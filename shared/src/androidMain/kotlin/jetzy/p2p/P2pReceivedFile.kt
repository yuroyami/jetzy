package jetz.common.p2p

import androidx.documentfile.provider.DocumentFile

actual class P2pReceivedFile(val name: String, val file: DocumentFile)

actual class P2pTempFolder(val tempDirectory: DocumentFile)

actual typealias P2pPeer = P2pPeerAndroid

data class P2pPeerAndroid(
    val id: String,
    val name: String
)

actual fun P2pPeer.peerName() = this.name