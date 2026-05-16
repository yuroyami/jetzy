package jetzy.managers

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.aware.AttachCallback
import android.net.wifi.aware.DiscoverySession
import android.net.wifi.aware.DiscoverySessionCallback
import android.net.wifi.aware.PeerHandle
import android.net.wifi.aware.PublishConfig
import android.net.wifi.aware.PublishDiscoverySession
import android.net.wifi.aware.SubscribeConfig
import android.net.wifi.aware.SubscribeDiscoverySession
import android.net.wifi.aware.WifiAwareManager
import android.net.wifi.aware.WifiAwareNetworkInfo
import android.net.wifi.aware.WifiAwareNetworkSpecifier
import android.net.wifi.aware.WifiAwareSession
import android.os.Build
import android.os.Handler
import android.os.Looper
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import jetzy.MainActivity
import jetzy.p2p.P2pPeer
import jetzy.permissions.AndroidPermissionRequirements
import jetzy.permissions.PermissionRequirement
import jetzy.utils.JETZY_WIFI_AWARE_SERVICE
import jetzy.utils.PreferablyIO
import jetzy.utils.getDeviceName
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet6Address
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import kotlin.time.Duration.Companion.seconds

/**
 * Wi-Fi Aware (NAN) P2P manager. Cross-vendor interop with iOS 26+ peers and
 * other Wi-Fi-Aware-capable Android peers without needing a hotspot, a shared
 * Wi-Fi, or a QR scan.
 *
 * High-level flow:
 *  1. `attach()` to the system's [WifiAwareManager], get a [WifiAwareSession].
 *  2. Publish "jetzy" (lets peers discover us) **and** subscribe to "jetzy"
 *     (lets us discover peers). Symmetric like MPC.
 *  3. On `onServiceDiscovered`, the user picks a peer in the discovery UI; we
 *     send a HELLO message to it. Whichever side *received* HELLO becomes the
 *     server; the other becomes the client.
 *  4. Both sides request a NAN-provisioned IPv6 link via [WifiAwareNetworkSpecifier]
 *     through ConnectivityManager.
 *  5. Server binds a TCP `ServerSocket` to the NAN link-local IPv6, sends READY
 *     with its port via NAN message, accepts the inbound socket.
 *  6. Client receives READY, dials the server's IPv6+port using a [Socket]
 *     created on the NAN [Network] (so the routing is forced through NAN, not
 *     whatever Wi-Fi the device happens to also be on).
 *  7. Both sides bridge their Socket I/O streams to Ktor `ByteChannel`s and
 *     hand them to [P2PManager.startTransferWithChannels] — protocol layer
 *     takes over from there.
 *
 * Requires Android 8.0 (API 26) and the `android.hardware.wifi.aware` chipset
 * feature. The companion runtime check lives in [jetzy.utils.isWifiAwareSupported].
 */
class WifiAwareP2PM(private val context: Context) : PeerDiscoveryP2PM() {

    private val appContext = context.applicationContext
    private val awareManager = appContext.getSystemService(Context.WIFI_AWARE_SERVICE) as? WifiAwareManager
    private val connectivityManager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var session: WifiAwareSession? = null
    private var publishSession: PublishDiscoverySession? = null
    private var subscribeSession: SubscribeDiscoverySession? = null
    private var activeNetworkCallback: ConnectivityManager.NetworkCallback? = null

    private val peerHandles = mutableMapOf<String, PeerInfo>()

    /** Resolved once we have a bridged byte-channel pair and have called startTransferWithChannels. */
    private var connectionReady: CompletableDeferred<Boolean>? = null
    private val bridgeJobs = mutableListOf<Job>()

    /** Set on whichever side initiated the HELLO (client) vs received it (server). */
    private var localRole: Role = Role.Undecided
    private enum class Role { Undecided, Server, Client }

    private data class PeerInfo(
        val id: String,
        val displayName: String,
        var peerHandle: PeerHandle,
        var seenVia: DiscoverySession,
    )

    override val permissionRequirements: List<PermissionRequirement>
        get() {
            val activity = context as? MainActivity ?: return emptyList()
            return buildList {
                add(AndroidPermissionRequirements.nearbyDevices(activity))
                add(AndroidPermissionRequirements.postNotifications(activity))
                add(AndroidPermissionRequirements.wifiEnabled(activity))
                // Pre-Android 13, NEARBY_WIFI_DEVICES is location-derived so the
                // chipset also needs Location services on for proximity.
                if (Build.VERSION.SDK_INT in Build.VERSION_CODES.O..Build.VERSION_CODES.S_V2) {
                    add(AndroidPermissionRequirements.locationServicesEnabled(activity))
                }
            }
        }

