package jetzy.managers

import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import jetzy.p2p.P2pPeer
import jetzy.utils.PreferablyIO
import jetzy.utils.getDeviceName
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import platform.Foundation.NSError
import platform.Foundation.NSInputStream
import platform.Foundation.NSOutputStream
import platform.Foundation.NSStreamStatus
import platform.MultipeerConnectivity.MCEncryptionOptional
import platform.MultipeerConnectivity.MCNearbyServiceAdvertiser
import platform.MultipeerConnectivity.MCNearbyServiceAdvertiserDelegateProtocol
import platform.MultipeerConnectivity.MCNearbyServiceBrowser
import platform.MultipeerConnectivity.MCNearbyServiceBrowserDelegateProtocol
import platform.MultipeerConnectivity.MCPeerID
import platform.MultipeerConnectivity.MCSession
import platform.MultipeerConnectivity.MCSessionDelegateProtocol
import platform.MultipeerConnectivity.MCSessionState
import platform.darwin.NSObject
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private const val SERVICE_TYPE = "jetzy-p2p" // must be <= 15 chars, lowercase, no underscores
private const val STREAM_NAME = "jetzy-xfer"
private const val INVITE_TIMEOUT_SEC = 30.0
private val STREAM_READY_TIMEOUT = 15.seconds

class MpcP2PM : PeerDiscoveryP2PM() {

    private val localPeerID = MCPeerID(displayName = getDeviceName())
    private val session = MCSession(peer = localPeerID, securityIdentity = null, encryptionPreference = MCEncryptionOptional)

    private var advertiser: MCNearbyServiceAdvertiser? = null
    private var browser: MCNearbyServiceBrowser? = null

    private val sessionDelegate   = JetzyMCSessionDelegate()
    private val advertiserDelegate = JetzyMCAdvertiserDelegate()
    private val browserDelegate    = JetzyMCBrowserDelegate()

    // maps MCPeerID display name → actual MCPeerID so we can reconnect by peerID later
    private val peerIdMap = mutableMapOf<String, MCPeerID>()

    // MPC stream state for bidirectional bridge
    private var mpcOutputStream: NSOutputStream? = null
    private var mpcInputStream: NSInputStream? = null
    private var connectedPeer: MCPeerID? = null
    /** Resolved once both streams are open and bridged to Ktor channels. */
    private var connectionReady: CompletableDeferred<Boolean>? = null
    private var bridgeJobs: MutableList<Job> = mutableListOf()

    init {
        session.delegate = sessionDelegate

        sessionDelegate.onPeerStateChanged = { mcPeerID, state ->
            when (state) {
                MCSessionState.MCSessionStateConnected -> {
                    diag("MPC peer connected: ${mcPeerID.displayName}")
                    connectedPeer = mcPeerID
                    openOutputStreamTo(mcPeerID)
                    tryBridgeStreams()
                }
                MCSessionState.MCSessionStateConnecting -> diag("MPC connecting to ${mcPeerID.displayName}")
                MCSessionState.MCSessionStateNotConnected -> {
                    diag("MPC peer disconnected: ${mcPeerID.displayName}")
                    if (connectedPeer == mcPeerID) {
                        connectionReady?.complete(false)
                        teardownStreams()
                        connectedPeer = null
                    }
                }
            }
        }

        sessionDelegate.onStreamReceived = { stream, name, peerID ->
            diag("MPC stream '$name' received from ${peerID.displayName}")
            mpcInputStream = stream
            val opened = openStream(stream)
            if (!opened) {
                diag("MPC failed to open input stream from ${peerID.displayName}")
                connectionReady?.complete(false)
            } else {
                tryBridgeStreams()
            }
        }

        browserDelegate.onPeerFound = { mcPeerID, _ ->
            peerIdMap[mcPeerID.displayName] = mcPeerID
            val peers = peerIdMap.values.map { id ->
                P2pPeer(id = id.displayName, name = id.displayName, signalStrength = 3)
            }
            availablePeers.value = peers
            diag("MPC found peer: ${mcPeerID.displayName}")
        }

        browserDelegate.onPeerLost = { mcPeerID ->
            peerIdMap.remove(mcPeerID.displayName)
            availablePeers.value = availablePeers.value.filter { it.id != mcPeerID.displayName }
            diag("MPC lost peer: ${mcPeerID.displayName}")
        }

        browserDelegate.onBrowseFailed = { err ->
            diag("MPC browse failed: ${err.localizedDescription}")
            viewmodel.snacky("Discovery failed: ${err.localizedDescription}")
        }

        advertiserDelegate.onInvitationReceived = { mcPeerID, _, invitationHandler ->
            diag("MPC invitation from ${mcPeerID.displayName} — auto-accepting")
            // auto-accept: both devices are running Jetzy and user already chose to receive
            invitationHandler(true, session)
        }

        advertiserDelegate.onAdvertiseFailed = { err ->
            diag("MPC advertise failed: ${err.localizedDescription}")
            viewmodel.snacky("Can't advertise to peers: ${err.localizedDescription}")
        }
    }

