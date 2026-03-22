package jetzy.managers

import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.close
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import io.ktor.utils.io.flush
import jetzy.p2p.P2pPeer
import jetzy.utils.PreferablyIO
import jetzy.utils.loggy
import kotlinx.coroutines.launch
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import platform.Foundation.NSError
import platform.Foundation.NSInputStream
import platform.Foundation.NSOutputStream
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

private const val SERVICE_TYPE = "jetzy-p2p" // must be <= 15 chars, lowercase, no underscores
private const val STREAM_NAME = "jetzy-xfer"

class MpcP2PM : PeerDiscoveryP2PM() {

    private val localPeerID = MCPeerID(displayName = platform.UIKit.UIDevice.currentDevice.name)
    private val session = MCSession(peer = localPeerID, securityIdentity = null, encryptionPreference = MCEncryptionOptional)

    private var advertiser: MCNearbyServiceAdvertiser? = null
    private var browser: MCNearbyServiceBrowser? = null

    private val sessionDelegate   = JetzyMCSessionDelegate()
    private val advertiserDelegate = JetzyMCAdvertiserDelegate()
    private val browserDelegate    = JetzyMCBrowserDelegate()

    // maps MCPeerID → P2pPeer so we can reconnect by peerID later
    private val peerIdMap = mutableMapOf<String, MCPeerID>()

    // MPC stream state for bidirectional bridge
    private var mpcOutputStream: NSOutputStream? = null
    private var mpcInputStream: NSInputStream? = null
    private var connectedPeer: MCPeerID? = null

    init {
        session.delegate = sessionDelegate

        sessionDelegate.onPeerConnected = { mcPeerID ->
            loggy("MPC peer connected: ${mcPeerID.displayName}")
            connectedPeer = mcPeerID

            // Create our output stream to the peer
            mpcOutputStream = session.startStreamWithName(
                streamName = STREAM_NAME,
                toPeer = mcPeerID,
                error = null
            )
            mpcOutputStream?.open()
            loggy("MPC output stream opened to ${mcPeerID.displayName}")

            // Check if we already have the input stream (peer might have connected first)
            tryBridgeStreams()
        }

        sessionDelegate.onPeerDisconnected = { mcPeerID ->
            loggy("MPC peer disconnected: ${mcPeerID.displayName}")
        }

        sessionDelegate.onDataReceived = { data, _ ->
            loggy("MPC raw data received: ${data.length} bytes")
        }

        sessionDelegate.onStreamReceived = { stream, name, peerID ->
            loggy("MPC stream received from ${peerID.displayName}: $name")
            mpcInputStream = stream
            mpcInputStream?.open()

            // Check if we already have the output stream
            tryBridgeStreams()
        }

        browserDelegate.onPeerFound = { mcPeerID, _ ->
            peerIdMap[mcPeerID.displayName] = mcPeerID
            val peers = peerIdMap.values.map { id ->
                P2pPeer(id = id.displayName, name = id.displayName, signalStrength = 3)
            }
            availablePeers.value = peers
            loggy("MPC found peer: ${mcPeerID.displayName}")
        }

        browserDelegate.onPeerLost = { mcPeerID ->
            peerIdMap.remove(mcPeerID.displayName)
            availablePeers.value = availablePeers.value.filter { it.id != mcPeerID.displayName }
            loggy("MPC lost peer: ${mcPeerID.displayName}")
        }

        advertiserDelegate.onInvitationReceived = { mcPeerID, context, invitationHandler ->
            loggy("MPC invitation from ${mcPeerID.displayName} — auto-accepting")
            // auto-accept since both devices are running Jetzy and user already chose to receive
            invitationHandler(true, session)
        }
    }

    /**
     * Once both MPC streams are available, bridge them to Ktor ByteChannels
     * and start the file transfer protocol.
     */
    private fun tryBridgeStreams() {
        val inputStream = mpcInputStream ?: return
        val outputStream = mpcOutputStream ?: return

        loggy("MPC both streams ready — bridging to ByteChannels")

        val readChannel = ByteChannel()
        val writeChannel = ByteChannel()

        // Pump NSInputStream → ByteChannel (read side)
        p2pScope.launch(PreferablyIO) {
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
                loggy("MPC read pump error: ${e.message}")
            } finally {
                readChannel.close()
            }
        }

