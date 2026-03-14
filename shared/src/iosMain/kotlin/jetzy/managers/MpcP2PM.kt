package jetzy.managers

import jetzy.models.JetzyElement
import jetzy.p2p.P2pDiscoveryMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

class MpcP2PM: P2PManager() {

    override suspend fun cleanup() {
        TODO("Not yet implemented")
    }

    override suspend fun disconnect() {
        TODO("Not yet implemented")
    }

    override suspend fun receiveFiles(outputDir: JetzyElement): Result<List<JetzyElement>> {
        TODO("Not yet implemented")
    }

    override suspend fun sendFiles(files: List<JetzyElement>): Result<Unit> {
        TODO("Not yet implemented")
    }

    override val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.IO)

    override val discoveryMode: P2pDiscoveryMode = P2pDiscoveryMode.PeerDiscovery

}