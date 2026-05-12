package jetzy.managers

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.WifiP2pManager.ActionListener
import android.os.Build
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.connection
import jetzy.MainActivity
import jetzy.p2p.P2pPeer
import jetzy.permissions.AndroidPermissionRequirements
import jetzy.permissions.PermissionRequirement
import jetzy.utils.PreferablyIO
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class WiFiDirectP2PM(private val context: Context) : PeerDiscoveryP2PM() {

    private val wifiP2pManager by lazy {
        context.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
    }
    private val wifiManager by lazy {
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    }

    private var channel: WifiP2pManager.Channel? = null
    private var receiver: BroadcastReceiver? = null

    /** Whether WiFi P2P is currently enabled on the device */
    val isWifiP2pEnabled = MutableStateFlow(false)

    /** The device name to use for advertising — saved so we can restart discovery */
    private var advertisedDeviceName: String? = null
    /** Peer currently being connected — so the broadcast-driven TCP step can pair with its retry loop. */
    private var pendingPeer: P2pPeer? = null

    override val permissionRequirements: List<PermissionRequirement>
        get() {
            // Cast is safe because MainActivity is what constructs the manager
            // (see MainActivity.getSuitableP2pManager). If a future caller changes
            // that contract we silently fall back to no requirements rather than
            // crash — the discovery screen would still surface its own errors.
            val activity = context as? MainActivity ?: return emptyList()
            return buildList {
                add(AndroidPermissionRequirements.nearbyDevices(activity))
                add(AndroidPermissionRequirements.postNotifications(activity))
                add(AndroidPermissionRequirements.wifiEnabled(activity))
                add(AndroidPermissionRequirements.ignoreBatteryOptimizations(activity))
            }
        }

    // ── Setup ─────────────────────────────────────────────────────────────────
    private fun ensureChannel() {
        if (channel != null) return
        channel = wifiP2pManager.initialize(context, context.mainLooper, null)
        diag("Wi-Fi Direct channel initialised")
    }

    // ── Discovery ─────────────────────────────────────────────────────────────
    @SuppressLint("MissingPermission")
    override suspend fun startDiscoveryAndAdvertising(deviceName: String) {
        advertisedDeviceName = deviceName
        ensureChannel()

        if (!wifiManager.isWifiEnabled) {
            diag("Wi-Fi is off — Wi-Fi Direct needs Wi-Fi enabled")
            viewmodel.snacky("Please turn on Wi-Fi to discover peers")
            return
        }

        registerReceiver()
        isDiscovering.value = true
        isAdvertising.value = true

        discoverWithRetry()
    }

    @SuppressLint("MissingPermission")
    private suspend fun discoverWithRetry() {
        val ch = channel ?: return
        repeat(DISCOVERY_ATTEMPTS) { attempt ->
            val reason = suspendCancellableCoroutine<Int?> { cont ->
                wifiP2pManager.discoverPeers(ch, object : ActionListener {
                    override fun onSuccess() { cont.resume(null) }
                    override fun onFailure(reason: Int) { cont.resume(reason) }
                })
            }
            if (reason == null) {
                diag("discovery started (attempt ${attempt + 1})")
                return
            }
            diag("discovery attempt ${attempt + 1}/$DISCOVERY_ATTEMPTS failed: ${wifiDirectError(reason)}")
            if (reason == WifiP2pManager.P2P_UNSUPPORTED) {
                viewmodel.snacky("This device doesn't support Wi-Fi Direct")
                return
            }
            if (attempt < DISCOVERY_ATTEMPTS - 1) {
                // BUSY/ERROR are transient — back off and retry.
                delay((1000L * (attempt + 1)).milliseconds)
            }
        }
        viewmodel.snacky("Couldn't start Wi-Fi Direct discovery. Pull down to try again.")
    }

    /** Restart discovery after WiFi P2P becomes available again. */
    private fun restartDiscovery() {
        p2pScope.launch {
            diag("restarting discovery after P2P became available")
            isDiscovering.value = true
            isAdvertising.value = true
            discoverWithRetry()
        }
    }

    override suspend fun stopDiscoveryAndAdvertising() {
        isDiscovering.value = false
        isAdvertising.value = false
        try {
            channel?.let { wifiP2pManager.stopPeerDiscovery(it, null) }
            unregisterReceiver()
        } catch (e: Exception) {
            diag("stopDiscovery error: ${e.message}")
        }
    }

    // ── Receiver ──────────────────────────────────────────────────────────────

    private fun registerReceiver() {
        if (receiver != null) return // already registered
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }
        receiver = object : BroadcastReceiver() {
            @SuppressLint("MissingPermission")
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.action) {
                    WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                        val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                        val enabled = state == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                        val wasEnabled = isWifiP2pEnabled.value
                        isWifiP2pEnabled.value = enabled
                        diag("Wi-Fi P2P state: enabled=$enabled")

                        if (enabled && !wasEnabled) {
                            restartDiscovery()
                        } else if (!enabled) {
                            availablePeers.value = emptyList()
                            isDiscovering.value = false
                            isAdvertising.value = false
                        }
                    }
                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                        wifiP2pManager.requestPeers(channel) { peerList ->
                            val peers = peerList.deviceList.map { device ->
                                P2pPeer(
                                    id = device.deviceAddress,
                                    name = device.deviceName.ifBlank { device.deviceAddress },
                                    signalStrength = signalFromStatus(device.status)
                                )
                            }
                            availablePeers.value = peers
                        }
                    }
                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                        // android.net.NetworkInfo is deprecated (use ConnectivityManager.NetworkCallback +
                        // NetworkCapabilities), but the Wi-Fi Direct broadcast extra is still shipped as
                        // NetworkInfo — there is no modern equivalent on the WifiP2pManager broadcast.
                        @Suppress("DEPRECATION")
                        val networkInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO, android.net.NetworkInfo::class.java)
                        } else {
                            intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO)
                        }
                        @Suppress("DEPRECATION")
                        if (networkInfo?.isConnected == true) {
                            wifiP2pManager.requestConnectionInfo(channel) { info ->
                                if (info?.groupFormed == true) {
                                    val goIp = info.groupOwnerAddress?.hostAddress ?: return@requestConnectionInfo
                                    val isGO = info.isGroupOwner
                                    diag("group formed — GO=$goIp, isGroupOwner=$isGO")
                                    p2pScope.launch {
                                        handleP2pConnection(goIp, isGO)
                                    }
                                }
                            }
                        } else if (networkInfo != null) {
                            diag("connection changed — not connected")
                        }
                    }
                }
            }
        }
        context.registerReceiver(receiver, filter)
    }

    private fun unregisterReceiver() {
        receiver?.let {
            try { context.unregisterReceiver(it) } catch (_: Exception) {}
            receiver = null
        }
    }

    // ── Connection ────────────────────────────────────────────────────────────
    @SuppressLint("MissingPermission")
    override suspend fun connectToPeer(peer: P2pPeer): Result<Unit> {
        val ch = channel ?: return Result.failure(Exception("Channel not initialised"))
        pendingPeer = peer
        diag("connecting to '${peer.name}' (${peer.id})")

        repeat(CONNECT_ATTEMPTS) { attempt ->
            val reason = suspendCancellableCoroutine<Int?> { cont ->
                val config = WifiP2pConfig().apply { deviceAddress = peer.id }
                wifiP2pManager.connect(ch, config, object : ActionListener {
                    override fun onSuccess() { cont.resume(null) }
                    override fun onFailure(reason: Int) { cont.resume(reason) }
                })
            }
            if (reason == null) {
                diag("connect request accepted (attempt ${attempt + 1}); waiting for group formation")
                // Wait up to CONNECTION_FORMATION_TIMEOUT for the CONNECTION_CHANGED broadcast
                // to promote `connection` via handleP2pConnection. If nothing happens, treat the
                // attempt as failed and retry.
                val arrived = withTimeoutOrNull(CONNECTION_FORMATION_TIMEOUT) {
                    while (connection == null) delay(500.milliseconds)
                    true
                } ?: false
                if (arrived) {
                    diag("connection established")
                    return Result.success(Unit)
                }
                diag("no group formation within ${CONNECTION_FORMATION_TIMEOUT} on attempt ${attempt + 1}; cancelling connect to retry")
                // Cancel the pending connect so the next retry is clean.
                suspendCancellableCoroutine<Unit> { cont ->
                    wifiP2pManager.cancelConnect(ch, object : ActionListener {
                        override fun onSuccess() { cont.resume(Unit) }
                        override fun onFailure(reason: Int) { cont.resume(Unit) }
                    })
                }
            } else {
                diag("connect attempt ${attempt + 1}/$CONNECT_ATTEMPTS failed: ${wifiDirectError(reason)}")
                if (reason == WifiP2pManager.P2P_UNSUPPORTED) {
                    return Result.failure(Exception("Wi-Fi Direct not supported on this device"))
                }
            }
            if (attempt < CONNECT_ATTEMPTS - 1) {
                delay((1500L * (attempt + 1)).milliseconds)
            }
        }

        pendingPeer = null
        val err = "Couldn't connect to ${peer.name} after $CONNECT_ATTEMPTS attempts"
        viewmodel.snacky(err)
        return Result.failure(Exception(err))
    }

    // ── Socket setup after connection ─────────────────────────────────────────
    private suspend fun handleP2pConnection(groupOwnerIp: String, isGroupOwner: Boolean) {
        try {
            val selectorManager = SelectorManager(PreferablyIO)
            if (isGroupOwner) {
                val serverSocket = aSocket(selectorManager).tcp().bind("0.0.0.0", PORT)
                diag("Wi-Fi Direct server listening on $PORT")
                val socket = withTimeoutOrNull(GO_ACCEPT_TIMEOUT) { serverSocket.accept() }
                runCatching { serverSocket.close() }
                if (socket == null) {
                    diag("no client connected within ${GO_ACCEPT_TIMEOUT}; closing server")
                    return
                }
                connection = socket.connection()
                diag("client connected on TCP $PORT")
            } else {
                diag("dialing group owner $groupOwnerIp:$PORT")
                repeat(CLIENT_TCP_ATTEMPTS) { attempt ->
                    val ok = runCatching {
                        val socket = aSocket(selectorManager).tcp().connect(groupOwnerIp, PORT)
                        connection = socket.connection()
                    }.isSuccess
                    if (ok) {
                        diag("TCP connected on attempt ${attempt + 1}")
                        return
                    }
                    diag("TCP attempt ${attempt + 1}/$CLIENT_TCP_ATTEMPTS failed; retrying")
                    if (attempt < CLIENT_TCP_ATTEMPTS - 1) delay(1500.milliseconds)
                }
                diag("client TCP gave up after $CLIENT_TCP_ATTEMPTS attempts")
                viewmodel.snacky("Couldn't reach the other device over TCP. Please retry.")
            }
        } catch (e: Exception) {
            diag("handleP2pConnection failed: ${e.message ?: e::class.simpleName}")
        }
    }

    // ── Cleanup ───────────────────────────────────────────────────────────────
    override suspend fun cleanup() {
        super.cleanup()
        stopDiscoveryAndAdvertising()
        channel?.let { wifiP2pManager.removeGroup(it, null) }
        channel = null
    }

    // ── Helpers ───────────────────────────────────────────────────────────────
    private fun signalFromStatus(status: Int) = when (status) {
        WifiP2pDevice.AVAILABLE   -> 4
        WifiP2pDevice.INVITED     -> 3
        WifiP2pDevice.CONNECTED   -> 4
        WifiP2pDevice.FAILED      -> 1
        WifiP2pDevice.UNAVAILABLE -> 0
        else -> 2
    }

    private fun wifiDirectError(reason: Int) = when (reason) {
        WifiP2pManager.ERROR             -> "internal error"
        WifiP2pManager.P2P_UNSUPPORTED   -> "Wi-Fi Direct not supported"
        WifiP2pManager.BUSY              -> "framework busy"
        WifiP2pManager.NO_SERVICE_REQUESTS -> "no service requests registered"
        else -> "unknown error ($reason)"
    }

    companion object {
        private const val PORT = 49152
        private const val DISCOVERY_ATTEMPTS = 3
        private const val CONNECT_ATTEMPTS = 3
        private const val CLIENT_TCP_ATTEMPTS = 5
        private val CONNECTION_FORMATION_TIMEOUT = 25.seconds
        private val GO_ACCEPT_TIMEOUT = 60.seconds
    }
}
