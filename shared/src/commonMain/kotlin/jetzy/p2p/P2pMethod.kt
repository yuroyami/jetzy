package jetzy.p2p

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Lan
import androidx.compose.material.icons.outlined.LeakAdd
import androidx.compose.material.icons.outlined.SettingsEthernet
import androidx.compose.material.icons.outlined.WifiTethering
import androidx.compose.ui.graphics.vector.ImageVector
import jetzy.utils.Platform

sealed class P2pMethod(
    val id: String,
    val displayName: String,
    val description: String,
    val icon: ImageVector,
    val supportedPlatforms: Set<Platform>,
    val priority: MethodPriority
) {
    abstract fun isAvailable(currentPlatform: Platform, targetPlatform: Platform): Boolean
    abstract suspend fun initiate(): P2PConnection

    // Common method instances
    object LocalNetwork : P2pMethod(
        id = "local_network",
        displayName = "Local Network",
        description = "Connect via WiFi network",
        supportedPlatforms = setOf(Platform.Android, Platform.IOS, Platform.Web, Platform.PC),
        priority = MethodPriority.RECOMMENDED,
        icon = Icons.Outlined.Lan
    ) {
        override fun isAvailable(currentPlatform: Platform, targetPlatform: Platform): Boolean {
            return currentPlatform in supportedPlatforms && targetPlatform in supportedPlatforms
        }

        override suspend fun initiate(): P2PConnection = TODO("Implement mDNS/Bonjour discovery")
    }

    object WebRTC : P2pMethod(
        id = "webrtc",
        displayName = "WebRTC",
        description = "Direct peer connection",
        supportedPlatforms = setOf(Platform.Android, Platform.IOS, Platform.Web, Platform.PC),
        priority = MethodPriority.ACCEPTABLE,
        icon = Icons.Outlined.SettingsEthernet //todo
    ) {
        override fun isAvailable(currentPlatform: Platform, targetPlatform: Platform): Boolean {
            return currentPlatform in supportedPlatforms && targetPlatform in supportedPlatforms
        }

        override suspend fun initiate(): P2PConnection = TODO("Implement WebRTC")
    }

    object Bluetooth : P2pMethod(
        id = "bluetooth",
        displayName = "Bluetooth",
        description = "Connect via Bluetooth",
        supportedPlatforms = setOf(Platform.Android, Platform.IOS, Platform.PC),
        priority = MethodPriority.FALLBACK,
        icon = Icons.Outlined.Bluetooth
    ) {
        override fun isAvailable(currentPlatform: Platform, targetPlatform: Platform): Boolean {
            return currentPlatform in supportedPlatforms && targetPlatform in supportedPlatforms
        }

        override suspend fun initiate(): P2PConnection = TODO("Implement Bluetooth")
    }

    // Android-specific methods
    object WiFiDirect : P2pMethod(
        id = "wifi_direct",
        displayName = "WiFi Direct",
        description = "Fast direct WiFi connection",
        supportedPlatforms = setOf(Platform.Android),
        priority = MethodPriority.RECOMMENDED,
        icon = Icons.Outlined.LeakAdd
    ) {
        override fun isAvailable(currentPlatform: Platform, targetPlatform: Platform): Boolean {
            return currentPlatform == Platform.Android && targetPlatform == Platform.Android
        }

        override suspend fun initiate(): P2PConnection = TODO("Implement WiFi Direct")
    }

    object NearbyConnections : P2pMethod(
        id = "nearby_connections",
        displayName = "Nearby Share",
        description = "Google Nearby Connections",
        supportedPlatforms = setOf(Platform.Android),
        priority = MethodPriority.RECOMMENDED,
        icon = Icons.Outlined.Hub
    ) {
        override fun isAvailable(currentPlatform: Platform, targetPlatform: Platform): Boolean {
            return currentPlatform == Platform.Android && targetPlatform == Platform.Android
        }

        override suspend fun initiate(): P2PConnection = TODO("Implement Nearby Connections")
    }

    object HotspotLAN : P2pMethod(
        id = "hotspot_lan",
        displayName = "Hotspot Transfer",
        description = "Create WiFi hotspot for transfer",
        supportedPlatforms = setOf(Platform.Android, Platform.IOS),
        priority = MethodPriority.ACCEPTABLE,
        icon = Icons.Outlined.WifiTethering
    ) {
        override fun isAvailable(currentPlatform: Platform, targetPlatform: Platform): Boolean {
            return (currentPlatform == Platform.Android || currentPlatform == Platform.IOS) &&
                    (targetPlatform == Platform.Android || targetPlatform == Platform.IOS ||
                            targetPlatform == Platform.PC)
        }

        override suspend fun initiate(): P2PConnection = TODO("Implement Hotspot")
    }

    // iOS-specific methods
    object MultipeerConnectivity : P2pMethod(
        id = "multipeer",
        displayName = "MultipeerConnectivity",
        description = "Apple's peer-to-peer framework",
        supportedPlatforms = setOf(Platform.IOS),
        priority = MethodPriority.RECOMMENDED,
        icon = Icons.Outlined.Hub
    ) {
        override fun isAvailable(currentPlatform: Platform, targetPlatform: Platform): Boolean {
            return currentPlatform == Platform.IOS && targetPlatform == Platform.IOS
        }

        override suspend fun initiate(): P2PConnection = TODO("Implement MultipeerConnectivity")
    }
}