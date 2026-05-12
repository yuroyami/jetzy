package jetzy.p2p

import jetzy.managers.P2PManager
import jetzy.utils.Platform

interface P2pPlatformCallback {

    fun getSuitableP2pManager(peerPlatform: Platform): P2PManager?

    /**
     * Start a sticky foreground notification so the OS won't kill the transfer
     * while the user backgrounds the app. Called right before navigating from
     * the main screen to the discovery/QR screen.
     */
    fun startBackgroundService() {}

    /** Stop the foreground notification — called from [P2PManager.cleanup]. */
    fun stopBackgroundService() {}
}