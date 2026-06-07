package jetzy.p2p

/** One scheduled connection attempt: which transport+role to try, and how long to wait before starting it. */
data class ConnectAttempt(val match: TransportMatch, val startDelayMs: Long)

/**
 * Turns the negotiator's ranked transport list into a **Happy-Eyeballs** connection schedule
 * (RFC 8305, but for radios). Instead of trying one transport, waiting for a 6-second timeout, and
 * making the user tap "try a different transport," the executor launches the top attempts in a
 * staggered race and keeps whichever establishes a byte channel first — so connecting just
 * *succeeds*, fast, and a mis-ranked transport costs a stagger step, not a dead end.
 *
 * This object owns the PURE schedule (which transport starts when). The executor that actually runs
 * the race — spawning [P2pPlatformCallback.getManagerForTechnology] per attempt, cancelling the
 * losers, handing the winner's channel to the protocol — is the device-validated half and lands once
 * managers expose a uniform connect primitive.
 */
object TransportCoordinator {

    /**
     * Build the staggered schedule from [matches] (already best-first by quality, from
     * [TransportNegotiator.negotiate]).
     *
     * Non-disruptive transports race first, each offset by [stepMs] in quality order, so the best
     * link gets a head start and a fast winner cancels the rest. Disruptive transports
     * ([P2pTechnology.disruptsLocalConnectivity] — hotspot, Wi-Fi Direct) are appended *after* all
     * the safe ones, so they only fire if no clean link connected and never overlap each other or a
     * safe attempt mid-bringup. Result preserves quality order within each group.
     */
    fun schedule(matches: List<TransportMatch>, stepMs: Long = 250): List<ConnectAttempt> {
        val (safe, disruptive) = matches.partition { !it.technology.disruptsLocalConnectivity }
        return (safe + disruptive).mapIndexed { i, m -> ConnectAttempt(m, i * stepMs) }
    }
}
