package jetzy.p2p

import jetzy.managers.P2PManager
import jetzy.utils.Platform

interface P2pPlatformCallback {

    /**
     * The bootstrap transport to try first when the peer's platform is **not known up front** —
     * the gate-free flow that replaced the old "pick who you're sending to" screen. mDNS on every
     * host: it auto-discovers any Jetzy peer on the shared LAN regardless of OS, so no platform
     * pick is needed and a wrong guess can no longer route us to an incompatible transport. The
     * discovered peer carries its own identity; from there the [TransportNegotiator] can climb to
     * a better link via [getManagerForTechnology]. Returns null only if this host can't do mDNS.
     */
    fun getDefaultP2pManager(): P2PManager?

    /**
     * Construct the manager for a specific negotiated [technology] with this device's resolved
     * [role] (HOST stands up the data plane, CLIENT dials it). Instead of guessing a transport
     * from the peer's platform, the [TransportNegotiator] picks the exact mutual transport + role
     * and this turns that decision into a concrete manager. The seam every capability-driven path
     * needs — the Happy-Eyeballs racer, the opportunistic upgrade, and the advert-driven connect.
     *
     * Returns null when this platform can't provide that transport (no manager wired, or the role
     * isn't one this side can take — e.g. a PC asked to HOST a hotspot). Default null so platforms
     * opt in incrementally without breaking the existing flow.
     */
    fun getManagerForTechnology(technology: P2pTechnology, role: Role): P2PManager? = null

    /**
     * The full fallback ladder for **this host**, independent of the peer's platform — walked by
     * the "Try a different transport" affordance when the default (mDNS) surfaces no peers. Ordered
     * best→worst (mDNS first), and collectively covering every path the old per-peer-platform
     * ladders used to, so collapsing the platform picker costs no reach: the host simply tries each
     * link it can stand up (Wi-Fi Aware / Direct, hotspot, MPC, BT…) until one connects.
     *
     * Default empty — platforms opt in. The discovery screen hides its "Try a different transport"
     * affordance when this is empty.
     */
    fun getDefaultFallbackManagers(): List<() -> P2PManager?> = emptyList()

    /**
     * Start a sticky foreground notification so the OS won't kill the transfer
     * while the user backgrounds the app. Called right before navigating from
     * the main screen to the discovery/QR screen.
     */
    fun startBackgroundService() {}

    /** Stop the foreground notification — called from [P2PManager.cleanup]. */
    fun stopBackgroundService() {}
}