    private fun openOutputStreamTo(mcPeerID: MCPeerID) {
        runCatching {
            val stream = session.startStreamWithName(
                streamName = STREAM_NAME,
                toPeer = mcPeerID,
                error = null
            )
            if (stream == null) {
                diag("MPC startStreamWithName returned null for ${mcPeerID.displayName}")
                connectionReady?.complete(false)
                return
            }
            mpcOutputStream = stream
            if (!openStream(stream)) {
                diag("MPC failed to open output stream to ${mcPeerID.displayName}")
                connectionReady?.complete(false)
                return
            }
            diag("MPC output stream opened to ${mcPeerID.displayName}")
        }.onFailure {
            diag("MPC output stream setup threw: ${it.message}")
            connectionReady?.complete(false)
        }
    }

    private fun openStream(stream: platform.Foundation.NSStream): Boolean {
        stream.open()
        // give the runtime a microsecond to progress state
        return stream.streamStatus != NSStreamStatus.MAX_VALUE // sanity check; real validation happens on first read/write
    }

    private var alreadyBridged = false
    @OptIn(ExperimentalForeignApi::class)
    private fun tryBridgeStreams() {
        val inputStream = mpcInputStream ?: return
        val outputStream = mpcOutputStream ?: return
        if (alreadyBridged) return
        alreadyBridged = true

        diag("MPC both streams ready — bridging to ByteChannels")

        val readChannel = ByteChannel()
        val writeChannel = ByteChannel()

        // Pump NSInputStream → ByteChannel (read side)
        bridgeJobs += p2pScope.launch(PreferablyIO) {
            val source = inputStream.asSource().buffered()
            val buf = ByteArray(bufferSize)
            try {
                while (true) {
                    val n = source.readAtMostTo(buf)
                    if (n == -1) break
                    readChannel.writeFully(buf, 0, n)
                    readChannel.flush()
                }
            } catch (e: Exception) {
                diag("MPC read pump error: ${e.message}")
            } finally {
                readChannel.close()
            }
        }

        // Pump ByteChannel → NSOutputStream (write side)
        bridgeJobs += p2pScope.launch(PreferablyIO) {
            val sink = outputStream.asSink().buffered()
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
                diag("MPC write pump error: ${e.message}")
            } finally {
                sink.close()
            }
        }

        // Mark connection ready — connectToPeer's awaiter will unblock now
        connectionReady?.complete(true)
        // Start the transfer using the bridged channels
        startTransferWithChannels(input = readChannel, output = writeChannel)
    }

    private fun teardownStreams() {
        bridgeJobs.forEach { runCatching { it.cancel() } }
        bridgeJobs.clear()
        runCatching { mpcInputStream?.close() }
        runCatching { mpcOutputStream?.close() }
        mpcInputStream = null
        mpcOutputStream = null
        alreadyBridged = false
    }

    // ── Discovery ──────────────────────────────────────────────────────────────

    override suspend fun startDiscoveryAndAdvertising(deviceName: String) {
        isDiscovering.value = true
        isAdvertising.value = true

        advertiser = MCNearbyServiceAdvertiser(
            peer = localPeerID,
            discoveryInfo = null,
            serviceType = SERVICE_TYPE
        ).also {
            it.delegate = advertiserDelegate
            it.startAdvertisingPeer()
        }

        browser = MCNearbyServiceBrowser(
            peer = localPeerID,
            serviceType = SERVICE_TYPE
        ).also {
            it.delegate = browserDelegate
            it.startBrowsingForPeers()
        }

        diag("MPC advertising & browsing started as '${localPeerID.displayName}'")
    }

    override suspend fun stopDiscoveryAndAdvertising() {
        advertiser?.stopAdvertisingPeer()
        browser?.stopBrowsingForPeers()
        advertiser = null
        browser = null
        isDiscovering.value = false
        isAdvertising.value = false
    }

    // ── Connection ─────────────────────────────────────────────────────────────

    override suspend fun connectToPeer(peer: P2pPeer): Result<Unit> {
        val mcPeerID = peerIdMap[peer.id]
            ?: return Result.failure(Exception("MCPeerID not found for ${peer.id}"))

        // Arm the gate BEFORE sending the invitation so late callbacks still fire into it.
        val ready = CompletableDeferred<Boolean>()
        connectionReady = ready

        diag("inviting ${mcPeerID.displayName}…")
        browser?.invitePeer(
            peerID = mcPeerID,
            toSession = session,
            withContext = null,
            timeout = INVITE_TIMEOUT_SEC
        )

        // Wait for either streams to be bridged OR a disconnect; whichever comes first.
        val arrived = withTimeoutOrNull(STREAM_READY_TIMEOUT) { ready.await() } ?: false
        if (!arrived) {
            diag("MPC connect timed out or failed for ${mcPeerID.displayName}")
            viewmodel.snacky("Couldn't connect to ${peer.name}. The peer may have declined or gone offline.")
            return Result.failure(Exception("MPC connect timed out"))
        }
        return Result.success(Unit)
    }

    // ── Cleanup ────────────────────────────────────────────────────────────────

    override suspend fun cleanup() {
        super.cleanup()
        stopDiscoveryAndAdvertising()
        connectionReady?.complete(false)
        connectionReady = null
        teardownStreams()
        connectedPeer = null
        runCatching { session.disconnect() }
    }
}

