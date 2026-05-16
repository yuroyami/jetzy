package jetzy.managers

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.ServerSocket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.connection
import io.ktor.network.sockets.port
import jetzy.p2p.P2pPeer
import jetzy.utils.JETZY_MDNS_SERVICE_TYPE_NO_DOT
import jetzy.utils.PreferablyIO
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import platform.Foundation.NSNetService
import platform.Foundation.NSNetServiceBrowser
import platform.Foundation.NSNetServiceBrowserDelegateProtocol
import platform.Foundation.NSNetServiceDelegateProtocol
import platform.darwin.NSObject
import kotlin.time.Duration.Companion.seconds

/**
 * iOS mDNS / Bonjour peer manager. Uses Foundation's `NSNetServiceBrowser` for
 * discovery and `NSNetService` for advertising — both still supported on iOS 16+
 * and they're the lowest-friction Bonjour APIs (Network framework's NWBrowser
 * is the modern way but its iOS 16 surface is awkward for our use).
 *
 * Note: iOS asks the user for "Local Network" permission the first time we try
 * to discover. We declare the prompt string and the `NSBonjourServices` plist
 * key in `Info.plist`.
 */
class LanMdnsP2PM : PeerDiscoveryP2PM() {

    private var advertiser: NSNetService? = null
    private var browser: NSNetServiceBrowser? = null
    private var serverSocket: ServerSocket? = null

    private val advertisingDelegate = AdvertisingDelegate()
    private val browsingDelegate = BrowsingDelegate()

    // Discovered services awaiting resolution → resolved peers.
    private val pendingResolve = mutableListOf<NSNetService>()
    private val resolvedPeers = mutableMapOf<String, NSNetService>()

    private var connectionReady: CompletableDeferred<Boolean>? = null

