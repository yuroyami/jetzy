package jetzy.managers

import jetzy.models.JetzyElement
import jetzy.p2p.P2pDiscoveryMode

class MpcP2PM: P2PManager() {

    override val discoveryMode: P2pDiscoveryMode = P2pDiscoveryMode.PeerDiscovery

    override suspend fun cleanup() {
        TODO("Not yet implemented")
    }
    override suspend fun sendFiles(files: List<JetzyElement>) {
        TODO("Not yet implemented")
    }

    override suspend fun receiveFiles() {

    }

}