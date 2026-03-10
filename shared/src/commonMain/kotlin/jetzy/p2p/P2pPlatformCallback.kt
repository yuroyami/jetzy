package jetzy.p2p

import jetzy.managers.P2PManager
import jetzy.utils.Platform

interface P2pPlatformCallback {

    fun getSuitableP2pManager(peerPlatform: Platform): P2PManager?

}