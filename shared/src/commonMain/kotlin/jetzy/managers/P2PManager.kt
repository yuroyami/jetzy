package jetzy.managers

import jetzy.models.JetzyElement
import kotlinx.coroutines.flow.StateFlow

/**
 * Base interface for all P2P transfer methods
 */
interface P2PManager {
    val transferProgress: StateFlow<Float>
    val transferStatus: StateFlow<String>
    val isConnected: StateFlow<Boolean>
    
    /**
     * Initialize the manager and prepare for connections
     */
    suspend fun initialize()
    
    /**
     * Clean up resources and disconnect
     */
    suspend fun cleanup()
    
    /**
     * Send files to the connected peer
     */
    suspend fun sendFiles(files: List<JetzyElement>): Result<Unit>
    
    /**
     * Receive files from the connected peer
     */
    suspend fun receiveFiles(outputDir: JetzyElement): Result<List<JetzyElement>>
    
    /**
     * Disconnect from current peer
     */
    suspend fun disconnect()
}