    init {
        browsingDelegate.onServiceFound = { service ->
            // Filter own advertisement by name.
            if (service.name != advertiser?.name) {
                val resolveDelegate = ResolvingDelegate { resolved ->
                    val addr = resolved.firstResolvableAddress()
                    if (addr != null) {
                        resolvedPeers[resolved.name] = resolved
                        availablePeers.value = resolvedPeers.values.map {
                            P2pPeer(id = it.name, name = it.name, signalStrength = 3)
                        }
                        diag("mDNS resolved ${resolved.name} @ $addr:${resolved.port}")
                    }
                }
                service.delegate = resolveDelegate
                pendingResolve += service
                service.resolveWithTimeout(5.0)
            }
        }
        browsingDelegate.onServiceLost = { service ->
            resolvedPeers.remove(service.name)
            availablePeers.value = resolvedPeers.values.map {
                P2pPeer(id = it.name, name = it.name, signalStrength = 3)
            }
            diag("mDNS lost ${service.name}")
        }
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    override suspend fun startDiscoveryAndAdvertising(deviceName: String) {
        isDiscovering.value = true
        isAdvertising.value = true

        // Bind TCP server before advertising so the port can be announced.
        val bound = aSocket(SelectorManager(PreferablyIO)).tcp().bind("0.0.0.0", 0)
        serverSocket = bound
        diag("mDNS server bound on port ${bound.port}")

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

        // Advertise.
        val adv = NSNetService(
            domain = "local.",
            type = "$JETZY_MDNS_SERVICE_TYPE_NO_DOT.",
            name = deviceName,
            port = bound.port,
        )
        adv.delegate = advertisingDelegate
        adv.publish()
        advertiser = adv

        // Browse.
        val b = NSNetServiceBrowser()
        b.delegate = browsingDelegate
        b.searchForServicesOfType("$JETZY_MDNS_SERVICE_TYPE_NO_DOT.", inDomain = "local.")
        browser = b
        diag("mDNS browsing for $JETZY_MDNS_SERVICE_TYPE_NO_DOT.local.")
    }

    override suspend fun stopDiscoveryAndAdvertising() {
        advertiser?.stop()
        browser?.stop()
        advertiser = null
        browser = null
        pendingResolve.forEach { it.stop() }
        pendingResolve.clear()
        isDiscovering.value = false
        isAdvertising.value = false
    }

    // ── Connect ────────────────────────────────────────────────────────────────

    @OptIn(ExperimentalForeignApi::class)
    override suspend fun connectToPeer(peer: P2pPeer): Result<Unit> {
        val service = resolvedPeers[peer.id]
            ?: return Result.failure(Exception("peer not resolved: ${peer.id}"))
        val host = service.firstResolvableAddress()
            ?: return Result.failure(Exception("peer has no resolvable address"))

        val ready = CompletableDeferred<Boolean>()
        connectionReady = ready

        p2pScope.launch(PreferablyIO) {
            try {
                diag("dialing $host:${service.port}…")
                val ktor = aSocket(SelectorManager(PreferablyIO))
                    .tcp()
                    .connect(host, service.port.toInt())
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
        connectionReady?.complete(false)
        connectionReady = null
        resolvedPeers.clear()
    }

    companion object {
        private val CONNECT_TIMEOUT = 15.seconds
    }
}

// ── Delegates ──────────────────────────────────────────────────────────────────

private class AdvertisingDelegate : NSObject(), NSNetServiceDelegateProtocol {
    override fun netServiceDidPublish(sender: NSNetService) { /* no-op */ }
    override fun netService(sender: NSNetService, didNotPublish: Map<Any?, *>) { /* surface via diag if needed */ }
}

private class BrowsingDelegate : NSObject(), NSNetServiceBrowserDelegateProtocol {
    var onServiceFound: ((NSNetService) -> Unit)? = null
    var onServiceLost: ((NSNetService) -> Unit)? = null

    // NSNetServiceBrowserDelegate exposes two methods with the same Kotlin shape
    // (didFindService / didFindDomain, didRemoveService / didRemoveDomain). We
    // only care about the *Service variants; the @ObjCSignatureOverride annotation
    // tells the compiler we know about the collision.
    @kotlinx.cinterop.ObjCSignatureOverride
    override fun netServiceBrowser(
        browser: NSNetServiceBrowser,
        didFindService: NSNetService,
        moreComing: Boolean,
    ) {
        onServiceFound?.invoke(didFindService)
    }

    @kotlinx.cinterop.ObjCSignatureOverride
    override fun netServiceBrowser(
        browser: NSNetServiceBrowser,
        didRemoveService: NSNetService,
        moreComing: Boolean,
    ) {
        onServiceLost?.invoke(didRemoveService)
    }
}

private class ResolvingDelegate(
    private val onResolved: (NSNetService) -> Unit,
) : NSObject(), NSNetServiceDelegateProtocol {
    override fun netServiceDidResolveAddress(sender: NSNetService) {
        onResolved(sender)
    }
    override fun netService(sender: NSNetService, didNotResolve: Map<Any?, *>) { /* fall through; UI shows nothing */ }
}

/**
 * Walk the resolved addresses and pick the first IPv4 or IPv6 we can convert
 * to a host string. `NSNetService.addresses` returns NSData blobs holding
 * sockaddr structures; we read the family and extract the textual form.
 */
@OptIn(ExperimentalForeignApi::class)
private fun NSNetService.firstResolvableAddress(): String? {
    val addrs = addresses ?: return null
    for (anyAddr in addrs) {
        val data = anyAddr as? platform.Foundation.NSData ?: continue
        val text = data.toHostString() ?: continue
        if (text.isNotBlank()) return text
    }
    return null
}

@OptIn(ExperimentalForeignApi::class)
private fun platform.Foundation.NSData.toHostString(): String? {
    val length = this.length.toInt()
    if (length <= 0) return null
    val buf = ByteArray(length)
    buf.usePinned { pinned ->
        platform.posix.memcpy(pinned.addressOf(0), this.bytes, length.toULong())
    }
    // sockaddr family is at byte 1 (sa_family_t on Darwin is 1 byte after sa_len).
    return when (buf[1].toInt()) {
        platform.posix.AF_INET -> {
            // sockaddr_in: bytes 4..7 are sin_addr (IPv4), big-endian.
            val a = buf[4].toInt() and 0xFF
            val b = buf[5].toInt() and 0xFF
            val c = buf[6].toInt() and 0xFF
            val d = buf[7].toInt() and 0xFF
            "$a.$b.$c.$d"
        }
        platform.posix.AF_INET6 -> {
            // sockaddr_in6: bytes 8..23 are sin6_addr (IPv6).
            val parts = (0 until 8).map { i ->
                val hi = buf[8 + i * 2].toInt() and 0xFF
                val lo = buf[9 + i * 2].toInt() and 0xFF
                ((hi shl 8) or lo).toString(16)
            }
            parts.joinToString(":")
        }
        else -> null
    }
}

