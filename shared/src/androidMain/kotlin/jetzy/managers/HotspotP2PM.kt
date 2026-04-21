package jetzy.managers

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.ServerSocket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.connection
import io.ktor.network.sockets.port
import jetzy.models.QRData
import jetzy.utils.PreferablyIO
import jetzy.utils.getDeviceName
import jetzy.utils.loggy
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Enumeration
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

class HotspotP2PM(context: Context) : P2PManager() {

    private val appContext = context.applicationContext
    private val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val locationManager = appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null
    private var serverSocket: ServerSocket? = null

    override val requiredPermissions: List<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    @OptIn(ExperimentalUuidApi::class)
    fun establishTcpServer(): Deferred<QRData?> = p2pScope.async(PreferablyIO) {
        try {
            // Close any stale hotspot / socket from a previous attempt before starting fresh.
            runCatching { reservation?.close() }
            reservation = null
            runCatching { serverSocket?.close() }
            serverSocket = null

            // ── Pre-flight readiness checks ────────────────────────────────
            if (!wifiManager.isWifiEnabled) {
                diag("Wi-Fi is off — hotspot needs Wi-Fi enabled to start")
                viewmodel.snacky("Please turn on Wi-Fi to start the hotspot")
                return@async null
            }
            if (Build.VERSION.SDK_INT in Build.VERSION_CODES.P..Build.VERSION_CODES.S_V2) {
                // Android 9–12 require Location services to be on for SSID visibility.
                val locationOn = locationManager?.let {
                    runCatching { it.isLocationEnabled }.getOrDefault(true)
                } ?: true
                if (!locationOn) {
                    diag("Location services disabled — Android 9–12 need them on for LocalOnlyHotspot")
                    viewmodel.snacky("Please turn on Location services so the QR can carry the Wi-Fi name")
                    return@async null
                }
            }

            // ── Hotspot start with up to 3 retries for transient failures ──
            diag("starting LocalOnlyHotspot…")
            val (ssid, password) = startLocalHotspotWithRetry()
            diag("hotspot up: SSID='$ssid'")

            val localAddress = waitForHotspotIpAddress()
            if (localAddress == null) {
                diag("no IP address on any hotspot interface after 3s")
                viewmodel.snacky("Hotspot started but no IP address was assigned. Please try again.")
                return@async null
            }
            diag("hotspot IP: $localAddress")

            val boundSocket = aSocket(SelectorManager(PreferablyIO))
                .tcp()
                .bind("0.0.0.0", 0)
            serverSocket = boundSocket
            diag("server socket bound on port ${boundSocket.port}")

            // Pin a session id *before* the QR is minted so receiver can reattach to the
            // same session if the first connection drops. A fresh session id means
            // "start over, ignore any partial files on disk".
            val session = sessionId.value ?: Uuid.random().toString().also { sessionId.value = it }

            // launch the blocking accept() independently so it doesn't hold up the return
            p2pScope.launch(PreferablyIO) {
                try {
                    diag("waiting for receiver to connect…")
                    val socket = boundSocket.accept()
                    isHandshaking.value = true
                    connection = socket.connection()
                    diag("receiver connected")
                } catch (e: Exception) {
                    diag("accept failed: ${e.message ?: e::class.simpleName}")
                }
            }

            QRData(
                hotspotSSID = ssid,
                hotspotPassword = password,
                ipAddress = localAddress,
                port = boundSocket.port,
                deviceName = getDeviceName(),
                sessionId = session,
            )
        } catch (e: Exception) {
            diag("hotspot start failed: ${e.message ?: e::class.simpleName}")
            viewmodel.snacky("Couldn't start hotspot: ${e.message ?: "unknown error"}")
            null
        }
    }

    /** Start the hotspot with exponential-ish backoff. Gives up after ATTEMPTS failures. */
    private suspend fun startLocalHotspotWithRetry(): Pair<String, String> {
        var lastError: Throwable? = null
        repeat(HOTSPOT_START_ATTEMPTS) { attempt ->
            try {
                return startLocalHotspotAsync()
            } catch (e: Exception) {
                lastError = e
                diag("hotspot attempt ${attempt + 1}/$HOTSPOT_START_ATTEMPTS failed: ${e.message}")
                // The system needs a moment before it'll accept another request, especially
                // after ERROR_INCOMPATIBLE_MODE (3) or ERROR_NO_CHANNEL (4).
                runCatching { reservation?.close() }
                reservation = null
                if (attempt < HOTSPOT_START_ATTEMPTS - 1) {
                    delay((500L * (attempt + 1)).milliseconds)
                }
            }
        }
        throw lastError ?: Exception("Hotspot start failed after $HOTSPOT_START_ATTEMPTS attempts")
    }

