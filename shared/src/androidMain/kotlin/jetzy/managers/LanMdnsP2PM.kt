package jetzy.managers

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.ServerSocket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.connection
import io.ktor.network.sockets.port
import jetzy.MainActivity
import jetzy.p2p.P2pPeer
import jetzy.permissions.AndroidPermissionRequirements
import jetzy.permissions.PermissionRequirement
import jetzy.utils.JETZY_MDNS_SERVICE_TYPE
import jetzy.utils.PreferablyIO
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress
import kotlin.time.Duration.Companion.seconds

/**
 * Android-side mDNS / Bonjour peer manager. Symmetric: both sides advertise an
 * `_jetzy._tcp.` service on the local Wi-Fi and browse for other peers running
 * the same. When the user taps a peer, the tapping side dials the advertised
 * `(host, port)` via TCP — protocol layer takes over from there.
 *
 * This collapses the LAN-mode QR-paste flow: no more pasting IP+port from one
 * device to the other. As long as both devices are on the same Wi-Fi and the
 * network doesn't block multicast, they auto-discover.
 *
 * Requires only the `INTERNET` and (for older devices) `ACCESS_WIFI_STATE`
 * permissions, both already declared in the shared module's manifest. No
 * runtime permission prompts needed.
 */
class LanMdnsP2PM(private val context: Context) : PeerDiscoveryP2PM() {

    private val appContext = context.applicationContext
    private val nsdManager = appContext.getSystemService(Context.NSD_SERVICE) as NsdManager

    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var serverSocket: ServerSocket? = null
    private var selectorManager: SelectorManager? = null

    private val foundPeers = mutableMapOf<String, NsdServiceInfo>()
    private var connectionReady: CompletableDeferred<Boolean>? = null

    /** Our own advertised service name (post-registration — NSD may rename it on a collision). */
    private var registeredServiceName: String? = null

    /** NsdManager allows only one outstanding resolve pre-API-34, so we serialize them. */
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    private fun selector(): SelectorManager =
        selectorManager ?: SelectorManager(PreferablyIO).also { selectorManager = it }

    override val permissionRequirements: List<PermissionRequirement>
        get() {
            val activity = context as? MainActivity ?: return emptyList()
            // mDNS multicast needs Wi-Fi to be on; nothing else (no NEARBY_WIFI_DEVICES
            // here, no location). We surface that one check.
            return listOf(AndroidPermissionRequirements.wifiEnabled(activity))
        }

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    override suspend fun startDiscoveryAndAdvertising(deviceName: String) {
        isDiscovering.value = true
        isAdvertising.value = true

        // Bind a TCP server first so we know which port to advertise.
        val bound = aSocket(selector()).tcp().bind("0.0.0.0", 0)
        serverSocket = bound
        diag("mDNS server bound on port ${bound.port}")

        // Accept inbound connections in the background — whichever side dials wins.
        p2pScope.launch(PreferablyIO) {
            try {
                val socket = bound.accept()
                isHandshaking.value = true
                connection = socket.connection()
                diag("mDNS inbound socket accepted")
                connectionReady?.complete(true)
            } catch (e: Exception) {
                diag("mDNS accept failed: ${e.message ?: e::class.simpleName}")
            }
        }

        registerService(deviceName, bound.port)
        startBrowsing()
    }

    override suspend fun stopDiscoveryAndAdvertising() {
        runCatching { registrationListener?.let { nsdManager.unregisterService(it) } }
        runCatching { discoveryListener?.let { nsdManager.stopServiceDiscovery(it) } }
        registrationListener = null
        discoveryListener = null
        isDiscovering.value = false
        isAdvertising.value = false
    }