    // ── Discovery lifecycle ────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    override suspend fun startDiscoveryAndAdvertising(deviceName: String) {
        val mgr = awareManager ?: run {
            diag("WifiAwareManager unavailable; chipset doesn't support NAN")
            viewmodel.snacky("This device doesn't support Wi-Fi Aware.")
            return
        }
        if (!mgr.isAvailable) {
            diag("WifiAwareManager.isAvailable == false; Wi-Fi off or temporarily disabled")
            viewmodel.snacky("Wi-Fi Aware is off — turn Wi-Fi on and try again.")
            return
        }

        isDiscovering.value = true
        isAdvertising.value = true

        val attached = CompletableDeferred<WifiAwareSession?>()
        mgr.attach(object : AttachCallback() {
            override fun onAttached(s: WifiAwareSession) {
                diag("WiFi Aware session attached")
                attached.complete(s)
            }
            override fun onAttachFailed() {
                diag("WiFi Aware attach failed")
                attached.complete(null)
            }
        }, mainHandler)

        val s = attached.await() ?: run {
            viewmodel.snacky("Couldn't start Wi-Fi Aware. Try again.")
            isDiscovering.value = false
            isAdvertising.value = false
            return
        }
        session = s

        val publishConfig = PublishConfig.Builder()
            .setServiceName(JETZY_WIFI_AWARE_SERVICE)
            .setServiceSpecificInfo(deviceName.toByteArray(StandardCharsets.UTF_8))
            .build()
        s.publish(publishConfig, publishCallback, mainHandler)

