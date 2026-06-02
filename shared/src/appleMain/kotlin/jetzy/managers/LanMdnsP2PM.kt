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
    private var selectorManager: SelectorManager? = null

    private fun selector(): SelectorManager =
        selectorManager ?: SelectorManager(PreferablyIO).also { selectorManager = it }

    // NSNetService.delegate is a *weak* reference, so we must hold the resolving delegates
    // ourselves for the duration of the resolve — otherwise they deallocate and the
    // netServiceDidResolveAddress callback never fires (peers silently never resolve).
    private val resolvingDelegates = mutableListOf<ResolvingDelegate>()

    private val advertisingDelegate = AdvertisingDelegate()
    private val browsingDelegate = BrowsingDelegate()

    // Discovered services awaiting resolution → resolved peers.
    private val pendingResolve = mutableListOf<NSNetService>()
    // We cache the parsed host string alongside the service at resolve time so the
    // NSData→ByteArray address parse doesn't have to run a second time on connect.
    private class ResolvedPeer(val service: NSNetService, val host: String)
    private val resolvedPeers = mutableMapOf<String, ResolvedPeer>()

    private var connectionReady: CompletableDeferred<Boolean>? = null

    init {
        browsingDelegate.onServiceFound = { service ->
            // Filter own advertisement by name.
            if (service.name != advertiser?.name) {
                lateinit var resolveDelegate: ResolvingDelegate
                resolveDelegate = ResolvingDelegate { resolved ->
                    val addr = resolved.firstResolvableAddress()
                    if (addr != null) {
                        resolvedPeers[resolved.name] = ResolvedPeer(resolved, addr)
                        availablePeers.value = resolvedPeers.values.map {
                            P2pPeer(id = it.service.name, name = it.service.name, signalStrength = 3)
                        }
                        diag("mDNS resolved ${resolved.name} @ $addr:${resolved.port}")
                    }
                    // Resolution is done; release the held delegate and drop the
                    // service from the awaiting-resolution list so neither grows
                    // unbounded across peer churn. (The resolved NSNetService stays
                    // in resolvedPeers — it's still needed to dial the peer.)
                    resolvingDelegates.remove(resolveDelegate)
                    pendingResolve.remove(service)
                }
                resolvingDelegates += resolveDelegate
                service.delegate = resolveDelegate
                pendingResolve += service
                service.resolveWithTimeout(5.0)
            }
        }
        browsingDelegate.onServiceLost = { service ->
            resolvedPeers.remove(service.name)
            availablePeers.value = resolvedPeers.values.map {
                P2pPeer(id = it.service.name, name = it.service.name, signalStrength = 3)
            }
            diag("mDNS lost ${service.name}")
        }
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    override suspend fun startDiscoveryAndAdvertising(deviceName: String) {
        isDiscovering.value = true
        isAdvertising.value = true

        // Bind TCP server before advertising so the port can be announced.
        val bound = aSocket(selector()).tcp().bind("0.0.0.0", 0)
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
        val resolved = resolvedPeers[peer.id]
            ?: return Result.failure(Exception("peer not resolved: ${peer.id}"))
        val service = resolved.service
        // Host string was parsed once at resolve time; reuse it instead of
        // re-running the NSData→ByteArray address parse here.
        val host = resolved.host

        val ready = CompletableDeferred<Boolean>()
        connectionReady = ready

        p2pScope.launch(PreferablyIO) {
            try {
                diag("dialing $host:${service.port}…")
                val ktor = aSocket(selector())
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
        runCatching { selectorManager?.close() }
        selectorManager = null
        connectionReady?.complete(false)
        connectionReady = null
        resolvedPeers.clear()
        resolvingDelegates.clear()
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
    var firstV6: String? = null
    for (anyAddr in addrs) {
        val data = anyAddr as? platform.Foundation.NSData ?: continue
        val text = data.toHostString()?.takeIf { it.isNotBlank() } ?: continue
        // Prefer IPv4 — a bare IPv6 link-local address needs a %scope suffix to be dialable.
        if (':' in text) { if (firstV6 == null) firstV6 = text } else return text
    }
    return firstV6
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
            // Build the colon-separated hex form in one pass — avoids the
            // intermediate 8-element list and its short-lived String parts.
            val sb = StringBuilder()
            for (i in 0 until 8) {
                if (i > 0) sb.append(':')
                val hi = buf[8 + i * 2].toInt() and 0xFF
                val lo = buf[9 + i * 2].toInt() and 0xFF
                sb.append(((hi shl 8) or lo).toString(16))
            }
            sb.toString()
        }
        else -> null
    }
}