    @SuppressLint("MissingPermission")
    private suspend fun startLocalHotspotAsync(): Pair<String, String> = suspendCancellableCoroutine { cont ->
        wifiManager.startLocalOnlyHotspot(
            object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation?) {
                    this@HotspotP2PM.reservation = reservation

                    if (reservation == null) {
                        cont.resumeWithException(Exception("Reservation was null"))
                        return
                    }

                    // SoftApConfiguration.ssid and legacy WifiConfiguration.SSID/preSharedKey are
                    // marked @Deprecated on Android 33+. Keeping both paths (pre-30 and 30+) intentionally
                    // because modern SoftApConfiguration.wifiSsid (API 33+) returns a WifiSsid object that
                    // requires another codepath just to extract the plain-text SSID. Suppressing here.
                    @Suppress("DEPRECATION")
                    val credentials: Pair<String, String>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        val config = reservation.softApConfiguration
                        val ssid = config.ssid ?: return cont.resumeWithException(Exception("SSID was null"))
                        val password = config.passphrase ?: return cont.resumeWithException(Exception("Passphrase was null"))
                        Pair(ssid, password)
                    } else {
                        val config = reservation.wifiConfiguration
                        val ssid = config?.SSID ?: return cont.resumeWithException(Exception("SSID was null"))
                        val password = config.preSharedKey ?: return cont.resumeWithException(Exception("Password was null"))
                        Pair(ssid, password)
                    }

                    cont.resume(value = credentials!!, onCancellation = { _, _, _ -> })
                }

                override fun onStopped() {}

                override fun onFailed(reason: Int) {
                    cont.resumeWithException(Exception("Hotspot failed: ${hotspotReasonString(reason)}"))
                }
            }, null
        )

        cont.invokeOnCancellation {
            reservation?.close()
            reservation = null
        }
    }

    /**
     * Poll for the hotspot interface's IP. On many OEM ROMs the interface takes
     * 500ms – 2s to get an address after [onStarted] fires.
     */
    private suspend fun waitForHotspotIpAddress(): String? {
        val deadline = System.currentTimeMillis() + 3.seconds.inWholeMilliseconds
        while (System.currentTimeMillis() < deadline) {
            getHotspotIpAddress()?.let { return it }
            delay(250.milliseconds)
        }
        return null
    }

    override suspend fun cleanup() {
        super.cleanup()
        runCatching {
            reservation?.close()
            reservation = null
        }
        runCatching {
            serverSocket?.close()
            serverSocket = null
        }
    }

    /**
     * When the user kicks off a resume, close the existing hotspot reservation so
     * [establishTcpServer] can spin up a fresh one (with its own SSID/password) and
     * mint a new QR — but keep the session id and receiver ledger via the base impl.
     */
    override suspend fun prepareForResume() {
        super.prepareForResume()
        runCatching {
            reservation?.close()
            reservation = null
        }
        runCatching {
            serverSocket?.close()
            serverSocket = null
        }
    }

    fun getHotspotIpAddress(): String? {
        try {
            val interfaces: Enumeration<NetworkInterface> = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface: NetworkInterface = interfaces.nextElement()
                val name = networkInterface.name

                // hotspot interfaces are typically ap0, wlan1, wlan2, swlan0 etc.
                // but NOT wlan0 which is the regular Wi-Fi client interface
                val isHotspotInterface = (name.startsWith("ap") ||
                        name.startsWith("swlan") ||
                        (name.startsWith("wlan") && name != "wlan0"))

                if (!isHotspotInterface) continue

                val addresses: Enumeration<InetAddress> = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address: InetAddress = addresses.nextElement()
                    val ip = address.hostAddress ?: continue
                    if (!address.isLoopbackAddress && address is Inet4Address) {
                        return ip
                    }
                }
            }
        } catch (e: Exception) {
            loggy(e.stackTraceToString())
        }
        return null
    }

    companion object {
        private const val HOTSPOT_START_ATTEMPTS = 3

        private fun hotspotReasonString(reason: Int): String = when (reason) {
            WifiManager.LocalOnlyHotspotCallback.ERROR_NO_CHANNEL -> "no Wi-Fi channel available (reason 1)"
            WifiManager.LocalOnlyHotspotCallback.ERROR_GENERIC -> "generic error (reason 2)"
            WifiManager.LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE -> "Wi-Fi is in an incompatible mode (reason 3)"
            WifiManager.LocalOnlyHotspotCallback.ERROR_TETHERING_DISALLOWED -> "tethering is disallowed by policy (reason 4)"
            else -> "unknown reason $reason"
        }
    }
}
