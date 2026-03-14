package jetzy.managers

import jetzy.models.JetzyElement
import jetzy.p2p.P2pDiscoveryMode
import jetzy.p2p.P2pPlatformCallback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Base interface for all P2P transfer methods
 */
abstract class P2PManager {

    abstract val coroutineScope: CoroutineScope //the viewModelScope should be passed down to this

    companion object {
        lateinit var platformCallback: P2pPlatformCallback
    }

    val transferProgress = MutableStateFlow(0f)
    val transferStatus = MutableStateFlow("")
    val isConnected  = MutableStateFlow(false)

    abstract val discoveryMode: P2pDiscoveryMode

    /**
     * Initialize the manager and prepare for connections
     */
    abstract suspend fun initialize()

    /**
     * Clean up resources and disconnect
     */
    abstract suspend fun cleanup()
    
    /**
     * Send files to the connected peer
     */
    abstract suspend fun sendFiles(files: List<JetzyElement>): Result<Unit>
    
    /**
     * Receive files from the connected peer
     */
    abstract suspend fun receiveFiles(outputDir: JetzyElement): Result<List<JetzyElement>>
    
    /**
     * Disconnect from current peer
     */
    abstract suspend fun disconnect()
}