        val subscribeConfig = SubscribeConfig.Builder()
            .setServiceName(JETZY_WIFI_AWARE_SERVICE)
            .build()
        s.subscribe(subscribeConfig, subscribeCallback, mainHandler)
    }

    override suspend fun stopDiscoveryAndAdvertising() {
        runCatching { publishSession?.close() }
        runCatching { subscribeSession?.close() }
        publishSession = null
        subscribeSession = null
        isDiscovering.value = false
        isAdvertising.value = false
    }

    // ── Discovery callbacks ────────────────────────────────────────────────────

    private val publishCallback = object : DiscoverySessionCallback() {
        override fun onPublishStarted(session: PublishDiscoverySession) {
            diag("publish started")
            publishSession = session
        }

        override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
            val text = message.toString(StandardCharsets.UTF_8)
            diag("publish ← $text")
            handlePeerMessage(text, peerHandle, publishSession ?: return, asPublisher = true)
        }

        override fun onSessionConfigFailed() {
            diag("publish session config failed")
            viewmodel.snacky("Couldn't advertise over Wi-Fi Aware.")
        }
    }

    private val subscribeCallback = object : DiscoverySessionCallback() {
        override fun onSubscribeStarted(session: SubscribeDiscoverySession) {
            diag("subscribe started")
            subscribeSession = session
        }

        override fun onServiceDiscovered(
            peerHandle: PeerHandle,
            serviceSpecificInfo: ByteArray?,
            matchFilter: List<ByteArray>?,
        ) {
            val sub = subscribeSession ?: return
            val peerName = serviceSpecificInfo?.toString(StandardCharsets.UTF_8).orEmpty()
                .ifBlank { "Wi-Fi Aware peer" }
            // PeerHandles re-issue on every match; key by displayName so the UI list is stable.
            peerHandles[peerName] = PeerInfo(peerName, peerName, peerHandle, sub)
            availablePeers.value = peerHandles.values.map {
                P2pPeer(id = it.id, name = it.displayName, signalStrength = 3)
            }
            diag("discovered peer '$peerName'")
        }

        override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
            val text = message.toString(StandardCharsets.UTF_8)
            diag("subscribe ← $text")
            handlePeerMessage(text, peerHandle, subscribeSession ?: return, asPublisher = false)
        }

        override fun onSessionConfigFailed() {
            diag("subscribe session config failed")
            viewmodel.snacky("Couldn't discover Wi-Fi Aware peers.")
        }
    }

    // ── Rendezvous protocol over NAN messages ──────────────────────────────────
    //
    // Two ASCII-prefixed messages decide roles and exchange the TCP port:
    //   "HELLO\n<deviceName>"   — sent by client to server (initiates the handshake)
    //   "READY\n<port>"         — sent by server back to client (TCP server is up)
    //
    // We must send messages *via the same DiscoverySession we received the peer on*,
    // otherwise the NAN frames are routed to the wrong publish/subscribe context.

    private fun handlePeerMessage(text: String, peer: PeerHandle, via: DiscoverySession, asPublisher: Boolean) {
        when {
            text.startsWith("HELLO") && asPublisher -> {
                if (localRole != Role.Undecided) return
                localRole = Role.Server
                p2pScope.launch(PreferablyIO) { hostServerFor(peer, via) }
            }
            text.startsWith("READY") -> {
                if (localRole != Role.Client) return
                val port = text.substringAfter("\n").trim().toIntOrNull() ?: run {
                    diag("READY had bad port: '$text'"); connectionReady?.complete(false); return
                }
                p2pScope.launch(PreferablyIO) { connectClientTo(peer, via, port) }
            }
        }
    }

    override suspend fun connectToPeer(peer: P2pPeer): Result<Unit> {
        val info = peerHandles[peer.id]
            ?: return Result.failure(Exception("peer not found: ${peer.id}"))

        val ready = CompletableDeferred<Boolean>()
        connectionReady = ready
        localRole = Role.Client

        info.seenVia.sendMessage(
            info.peerHandle,
            MESSAGE_ID_HELLO,
            "HELLO\n${getDeviceName()}".toByteArray(StandardCharsets.UTF_8)
        )
        diag("HELLO sent to ${info.displayName}")

        val arrived = withTimeoutOrNull(CONNECT_TIMEOUT) { ready.await() } ?: false
        if (!arrived) {
            diag("Wi-Fi Aware connect timed out for ${info.displayName}")
            viewmodel.snacky("Couldn't connect to ${peer.name} over Wi-Fi Aware.")
            return Result.failure(Exception("Wi-Fi Aware connect timed out"))
        }
        return Result.success(Unit)
    }

    // ── Server side ────────────────────────────────────────────────────────────

    private suspend fun hostServerFor(peer: PeerHandle, via: DiscoverySession) {
        val (network, _) = requestNanNetwork(peer, via) ?: run {
            connectionReady?.complete(false); return
        }
        val localIpv6 = pickLinkLocalIpv6(network) ?: run {
            diag("no NAN IPv6 on this side")
            connectionReady?.complete(false); return
        }

        val server: ServerSocket = try {
            ServerSocket().apply { bind(java.net.InetSocketAddress(localIpv6, 0)) }
        } catch (e: Exception) {
            diag("ServerSocket bind failed: ${e.message}")
            connectionReady?.complete(false); return
        }
        diag("Wi-Fi Aware server bound on [${localIpv6.hostAddress}]:${server.localPort}")

        via.sendMessage(
            peer, MESSAGE_ID_READY,
            "READY\n${server.localPort}".toByteArray(StandardCharsets.UTF_8)
        )

        try {
            val socket = withTimeoutOrNull(ACCEPT_TIMEOUT) {
                // ServerSocket.accept() is blocking; the timeout cancels the coroutine
                // and the socket close in cleanup() will interrupt the blocked thread.
                server.accept()
            } ?: run {
                diag("server accept timed out")
                viewmodel.snacky("Peer didn't connect in time.")
                connectionReady?.complete(false)
                return
            }
            diag("inbound socket from ${socket.inetAddress?.hostAddress}")
            isHandshaking.value = true
            bridgeSocketToChannels(socket)
        } finally {
            runCatching { server.close() }
        }
    }

    // ── Client side ────────────────────────────────────────────────────────────

    private suspend fun connectClientTo(peer: PeerHandle, via: DiscoverySession, port: Int) {
        val (network, peerIpv6) = requestNanNetwork(peer, via) ?: run {
            connectionReady?.complete(false); return
        }
        if (peerIpv6 == null) {
            diag("no peer IPv6 from WifiAwareNetworkInfo")
            connectionReady?.complete(false); return
        }

        try {
            // Some chipsets briefly report the link up before routing is wired.
            delay(250)
            // Crucially, route the Socket through the NAN Network — otherwise the
            // kernel may pick the regular Wi-Fi route and fail to reach the peer.
            val socket = network.socketFactory.createSocket(peerIpv6, port)
            diag("Wi-Fi Aware client connected to [$peerIpv6]:$port")
            isHandshaking.value = true
            bridgeSocketToChannels(socket)
        } catch (e: Exception) {
            diag("client connect failed: ${e.message ?: e::class.simpleName}")
            viewmodel.snacky("Wi-Fi Aware client failed: ${e.message ?: "unknown"}")
            connectionReady?.complete(false)
        }
    }

    /**
     * Bridges a connected [Socket]'s InputStream/OutputStream to Ktor [ByteChannel]
     * pair, then calls [startTransferWithChannels]. Same pattern as MpcP2PM uses
     * for NSStreams — the protocol layer doesn't care what the bytes ride on.
     */
    private fun bridgeSocketToChannels(socket: Socket) {
        val read = ByteChannel()
        val write = ByteChannel()

        // Pump socket → readChannel
        bridgeJobs += p2pScope.launch(PreferablyIO) {
            val input = socket.getInputStream()
            val buf = ByteArray(bufferSize)
            try {
                while (true) {
                    val n = input.read(buf)
                    if (n == -1) break
                    read.writeFully(buf, 0, n)
                    read.flush()
                }
            } catch (e: Exception) {
                diag("read pump error: ${e.message}")
            } finally {
                read.close()
            }
        }

        // Pump writeChannel → socket
        bridgeJobs += p2pScope.launch(PreferablyIO) {
            val output = socket.getOutputStream()
            val buf = ByteArray(bufferSize)
            try {
                while (!write.isClosedForRead) {
                    val n = write.readAvailable(buf)
                    if (n <= 0) {
                        if (write.isClosedForRead) break
                        continue
                    }
                    output.write(buf, 0, n)
                    output.flush()
                }
            } catch (e: Exception) {
                diag("write pump error: ${e.message}")
            } finally {
                runCatching { output.close() }
                runCatching { socket.close() }
            }
        }

        connectionReady?.complete(true)
        startTransferWithChannels(input = read, output = write)
    }

    // ── NAN network request ────────────────────────────────────────────────────

    private suspend fun requestNanNetwork(peer: PeerHandle, via: DiscoverySession): Pair<Network, Inet6Address?>? {
        val specifier = WifiAwareNetworkSpecifier.Builder(via, peer)
            .setPskPassphrase(NAN_PASSPHRASE)
            .build()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
            .setNetworkSpecifier(specifier)
            .build()

        val gate = CompletableDeferred<Pair<Network, Inet6Address?>?>()
        val cb = object : ConnectivityManager.NetworkCallback() {
            private var net: Network? = null

            override fun onAvailable(network: Network) {
                diag("NAN network available")
                net = network
                // Wait for LinkProperties; peerIpv6 isn't known yet.
            }

            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
                val transportInfo = (connectivityManager.getNetworkCapabilities(network)
                    ?.transportInfo as? WifiAwareNetworkInfo)
                val peerIp = transportInfo?.peerIpv6Addr
                diag("NAN link ready, peerIpv6=$peerIp")
                if (!gate.isCompleted) gate.complete(network to peerIp)
            }

            override fun onUnavailable() {
                diag("NAN network unavailable")
                if (!gate.isCompleted) gate.complete(null)
            }
        }
        activeNetworkCallback = cb
        connectivityManager.requestNetwork(request, cb, NETWORK_REQUEST_TIMEOUT_MS)

        return withTimeoutOrNull(NETWORK_AWAIT_TIMEOUT) { gate.await() }
    }

    private fun pickLinkLocalIpv6(network: Network): Inet6Address? {
        val lp = connectivityManager.getLinkProperties(network) ?: return null
        return lp.linkAddresses
            .mapNotNull { it.address as? Inet6Address }
            .firstOrNull { !it.isLoopbackAddress }
    }

    // ── Cleanup ────────────────────────────────────────────────────────────────

    override suspend fun cleanup() {
        super.cleanup()
        stopDiscoveryAndAdvertising()
        bridgeJobs.forEach { runCatching { it.cancel() } }
        bridgeJobs.clear()
        activeNetworkCallback?.let { runCatching { connectivityManager.unregisterNetworkCallback(it) } }
        activeNetworkCallback = null
        runCatching { session?.close() }
        session = null
        connectionReady?.complete(false)
        connectionReady = null
        peerHandles.clear()
        localRole = Role.Undecided
    }

    companion object {
        private const val MESSAGE_ID_HELLO = 1
        private const val MESSAGE_ID_READY = 2
        private val CONNECT_TIMEOUT = 30.seconds
        private val ACCEPT_TIMEOUT = 30.seconds
        private val NETWORK_AWAIT_TIMEOUT = 20.seconds
        private const val NETWORK_REQUEST_TIMEOUT_MS = 20_000
        /** Shared PSK — same value on both sides → opportunistic NAN-link encryption. */
        private const val NAN_PASSPHRASE = "jetzy-nan-psk"
    }
}
