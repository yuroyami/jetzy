package jetzy.managers

/**
 * Manager that requires manual connection info (Local Network, WebRTC)
 */
interface ManualConnectionP2PManager : P2PManager {
    /**
     * Generate connection info (IP, port, QR code data, etc.)
     */
    suspend fun generateConnectionInfo(): ConnectionInfo

    /**
     * Connect using connection info from peer
     */
    suspend fun connectWithInfo(info: ConnectionInfo): Result<Unit>
}