        // Pump ByteChannel → NSOutputStream (write side)
        p2pScope.launch(PreferablyIO) {
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
                loggy("MPC write pump error: ${e.message}")
            } finally {
                sink.close()
            }
        }

        // Start the transfer using the bridged channels
        startTransferWithChannels(input = readChannel, output = writeChannel)
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

        loggy("MPC advertising and browsing started as '${localPeerID.displayName}'")
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

        browser?.invitePeer(
            peerID = mcPeerID,
            toSession = session,
            withContext = null,
            timeout = 30.0
        )

        // actual connection result comes via sessionDelegate.onPeerConnected
        return Result.success(Unit)
    }

    // ── Cleanup ────────────────────────────────────────────────────────────────

    override suspend fun cleanup() {
        super.cleanup()
        stopDiscoveryAndAdvertising()
        mpcInputStream?.close()
        mpcOutputStream?.close()
        mpcInputStream = null
        mpcOutputStream = null
        connectedPeer = null
        session.disconnect()
    }
}

// ── Delegates ──────────────────────────────────────────────────────────────────

class JetzyMCSessionDelegate : NSObject(), MCSessionDelegateProtocol {
    var onPeerConnected:    ((MCPeerID) -> Unit)? = null
    var onPeerDisconnected: ((MCPeerID) -> Unit)? = null
    var onDataReceived:     ((platform.Foundation.NSData, MCPeerID) -> Unit)? = null
    var onStreamReceived:   ((platform.Foundation.NSInputStream, String, MCPeerID) -> Unit)? = null

    override fun session(session: MCSession, peer: MCPeerID, didChangeState: MCSessionState) {
        when (didChangeState) {
            MCSessionState.MCSessionStateConnected    -> onPeerConnected?.invoke(peer)
            MCSessionState.MCSessionStateNotConnected -> onPeerDisconnected?.invoke(peer)
            MCSessionState.MCSessionStateConnecting   -> loggy("MPC connecting to ${peer.displayName}")
        }
    }

    override fun session(session: MCSession, didReceiveData: platform.Foundation.NSData, fromPeer: MCPeerID) {
        onDataReceived?.invoke(didReceiveData, fromPeer)
    }

    override fun session(session: MCSession, didReceiveStream: platform.Foundation.NSInputStream, withName: String, fromPeer: MCPeerID) {
        onStreamReceived?.invoke(didReceiveStream, withName, fromPeer)
    }

    override fun session(session: MCSession, didStartReceivingResourceWithName: String, fromPeer: MCPeerID, withProgress: platform.Foundation.NSProgress) {
        loggy("MPC receiving resource: $didStartReceivingResourceWithName from ${fromPeer.displayName}")
    }

    override fun session(session: MCSession, didFinishReceivingResourceWithName: String, fromPeer: MCPeerID, atURL: platform.Foundation.NSURL?, withError: NSError?) {
        if (withError != null) loggy("MPC resource error: ${withError.localizedDescription}")
    }
}

class JetzyMCAdvertiserDelegate : NSObject(), MCNearbyServiceAdvertiserDelegateProtocol {
    var onInvitationReceived: ((MCPeerID, platform.Foundation.NSData?, (Boolean, MCSession?) -> Unit) -> Unit)? = null

    override fun advertiser(advertiser: MCNearbyServiceAdvertiser, didReceiveInvitationFromPeer: MCPeerID, withContext: platform.Foundation.NSData?, invitationHandler: (Boolean, MCSession?) -> Unit) {
        onInvitationReceived?.invoke(didReceiveInvitationFromPeer, withContext, invitationHandler)
            ?: invitationHandler(true, null) // auto-accept fallback
    }

    override fun advertiser(advertiser: MCNearbyServiceAdvertiser, didNotStartAdvertisingPeer: NSError) {
        loggy("MPC failed to advertise: ${didNotStartAdvertisingPeer.localizedDescription}")
    }
}

private class JetzyMCBrowserDelegate : NSObject(), MCNearbyServiceBrowserDelegateProtocol {
    var onPeerFound: ((MCPeerID, Map<Any?, *>?) -> Unit)? = null
    var onPeerLost:  ((MCPeerID) -> Unit)? = null

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
        loggy("MPC failed to browse: ${didNotStartBrowsingForPeers.localizedDescription}")
    }
}
