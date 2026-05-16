package jetzy.p2p

import jetzy.managers.P2PManager
import jetzy.utils.Platform

interface P2pPlatformCallback {

    fun getSuitableP2pManager(peerPlatform: Platform): P2PManager?

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