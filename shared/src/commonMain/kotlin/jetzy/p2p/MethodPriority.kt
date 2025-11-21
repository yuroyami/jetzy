package jetzy.p2p

enum class MethodPriority {
    RECOMMENDED,    // Best option for this platform combo
    ACCEPTABLE,     // Works well, but not optimal
    FALLBACK        // Use only if better options unavailable
}