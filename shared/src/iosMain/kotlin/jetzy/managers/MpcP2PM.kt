package jetzy.managers

import jetzy.p2p.P2pPeer
import jetzy.utils.loggy
import platform.Foundation.NSError
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

    init {
        session.delegate = sessionDelegate

        sessionDelegate.onPeerConnected = { mcPeerID ->
            loggy("MPC peer connected: ${mcPeerID.displayName}")
            //TODO ESTBALISH KTOR CONNECTION HERE
        }

        sessionDelegate.onPeerDisconnected = { mcPeerID ->
            loggy("MPC peer disconnected: ${mcPeerID.displayName}")
        }

        sessionDelegate.onDataReceived = { data, _ ->
            // MPC can also stream raw bytes — we use sendFiles/receiveFiles
            // via the stream API instead (see connectToPeer)
            loggy("MPC raw data received: ${data.length} bytes")
        }

        sessionDelegate.onStreamReceived = { stream, name, peerID ->
            loggy("MPC stream received from ${peerID.displayName}: $name")
            // TODO wire up ktor channel from NSInputStream
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
        stopDiscoveryAndAdvertising()
        session.disconnect()
        connection = null
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