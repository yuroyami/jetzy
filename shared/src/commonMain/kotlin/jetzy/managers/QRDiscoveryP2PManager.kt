package jetzy.managers

import jetzy.p2p.P2pDiscoveryMode

/**
 * Manager that supports QR discovery by scanning or showing a QR code
 * Great for LAN/Hotspot technologies
 */
abstract class QRDiscoveryP2PManager : P2PManager() {

    override val discoveryMode: P2pDiscoveryMode = P2pDiscoveryMode.QRCode

}