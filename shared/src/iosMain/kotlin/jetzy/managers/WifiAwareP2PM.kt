package jetzy.managers

import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import jetzy.p2p.P2pPeer
import jetzy.utils.PreferablyIO
import jetzy.utils.getDeviceName
import jetzy.utils.isWifiAwareSupported
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import platform.Foundation.NSInputStream
import platform.Foundation.NSOutputStream
import platform.Foundation.NSStreamStatus
import kotlin.time.Duration.Companion.seconds

/**
 * iOS Wi-Fi Aware P2P manager. iOS 26+ only (gated via [isWifiAwareSupported]).
 *
 * The actual framework code (`WAPublisher`/`WASubscriber`/`WAPairedDevice` and
 * Network.framework data-paths) lives in `iosApp/iosApp/WifiAwareBridge.swift`
 * because Apple's `WiFiAware` framework is Swift-only and Kotlin/Native's
 * cinterop can't generate ObjC bindings for it. The Swift bridge implements
 * [WifiAwareBridge]; we receive `NSInputStream`/`NSOutputStream` once the
 * peer's data session is up and bridge them to Ktor `ByteChannel`s exactly
 * the way [MpcP2PM] does for MultipeerConnectivity.
 *
 * Construct via [WifiAwareP2PM.Companion.create] which performs the runtime
 * availability check and returns null on iOS < 26 or unsupported chipsets.
 */
class WifiAwareP2PM private constructor(
    private val bridge: WifiAwareBridge,
) : PeerDiscoveryP2PM() {

    private val foundPeers = mutableMapOf<String, P2pPeer>()

    private var connectionReady: CompletableDeferred<Boolean>? = null
    private val bridgeJobs = mutableListOf<Job>()
    private var alreadyBridged = false

    private val listener = object : WifiAwareBridgeListener {
        override fun onPeerFound(peerId: String, peerName: String) {
            diag("Wi-Fi Aware peer found: $peerName")
            foundPeers[peerId] = P2pPeer(id = peerId, name = peerName, signalStrength = 3)
            availablePeers.value = foundPeers.values.toList()
        }

        override fun onPeerLost(peerId: String) {
            val gone = foundPeers.remove(peerId) ?: return
            diag("Wi-Fi Aware peer lost: ${gone.name}")
            availablePeers.value = foundPeers.values.toList()
        }

        @OptIn(ExperimentalForeignApi::class)
        override fun onConnected(peerId: String, input: NSInputStream, output: NSOutputStream) {
            diag("Wi-Fi Aware connected to $peerId")
            isHandshaking.value = true
            bridgeStreamsToChannels(input, output)
        }

        override fun onError(message: String) {
            diag("Wi-Fi Aware error: $message")
            viewmodel.snacky("Wi-Fi Aware: $message")
            connectionReady?.complete(false)
        }
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    override suspend fun startDiscoveryAndAdvertising(deviceName: String) {
        if (!bridge.isAvailable()) {
            diag("bridge reports Wi-Fi Aware unavailable on this device")
            viewmodel.snacky("This device doesn't support Wi-Fi Aware.")
            return
        }
        isDiscovering.value = true
        isAdvertising.value = true
        bridge.startDiscovery(deviceName, listener)
        diag("Wi-Fi Aware discovery started as '$deviceName'")
    }

    override suspend fun stopDiscoveryAndAdvertising() {
        bridge.stopDiscovery()
        isDiscovering.value = false
        isAdvertising.value = false
    }

    // ── Connection ─────────────────────────────────────────────────────────────

    override suspend fun connectToPeer(peer: P2pPeer): Result<Unit> {
        val ready = CompletableDeferred<Boolean>()
        connectionReady = ready

        diag("Wi-Fi Aware: requesting session with ${peer.name}")
        bridge.connectToPeer(peer.id)

        val arrived = withTimeoutOrNull(CONNECT_TIMEOUT) { ready.await() } ?: false
        if (!arrived) {
            diag("Wi-Fi Aware connect timed out for ${peer.name}")
            viewmodel.snacky("Couldn't connect to ${peer.name} over Wi-Fi Aware.")
            return Result.failure(Exception("Wi-Fi Aware connect timed out"))
        }
        return Result.success(Unit)
    }

    // ── Stream bridging ────────────────────────────────────────────────────────

    @OptIn(ExperimentalForeignApi::class)
    private fun bridgeStreamsToChannels(input: NSInputStream, output: NSOutputStream) {
        if (alreadyBridged) {
            diag("ignoring duplicate onConnected — already bridged")
            return
        }
        alreadyBridged = true

        input.open()
        output.open()
        if (input.streamStatus == NSStreamStatus.MAX_VALUE || output.streamStatus == NSStreamStatus.MAX_VALUE) {
            diag("Wi-Fi Aware streams refused to open")
            connectionReady?.complete(false)
            return
        }

        val readChannel = ByteChannel()
        val writeChannel = ByteChannel()

        bridgeJobs += p2pScope.launch(PreferablyIO) {
            val source = input.asSource().buffered()
            val buf = ByteArray(bufferSize)
            try {
                while (true) {
                    val n = source.readAtMostTo(buf)
                    if (n == -1) break
                    readChannel.writeFully(buf, 0, n)
                    readChannel.flush()
                }
            } catch (e: Exception) {
                diag("Wi-Fi Aware read pump error: ${e.message}")
            } finally {
                readChannel.close()
            }
        }

        bridgeJobs += p2pScope.launch(PreferablyIO) {
            val sink = output.asSink().buffered()
            val buf = ByteArray(bufferSize)
            try {
                while (!writeChannel.isClosedForRead) {
                    val n = writeChannel.readAvailable(buf)
                    if (n <= 0) {
                        if (writeChannel.isClosedForRead) break
                        continue
                    }
                    sink.write(buf, 0, n)
                    sink.flush()
                }
            } catch (e: Exception) {
                diag("Wi-Fi Aware write pump error: ${e.message}")
            } finally {
                sink.close()
            }
        }

        connectionReady?.complete(true)
        startTransferWithChannels(input = readChannel, output = writeChannel)
    }

    // ── Cleanup ────────────────────────────────────────────────────────────────

    override suspend fun cleanup() {
        super.cleanup()
        bridge.cleanup()
        bridgeJobs.forEach { runCatching { it.cancel() } }
        bridgeJobs.clear()
        foundPeers.clear()
        availablePeers.value = emptyList()
        connectionReady?.complete(false)
        connectionReady = null
        alreadyBridged = false
        isDiscovering.value = false
        isAdvertising.value = false
    }

    companion object {
        private val CONNECT_TIMEOUT = 60.seconds  // pairing dialog may take time on first use

        /**
         * Returns a manager if both the OS (iOS 26+) and the supplied bridge agree
         * Wi-Fi Aware is usable here; otherwise null so the caller falls through
         * to the next-best transport (HotspotLAN).
         */
        fun create(bridge: WifiAwareBridge): WifiAwareP2PM? {
            if (!isWifiAwareSupported()) return null
            if (!bridge.isAvailable()) return null
            return WifiAwareP2PM(bridge)
        }
    }
}
