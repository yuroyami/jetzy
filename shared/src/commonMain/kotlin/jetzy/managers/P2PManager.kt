package jetzy.managers

import jetzy.models.JetzyElement
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Base interface for all P2P transfer methods
 */
abstract class P2PManager {
    val transferProgress: StateFlow<Float>
        field = MutableStateFlow(0f)

    final val transferStatus: StateFlow<String>
         field: MutableStateFlow<String> = MutableStateFlow("")

    val isConnected: StateFlow<Boolean>
        field = MutableStateFlow(false)
    
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