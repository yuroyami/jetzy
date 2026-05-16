package jetzy.managers

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.ServerSocket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.connection
import io.ktor.network.sockets.port
import jetzy.p2p.P2pPeer
import jetzy.utils.JETZY_MDNS_SERVICE_TYPE
import jetzy.utils.PreferablyIO
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceInfo
import javax.jmdns.ServiceListener
import kotlin.time.Duration.Companion.seconds

/**
 * Desktop (JVM) mDNS / Bonjour peer manager using jmdns. Symmetric: both
 * advertise `_jetzy._tcp.local.` and browse for it. When the user taps a peer,
 * we dial its `(host, port)` over TCP. Mirrors the Android NsdManager flow
 * and is wire-compatible with iOS NWBrowser/NWListener.
 *
 * The jmdns instance is bound to the first non-loopback IPv4 interface — that
 * means we advertise on whichever Wi-Fi/Ethernet adapter is currently routable.
 * On hosts with multiple LAN NICs, we pick the first; users with weird routing
 * may need to disable other interfaces.
 */
class LanMdnsP2PM : PeerDiscoveryP2PM() {

    private var jmdns: JmDNS? = null
    private var registeredInfo: ServiceInfo? = null
    private var serverSocket: ServerSocket? = null

    private val foundPeers = mutableMapOf<String, ServiceInfo>()
    private var connectionReady: CompletableDeferred<Boolean>? = null

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    override suspend fun startDiscoveryAndAdvertising(deviceName: String) {
        isDiscovering.value = true
        isAdvertising.value = true

        val localAddr = pickLocalIPv4() ?: run {
            diag("no LAN IPv4 address — Wi-Fi/Ethernet down?")
            viewmodel.snacky("Couldn't find a LAN address. Connect to Wi-Fi or Ethernet first.")
            isDiscovering.value = false
            isAdvertising.value = false
            return
        }

        // Bind TCP server before advertising so the port is known.
        val bound = aSocket(SelectorManager(PreferablyIO)).tcp().bind("0.0.0.0", 0)
        serverSocket = bound
        diag("mDNS server bound on ${localAddr.hostAddress}:${bound.port}")

        p2pScope.launch(PreferablyIO) {
            try {
                val socket = bound.accept()
                isHandshaking.value = true
                connection = socket.connection()
                diag("mDNS inbound accepted")
                connectionReady?.complete(true)
            } catch (e: Exception) {
                diag("mDNS accept failed: ${e.message ?: e::class.simpleName}")
            }
        }

        // jmdns can take a couple of seconds to come up — do it on IO.
        p2pScope.launch(PreferablyIO) {
            try {
                val dns = JmDNS.create(localAddr, deviceName)
                jmdns = dns

                val info = ServiceInfo.create(
                    JETZY_MDNS_SERVICE_TYPE + "local.",
                    deviceName.take(63),
                    bound.port,
                    /* weight */ 0, /* priority */ 0,
                    /* properties */ emptyMap<String, String>(),
                )
                registeredInfo = info
                dns.registerService(info)
                diag("mDNS registered as '$deviceName' on ${JETZY_MDNS_SERVICE_TYPE}local.")

                dns.addServiceListener(JETZY_MDNS_SERVICE_TYPE + "local.", serviceListener)
            } catch (e: Exception) {
                diag("mDNS start failed: ${e.message ?: e::class.simpleName}")
                viewmodel.snacky("Couldn't start LAN discovery.")
            }
        }
    }

    override suspend fun stopDiscoveryAndAdvertising() {
        runCatching {
            jmdns?.let { dns ->
                registeredInfo?.let { dns.unregisterService(it) }
                dns.removeServiceListener(JETZY_MDNS_SERVICE_TYPE + "local.", serviceListener)
                dns.close()
            }
        }
        jmdns = null
        registeredInfo = null
        isDiscovering.value = false
        isAdvertising.value = false
    }

    private val serviceListener = object : ServiceListener {
        override fun serviceAdded(event: ServiceEvent) {
            // jmdns sends `serviceAdded` with only the name; we need `requestServiceInfo`
            // to actually resolve host/port.
            event.dns.requestServiceInfo(event.type, event.name, 1000)
        }

        override fun serviceRemoved(event: ServiceEvent) {
            val gone = foundPeers.remove(event.name) ?: return
            diag("mDNS lost ${gone.name}")
            availablePeers.value = foundPeers.values.map {
                P2pPeer(id = it.name, name = it.name, signalStrength = 3)
            }
        }

        override fun serviceResolved(event: ServiceEvent) {
            val info = event.info ?: return
            // Ignore our own advertisement.
            if (info.name == registeredInfo?.name) return
            foundPeers[info.name] = info
            availablePeers.value = foundPeers.values.map {
                P2pPeer(id = it.name, name = it.name, signalStrength = 3)
            }
            diag("mDNS resolved ${info.name} @ ${info.hostAddresses.firstOrNull()}:${info.port}")
        }
    }

    // ── Connect ────────────────────────────────────────────────────────────────

    override suspend fun connectToPeer(peer: P2pPeer): Result<Unit> {
        val info = foundPeers[peer.id]
            ?: return Result.failure(Exception("peer not resolved: ${peer.id}"))
        val host = info.hostAddresses.firstOrNull()
            ?: return Result.failure(Exception("peer has no address: ${peer.id}"))

        val ready = CompletableDeferred<Boolean>()
        connectionReady = ready

        p2pScope.launch(PreferablyIO) {
            try {
                diag("dialing $host:${info.port}…")
                val ktor = aSocket(SelectorManager(PreferablyIO))
                    .tcp()
                    .connect(host, info.port)
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

    private fun pickLocalIPv4(): InetAddress? = runCatching {
        NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress && it.isSiteLocalAddress }
    }.getOrNull()

    override suspend fun cleanup() {
        super.cleanup()
        stopDiscoveryAndAdvertising()
        runCatching { serverSocket?.close() }
        serverSocket = null
        connectionReady?.complete(false)
        connectionReady = null
        foundPeers.clear()
    }

    companion object {
        private val CONNECT_TIMEOUT = 15.seconds
    }
}
