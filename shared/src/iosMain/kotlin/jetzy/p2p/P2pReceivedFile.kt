package jetz.common.p2p

import platform.Foundation.NSURL

actual class P2pReceivedFile(val name: String, val url: NSURL)

actual class P2pTempFolder(val tempUrl: NSURL)

actual typealias P2pPeer = P2pAppleHandler.PeerDevice

actual fun P2pPeer.peerName() = this.peerName