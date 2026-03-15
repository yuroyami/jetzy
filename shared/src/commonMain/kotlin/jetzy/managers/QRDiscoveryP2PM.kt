package jetzy.managers

import jetzy.p2p.P2pDiscoveryMode

/**
 * Manager that supports QR discovery by scanning or showing a QR code
 * Great for LAN/Hotspot technologies
 */
abstract class QRDiscoveryP2PM : P2PManager() {

    //TODO Observe socket state in order to keep UI state fresh and up-to-date

    override val discoveryMode: P2pDiscoveryMode = P2pDiscoveryMode.QRCode

}