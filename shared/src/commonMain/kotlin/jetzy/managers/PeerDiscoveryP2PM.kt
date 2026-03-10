package jetzy.managers

import jetzy.p2p.P2pDiscoveryMode
import jetzy.p2p.P2pPeer
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Manager that supports automatic peer discovery/advertising
 * Both sides can discover each other (Wi-Fi Direct, Bluetooth, Nearby, MultipeerConnectivity)
 */
abstract class PeerDiscoveryP2PM : P2PManager() {

    val availablePeers = MutableStateFlow(listOf<P2pPeer>())

    val isDiscovering = MutableStateFlow(false)
    val isAdvertising = MutableStateFlow(false)

    override val discoveryMode: P2pDiscoveryMode = P2pDiscoveryMode.PeerDiscovery

    /**
     * Start both advertising this device AND discovering peers
     * @param deviceName The name to advertise as
     */
    abstract suspend fun startDiscoveryAndAdvertising(deviceName: String)
    
    /**
     * Stop both discovery and advertising
     */
    abstract suspend fun stopDiscoveryAndAdvertising()
    
    /**
     * Connect to a discovered peer
     */
    abstract suspend fun connectToPeer(peer: P2pPeer): Result<Unit>
}