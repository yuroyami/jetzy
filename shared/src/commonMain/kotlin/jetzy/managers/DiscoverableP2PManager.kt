package jetzy.managers

import jetzy.p2p.P2pPeer
import kotlinx.coroutines.flow.StateFlow

/**
 * Manager that supports automatic peer discovery/advertising
 * Both sides can discover each other (WiFi Direct, Bluetooth, Nearby, MultipeerConnectivity)
 */
interface DiscoverableP2PManager : P2PManager {
    val availablePeers: StateFlow<List<P2pPeer>>
    val isDiscovering: StateFlow<Boolean>
    val isAdvertising: StateFlow<Boolean>
    
    /**
     * Start both advertising this device AND discovering peers
     * @param deviceName The name to advertise as
     */
    suspend fun startDiscoveryAndAdvertising(deviceName: String)
    
    /**
     * Stop both discovery and advertising
     */
    suspend fun stopDiscoveryAndAdvertising()
    
    /**
     * Connect to a discovered peer
     */
    suspend fun connectToPeer(peer: P2pPeer): Result<Unit>
}