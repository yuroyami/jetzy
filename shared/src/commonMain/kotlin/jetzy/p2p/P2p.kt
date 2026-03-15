package jetzy.p2p


open class P2pPeer(
    val id: String,
    val name: String,
    val signalStrength: Int
)

@DslMarker annotation class P2pIoApi