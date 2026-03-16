package jetzy.managers

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.WifiP2pManager.ActionListener
import android.os.Build
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.connection
import jetzy.p2p.P2pPeer
import jetzy.utils.PreferablyIO
import jetzy.utils.loggy
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class WiFiDirectP2PM(private val context: Context) : PeerDiscoveryP2PM() {

    private val wifiP2pManager by lazy {
        context.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
    }

    private var channel: WifiP2pManager.Channel? = null
    private var receiver: BroadcastReceiver? = null

    override val requiredPermissions = buildList<String> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    // ── Setup ─────────────────────────────────────────────────────────────────
    private fun ensureChannel() {
        if (channel != null) return
        channel = wifiP2pManager.initialize(context, context.mainLooper, null)
    }

    // ── Discovery ─────────────────────────────────────────────────────────────
    @SuppressLint("MissingPermission")
    override suspend fun startDiscoveryAndAdvertising(deviceName: String) {
        ensureChannel()
        isDiscovering.value = true
        isAdvertising.value = true

        registerReceiver()

        suspendCancellableCoroutine { cont ->
            wifiP2pManager.discoverPeers(channel, object : ActionListener {
                override fun onSuccess() {
                    loggy("WiFi Direct discovery started")
                    cont.resume(Unit)
                }
                override fun onFailure(reason: Int) {
                    val msg = wifiDirectError(reason)
                    loggy("Discovery failed: $msg")
                    // don't resumeWithException — the receiver will still get peers
                    // if the framework has cached results
                    cont.resume(Unit)
                }
            })
            cont.invokeOnCancellation {
                p2pScope.launch {
                    stopDiscoveryAndAdvertising()
                }
            }
        }
    }

    override suspend fun stopDiscoveryAndAdvertising() {
        isDiscovering.value = false
        isAdvertising.value = false
        try {
            channel?.let { wifiP2pManager.stopPeerDiscovery(it, null) }
            unregisterReceiver()
        } catch (e: Exception) {
            loggy("stopDiscovery error: ${e.message}")
        }
    }

    // ── Receiver ──────────────────────────────────────────────────────────────

    private fun registerReceiver() {
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
                            loggy("Peers updated: ${peers.map { it.name }}")
                        }
                    }
                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                        val networkInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO, android.net.NetworkInfo::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO)
                        }
                        if (networkInfo?.isConnected == true) {
                            wifiP2pManager.requestConnectionInfo(channel) { info ->
                                if (info?.groupFormed == true) {
                                    val goIp = info.groupOwnerAddress?.hostAddress ?: return@requestConnectionInfo
                                    val isGO = info.isGroupOwner
                                    loggy("Connected — GO IP: $goIp, isGroupOwner: $isGO")
                                    p2pScope.launch {
                                        handleP2pConnection(goIp, isGO)
                                    }
                                }
                            }
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
        val ch = channel ?: return Result.failure(Exception("Channel not initialized"))

        return suspendCancellableCoroutine { cont ->
            val config = WifiP2pConfig().apply {
                deviceAddress = peer.id
            }
            wifiP2pManager.connect(ch, config, object : ActionListener {
                override fun onSuccess() {
                    loggy("Connect initiated to ${peer.name}")
                    // actual connection result comes via WIFI_P2P_CONNECTION_CHANGED_ACTION
                    cont.resume(Result.success(Unit))
                }
                override fun onFailure(reason: Int) {
                    cont.resume(Result.failure(Exception(wifiDirectError(reason))))
                }
            })
        }
    }

    // ── Socket setup after connection ─────────────────────────────────────────
    private suspend fun handleP2pConnection(groupOwnerIp: String, isGroupOwner: Boolean) {
        try {
            val selectorManager = SelectorManager(PreferablyIO)
            if (isGroupOwner) {
                // this device is the server — bind and wait
                val serverSocket = aSocket(selectorManager).tcp().bind("0.0.0.0", PORT)
                loggy("WiFi Direct server listening on port $PORT")
                val socket = serverSocket.accept()
                connection = socket.connection()
                loggy("WiFi Direct client connected")
            } else {
                // this device is the client — connect to GO
                loggy("WiFi Direct connecting to GO: $groupOwnerIp:$PORT")
                while (connection == null) {
                    runCatching {
                        val socket = aSocket(selectorManager).tcp().connect(groupOwnerIp, PORT)
                        connection = socket.connection()
                        loggy("WiFi Direct connected to group owner")
                    }.onFailure { e ->
                        loggy("Retrying WiFi Direct connection: ${e.message}")
                        kotlinx.coroutines.delay(1500)
                    }
                }
            }
        } catch (e: Exception) {
            loggy("handleP2pConnection failed: ${e.stackTraceToString()}")
        }
    }

    // ── Cleanup ───────────────────────────────────────────────────────────────
    override suspend fun cleanup() {
        stopDiscoveryAndAdvertising()
        channel?.let { wifiP2pManager.removeGroup(it, null) }
        channel = null
        connection = null
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
        WifiP2pManager.ERROR             -> "Internal error"
        WifiP2pManager.P2P_UNSUPPORTED   -> "Wi-Fi Direct not supported"
        WifiP2pManager.BUSY              -> "Framework is busy, try again"
        WifiP2pManager.NO_SERVICE_REQUESTS -> "No service requests registered"
        else -> "Unknown error ($reason)"
    }

    companion object {
        private const val PORT = 49152  // fixed well-known port for Wi-Fi Direct
        // unlike hotspot where we use a random port (and QR code to communicate it),
        // Wi-Fi Direct peers both know they'll connect on this fixed port
    }
}
