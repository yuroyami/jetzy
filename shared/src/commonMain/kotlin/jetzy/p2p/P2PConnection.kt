package jetzy.p2p

// P2P Connection interface
interface P2PConnection {
    suspend fun send(data: ByteArray)
    suspend fun receive(): ByteArray
    suspend fun close()
}