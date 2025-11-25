package jetzy.p2p

import jetzy.utils.Platform
import jetzy.utils.platform

// Registry to get available methods
object P2PMethodRegistry {
    val allMethods = listOf(
        P2pMethod.WiFiDirect,
        P2pMethod.NearbyConnections,
        P2pMethod.MultipeerConnectivity,
        P2pMethod.LocalNetwork,
        P2pMethod.WebRTC,
        P2pMethod.HotspotLAN,
        P2pMethod.Bluetooth
    )

    fun getAvailableMethods(targetPlatform: Platform): List<P2pMethod> {
        return allMethods
            .filter { it.isAvailable(platform, targetPlatform) }
            .sortedByDescending { it.priority }
    }

    fun getMethodById(id: String): P2pMethod? {
        return allMethods.find { it.id == id }
    }
}