// ── Delegates ──────────────────────────────────────────────────────────────────

class JetzyMCSessionDelegate : NSObject(), MCSessionDelegateProtocol {
    var onPeerStateChanged:  ((MCPeerID, MCSessionState) -> Unit)? = null
    var onDataReceived:     ((platform.Foundation.NSData, MCPeerID) -> Unit)? = null
    var onStreamReceived:   ((platform.Foundation.NSInputStream, String, MCPeerID) -> Unit)? = null

    override fun session(session: MCSession, peer: MCPeerID, didChangeState: MCSessionState) {
        onPeerStateChanged?.invoke(peer, didChangeState)
    }

    override fun session(session: MCSession, didReceiveData: platform.Foundation.NSData, fromPeer: MCPeerID) {
        onDataReceived?.invoke(didReceiveData, fromPeer)
    }

    override fun session(session: MCSession, didReceiveStream: platform.Foundation.NSInputStream, withName: String, fromPeer: MCPeerID) {
        onStreamReceived?.invoke(didReceiveStream, withName, fromPeer)
    }

    override fun session(session: MCSession, didStartReceivingResourceWithName: String, fromPeer: MCPeerID, withProgress: platform.Foundation.NSProgress) {
        // unused — we use streams rather than MPC's resource/file APIs
    }

    override fun session(session: MCSession, didFinishReceivingResourceWithName: String, fromPeer: MCPeerID, atURL: platform.Foundation.NSURL?, withError: NSError?) {
        // unused
    }
}

class JetzyMCAdvertiserDelegate : NSObject(), MCNearbyServiceAdvertiserDelegateProtocol {
    var onInvitationReceived: ((MCPeerID, platform.Foundation.NSData?, (Boolean, MCSession?) -> Unit) -> Unit)? = null
    var onAdvertiseFailed: ((NSError) -> Unit)? = null

    override fun advertiser(advertiser: MCNearbyServiceAdvertiser, didReceiveInvitationFromPeer: MCPeerID, withContext: platform.Foundation.NSData?, invitationHandler: (Boolean, MCSession?) -> Unit) {
        onInvitationReceived?.invoke(didReceiveInvitationFromPeer, withContext, invitationHandler)
            ?: invitationHandler(true, null) // auto-accept fallback
    }

    override fun advertiser(advertiser: MCNearbyServiceAdvertiser, didNotStartAdvertisingPeer: NSError) {
        onAdvertiseFailed?.invoke(didNotStartAdvertisingPeer)
    }
}

private class JetzyMCBrowserDelegate : NSObject(), MCNearbyServiceBrowserDelegateProtocol {
    var onPeerFound: ((MCPeerID, Map<Any?, *>?) -> Unit)? = null
    var onPeerLost:  ((MCPeerID) -> Unit)? = null
    var onBrowseFailed: ((NSError) -> Unit)? = null

    override fun browser(
        browser: MCNearbyServiceBrowser,
        foundPeer: MCPeerID,
        withDiscoveryInfo: Map<Any?, *>?
    ) {
        onPeerFound?.invoke(foundPeer, withDiscoveryInfo)
    }

    override fun browser(browser: MCNearbyServiceBrowser, lostPeer: MCPeerID) {
        onPeerLost?.invoke(lostPeer)
    }

    override fun browser(browser: MCNearbyServiceBrowser, didNotStartBrowsingForPeers: NSError) {
        onBrowseFailed?.invoke(didNotStartBrowsingForPeers)
    }
}
