package jetzy.p2p

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Lan
import androidx.compose.material.icons.outlined.LeakAdd
import androidx.compose.material.icons.outlined.WifiTethering
import androidx.compose.ui.graphics.vector.ImageVector
import jetzy.utils.Platform
import jetzy.utils.platform

sealed class P2pTechnology(
    val id: String,
    val icon: ImageVector,
    val supportedPlatforms: Set<Platform>,
    val priority: P2pTechPriority,
    val discoveryMode: P2pDiscoveryMode
) {
    companion object Registry {
        val allMethods = listOf(WiFiDirect, NearbyConnections, MultipeerConnectivity, LocalNetwork, HotspotLAN, Bluetooth)

        fun getBestTechnology(targetPlatform: Platform) = getAvailableMethods(targetPlatform).firstOrNull()

        fun getAvailableMethods(targetPlatform: Platform): List<P2pTechnology> {
            return allMethods.filter { it.isAvailable(platform, targetPlatform) }.sortedByDescending { it.priority }
        }

        fun getMethodById(id: String): P2pTechnology? = allMethods.find { it.id == id }
    }

    abstract fun isAvailable(currentPlatform: Platform, targetPlatform: Platform): Boolean

    // Common method instances
    object LocalNetwork : P2pTechnology(
        id = "local_network",
        supportedPlatforms = setOf(Platform.Web, Platform.PC),
        priority = P2pTechPriority.RECOMMENDED,
        icon = Icons.Outlined.Lan,
        discoveryMode = P2pDiscoveryMode.QRCode

    ) {
        override fun isAvailable(currentPlatform: Platform, targetPlatform: Platform): Boolean {
            return currentPlatform in supportedPlatforms && targetPlatform in supportedPlatforms
        }
    }

    object Bluetooth : P2pTechnology(
        id = "bluetooth",
        supportedPlatforms = setOf(Platform.Android, Platform.IOS, Platform.PC),
        priority = P2pTechPriority.FALLBACK,
        icon = Icons.Outlined.Bluetooth,
        discoveryMode = P2pDiscoveryMode.PeerDiscovery
    ) {
        override fun isAvailable(currentPlatform: Platform, targetPlatform: Platform): Boolean {
            return currentPlatform in supportedPlatforms && targetPlatform in supportedPlatforms
        }
    }

    // Android-specific methods
    object WiFiDirect : P2pTechnology(
        id = "wifi_direct",
        supportedPlatforms = setOf(Platform.Android),
        priority = P2pTechPriority.RECOMMENDED,
        icon = Icons.Outlined.LeakAdd,
        discoveryMode = P2pDiscoveryMode.PeerDiscovery
    ) {
        override fun isAvailable(currentPlatform: Platform, targetPlatform: Platform): Boolean {
            return currentPlatform == Platform.Android && targetPlatform == Platform.Android
        }
    }

    object NearbyConnections : P2pTechnology(
        id = "nearby_connections",
        supportedPlatforms = setOf(Platform.Android),
        priority = P2pTechPriority.RECOMMENDED,
        icon = Icons.Outlined.Hub,
        discoveryMode = P2pDiscoveryMode.PeerDiscovery
    ) {
        override fun isAvailable(currentPlatform: Platform, targetPlatform: Platform): Boolean {
            return currentPlatform == Platform.Android && targetPlatform == Platform.Android
        }
    }

    object HotspotLAN : P2pTechnology(
        id = "hotspot_lan",
        supportedPlatforms = setOf(Platform.Android, Platform.IOS),
        priority = P2pTechPriority.ACCEPTABLE,
        icon = Icons.Outlined.WifiTethering,
        discoveryMode = P2pDiscoveryMode.QRCode
    ) {
        override fun isAvailable(currentPlatform: Platform, targetPlatform: Platform): Boolean {
            return currentPlatform in setOf(Platform.Android, Platform.IOS) &&
                    targetPlatform in setOf(Platform.Android, Platform.IOS, Platform.PC)
        }
    }

    // iOS-specific methods
    object MultipeerConnectivity : P2pTechnology(
        id = "multipeer",
        supportedPlatforms = setOf(Platform.IOS),
        priority = P2pTechPriority.RECOMMENDED,
        icon = Icons.Outlined.Hub,
        discoveryMode = P2pDiscoveryMode.PeerDiscovery
    ) {
        override fun isAvailable(currentPlatform: Platform, targetPlatform: Platform): Boolean {
            return currentPlatform == Platform.IOS && targetPlatform == Platform.IOS
        }
    }
}