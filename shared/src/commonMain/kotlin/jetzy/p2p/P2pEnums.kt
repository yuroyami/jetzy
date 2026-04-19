package jetzy.p2p

enum class P2pOperation { SEND, RECEIVE }

enum class P2pTechPriority {
    FALLBACK ,       // Use only if better options unavailable
    ACCEPTABLE,     // Works well, but not optimal
    RECOMMENDED    // Best option for this platform combo
}

enum class P2pDiscoveryMode {
    QRCode,
    PeerDiscovery
}