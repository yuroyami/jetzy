package jetzy.p2p

import jetzy.managers.P2PManager
import jetzy.utils.Platform

interface P2pPlatformCallback {

    fun getSuitableP2pManager(peerPlatform: Platform): P2PManager?

    /**
     * Construct the manager for a specific negotiated [technology] with this device's resolved
     * [role] (HOST stands up the data plane, CLIENT dials it). This is the inverse of the legacy
     * platform-keyed [getSuitableP2pManager]: instead of guessing a transport from the peer's
     * platform, the [TransportNegotiator] picks the exact mutual transport + role, and this turns
     * that decision into a concrete manager. The seam every capability-driven path needs — the
     * Happy-Eyeballs racer, the opportunistic upgrade, and the advert-driven connect.
     *
     * Returns null when this platform can't provide that transport (no manager wired, or the role
     * isn't one this side can take — e.g. a PC asked to HOST a hotspot). Default null so platforms
     * opt in incrementally without breaking the existing flow.
     */
    fun getManagerForTechnology(technology: P2pTechnology, role: Role): P2PManager? = null

    /**
     * Fallback ladder of manager factories for the given peer. Used when the
     * primary transport surfaces no peers and the user chooses to retry with a
     * different one. Ordered best→worst; the first entry is typically equivalent
     * to [getSuitableP2pManager].
     *
     * Default empty — platforms opt in. The discovery screen hides its "Try a
     * different transport" affordance when this is empty.
     */
    fun getFallbackP2pManagers(peerPlatform: Platform): List<() -> P2PManager?> = emptyList()

    /**
     * Start a sticky foreground notification so the OS won't kill the transfer
     * while the user backgrounds the app. Called right before navigating from
     * the main screen to the discovery/QR screen.
     */
    fun startBackgroundService() {}

    /** Stop the foreground notification — called from [P2PManager.cleanup]. */
    fun stopBackgroundService() {}
}