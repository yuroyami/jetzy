package jetzy.managers

import jetzy.p2p.P2pMethod

expect object P2PManagerFactory {
    fun createManager(method: P2pMethod): P2PManager?
}