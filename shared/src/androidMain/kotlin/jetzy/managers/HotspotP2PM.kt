package jetzy.managers

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.connection
import io.ktor.network.sockets.port
import jetzy.models.QRData
import jetzy.utils.PreferablyIO
import jetzy.utils.getDeviceName
import jetzy.utils.loggy
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Enumeration
import kotlin.coroutines.resumeWithException

class HotspotP2PM(context: Context) : QRDiscoveryP2PM() {

    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null

    override val requiredPermissions: List<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun startLocalHotspotAsync(): Pair<String, String> = suspendCancellableCoroutine { cont ->
        wifiManager.startLocalOnlyHotspot(
            object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation?) {
                    this@HotspotP2PM.reservation = reservation

                    if (reservation == null) {
                        cont.resumeWithException(Exception("Reservation was null"))
                        return
                    }

                    val credentials: Pair<String, String>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        val config = reservation.softApConfiguration
                        val ssid = config.ssid ?: return cont.resumeWithException(Exception("SSID was null"))
                        val password = config.passphrase ?: return cont.resumeWithException(Exception("Passphrase was null"))
                        Pair(ssid, password)
                    } else {
                        @Suppress("DEPRECATION")
                        val config = reservation.wifiConfiguration
                        val ssid = config?.SSID ?: return cont.resumeWithException(Exception("SSID was null"))
                        val password = config.preSharedKey ?: return cont.resumeWithException(Exception("Password was null"))
                        Pair(ssid, password)
                    }

                    cont.resume(value = credentials!!, onCancellation = { _, _, _ -> })
                }

                override fun onStopped() {}

                override fun onFailed(reason: Int) {
                    cont.resumeWithException(Exception("Hotspot failed with reason $reason"))
                }
            }, null
        )

        cont.invokeOnCancellation {
            reservation?.close()
            reservation = null
        }
    }

    fun stopLocalHotspot() {
        reservation?.close()
        reservation = null
    }

    fun establishTcpServer(): Deferred<QRData?> = coroutineScope.async(PreferablyIO) {
        try {
            val (ssid, password) = startLocalHotspotAsync()

            val localAddress = getHotspotIpAddress() ?: return@async null

            val serverSocket = aSocket(SelectorManager(PreferablyIO))
               .tcp()
               .bind("0.0.0.0", 0)

            // launch the blocking accept() independently so it doesn't hold up the return
            coroutineScope.launch(PreferablyIO) {
                try {
                    loggy("######### Is waiting for serverSocket Acceptance #########")
                    val socket = serverSocket.accept()
                    connection = socket.connection()
                } catch (e: Exception) {
                    loggy("Accept failed: ${e.stackTraceToString()}")
                }
            }

            QRData(
                hotspotSSID = ssid,
                hotspotPassword = password,
                ipAddress = localAddress,
                port = serverSocket.port,
                deviceName = getDeviceName()
            )
        } catch (e: Exception) {
            loggy(e.stackTraceToString())
            null
        }
    }

    fun getHotspotIpAddress(): String? {
        try {
            val interfaces: Enumeration<NetworkInterface> = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface: NetworkInterface = interfaces.nextElement()
                val name = networkInterface.name
                loggy("Interface: $name")

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
                    loggy("  -> $ip")
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

}