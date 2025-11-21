package jetzy.p2p

import jetzy.utils.Platform

// Registry to get available methods
object P2PMethodRegistry {
    private val allMethods = listOf(
        P2PMethod.WiFiDirect,
        P2PMethod.NearbyConnections,
        P2PMethod.MultipeerConnectivity,
        P2PMethod.LocalNetwork,
        P2PMethod.WebRTC,
        P2PMethod.HotspotLAN,
        P2PMethod.Bluetooth
    )

    fun getAvailableMethods(
        currentPlatform: Platform,
        targetPlatform: Platform
    ): List<P2PMethod> {
        return allMethods
            .filter { it.isAvailable(currentPlatform, targetPlatform) }
            .sortedByDescending { it.priority }
    }

    fun getMethodById(id: String): P2PMethod? {
        return allMethods.find { it.id == id }
    }
}