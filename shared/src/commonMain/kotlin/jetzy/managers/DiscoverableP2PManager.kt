package jetzy.managers

import jetzy.p2p.P2pPeer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Manager that supports automatic peer discovery/advertising
 * Both sides can discover each other (WiFi Direct, Bluetooth, Nearby, MultipeerConnectivity)
 */
abstract class DiscoverableP2PManager : P2PManager() {
    val availablePeers: StateFlow<List<P2pPeer>>
        field: MutableStateFlow<List<P2pPeer>> = MutableStateFlow(listOf())

    val isDiscovering: StateFlow<Boolean>
        field = MutableStateFlow(false)

    val isAdvertising: StateFlow<Boolean>
        field = MutableStateFlow(false)
    
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