    private fun registerService(deviceName: String, port: Int) {
        val info = NsdServiceInfo().apply {
            serviceName = deviceName.take(63)  // mDNS service-name length cap
            serviceType = JETZY_MDNS_SERVICE_TYPE
            this.port = port
        }
        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                registeredServiceName = info.serviceName
                diag("mDNS registered as '${info.serviceName}'")
            }
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                diag("mDNS register failed: $errorCode")
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
        }
        nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, registrationListener)
    }

    private fun startBrowsing() {
        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                diag("mDNS browse start failed: $errorCode")
                viewmodel.snacky("Couldn't browse the local network. Multicast may be blocked.")
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onDiscoveryStarted(serviceType: String) {
                diag("mDNS browse started")
            }
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                if (info.serviceName.isBlank()) return
                // Skip our own advertisement. Compare against the *registered* name (NSD may have
                // renamed it on a clash) so we don't discover and then dial ourselves.
                if (info.serviceName == registeredServiceName) return
                enqueueResolve(info)
            }
            override fun onServiceLost(info: NsdServiceInfo) {
                val gone = foundPeers.remove(info.serviceName) ?: return
                diag("mDNS lost peer ${gone.serviceName}")
                availablePeers.value = foundPeers.values.map {
                    P2pPeer(id = it.serviceName, name = it.serviceName, signalStrength = 3)
                }
            }
        }
        nsdManager.discoverServices(JETZY_MDNS_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
    }

    private fun enqueueResolve(info: NsdServiceInfo) {
        synchronized(resolveQueue) { resolveQueue.addLast(info) }
        pumpResolveQueue()
    }

    /** Resolves one service at a time — concurrent resolveService() calls fail with
     *  FAILURE_ALREADY_ACTIVE on API < 34, which is why only the first peer used to appear. */
    private fun pumpResolveQueue() {
        val next = synchronized(resolveQueue) {
            if (resolving) return
            val n = resolveQueue.removeFirstOrNull() ?: return
            resolving = true
            n
        }
        val resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                diag("mDNS resolve failed for ${info.serviceName}: $errorCode")
                synchronized(resolveQueue) { resolving = false }
                pumpResolveQueue()
            }
            override fun onServiceResolved(resolved: NsdServiceInfo) {
                foundPeers[resolved.serviceName] = resolved
                availablePeers.value = foundPeers.values.map {
                    P2pPeer(id = it.serviceName, name = it.serviceName, signalStrength = 3)
                }
                diag("mDNS resolved ${resolved.serviceName} @ ${resolved.host}:${resolved.port}")
                synchronized(resolveQueue) { resolving = false }
                pumpResolveQueue()
            }
        }
        @Suppress("DEPRECATION")
        nsdManager.resolveService(next, resolveListener)
    }

    // ── Connect ────────────────────────────────────────────────────────────────

    override suspend fun connectToPeer(peer: P2pPeer): Result<Unit> {
        val info = foundPeers[peer.id]
            ?: return Result.failure(Exception("peer not resolved: ${peer.id}"))
        val host: InetAddress = info.host
            ?: return Result.failure(Exception("peer has no host: ${peer.id}"))

        val ready = CompletableDeferred<Boolean>()
        connectionReady = ready

        p2pScope.launch(PreferablyIO) {
            try {
                diag("dialing ${host.hostAddress}:${info.port}…")
                val ktor = aSocket(selector())
                    .tcp()
                    .connect(host.hostAddress!!, info.port)
                isHandshaking.value = true
                connection = ktor.connection()
                diag("mDNS outbound connected")
                ready.complete(true)
            } catch (e: Exception) {
                diag("mDNS dial failed: ${e.message ?: e::class.simpleName}")
                viewmodel.snacky("Couldn't reach ${peer.name}: ${e.message ?: "unknown"}")
                ready.complete(false)
            }
        }

        val ok = withTimeoutOrNull(CONNECT_TIMEOUT) { ready.await() } ?: false
        return if (ok) Result.success(Unit)
        else Result.failure(Exception("mDNS connect timed out / failed"))
    }

    override suspend fun cleanup() {
        super.cleanup()
        stopDiscoveryAndAdvertising()
        runCatching { serverSocket?.close() }
        serverSocket = null
        runCatching { selectorManager?.close() }
        selectorManager = null
        connectionReady?.complete(false)
        connectionReady = null
        foundPeers.clear()
        synchronized(resolveQueue) { resolveQueue.clear(); resolving = false }
        registeredServiceName = null
    }

    companion object {
        private val CONNECT_TIMEOUT = 15.seconds
    }
}
