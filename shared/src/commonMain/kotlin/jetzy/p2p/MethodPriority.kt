package jetzy.p2p

enum class MethodPriority {
    FALLBACK ,       // Use only if better options unavailable
    ACCEPTABLE,     // Works well, but not optimal
    RECOMMENDED    // Best option for this platform combo
}