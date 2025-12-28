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
    val priority: MethodPriority,
    val pros: List<String>,
    val cons: List<String>,
    val whenToUse: String
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
        icon = Icons.Outlined.Lan,
        pros = listOf(
            "Very fast (60-110 MB/s)",
            "Reliable and stable",
            "No extra setup needed. Just scan QR and go!"
        ),
        cons = listOf(
            "Requires same WiFi/Ethernet network"
        ),
        whenToUse = "Best for transferring between any devices on the same WiFi network (home, office, etc.)"
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
        priority = MethodPriority.FALLBACK,
        icon = Icons.Outlined.SettingsEthernet,
        pros = listOf(
            "Built-in encryption",
            "Bypasses most firewalls"
        ),
        cons = listOf(
            "Slower (20-30 MB/s max)",
            "Requires signaling server",
            "Higher latency",
            "Can fail with strict NAT"
        ),
        whenToUse = "Use when devices are on different networks or when sending to/from a web browser"
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
        icon = Icons.Outlined.Bluetooth,
        pros = listOf(
            "Works without WiFi",
            "Low power consumption",
            "Easy pairing",
            "Very short range = secure"
        ),
        cons = listOf(
            "Very slow (1-3 MB/s)",
            "Limited range (10m)",
            "Not good for large files",
            "iOS has restrictions"
        ),
        whenToUse = "Good for small files when WiFi is unavailable (outdoors, traveling, etc.)"
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
        icon = Icons.Outlined.LeakAdd,
        pros = listOf(
            "Extremely fast (100-250 MB/s)",
            "No router needed",
            "Direct device-to-device",
            "Low latency"
        ),
        cons = listOf(
            "Android-to-Android only",
            "Requires location permission",
            "Can be finicky to establish",
            "Disconnects existing WiFi"
        ),
        whenToUse = "Perfect for fast transfers between Android devices without a router nearby"
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
        icon = Icons.Outlined.Hub,
        pros = listOf(
            "Fast (uses WiFi Direct internally)",
            "Automatic method selection",
            "Easy discovery",
            "Integrated with Android"
        ),
        cons = listOf(
            "Android-to-Android only",
            "Requires Google Play Services",
            "Less control over connection",
            "Requires multiple permissions"
        ),
        whenToUse = "Easiest option for Android-to-Android when you want automatic setup"
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
        icon = Icons.Outlined.WifiTethering,
        pros = listOf(
            "Fast (60-100 MB/s)",
            "No existing network needed",
        ),
        cons = listOf(
            "Disconnects from internet (for sender and receiver)",
            "Requires hotspot permission",
            "Battery drain on sender",
            "Manual setup required"
        ),
        whenToUse = "Use when no WiFi network is available but you need cross-platform speed"
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
        icon = Icons.Outlined.Hub,
        pros = listOf(
            "Fast (like AirDrop)",
            "Native iOS integration"
        ),
        cons = listOf(
            "iOS/Mac only",
            "Requires Bluetooth + WiFi on",
            "Limited to 8 peers",
        ),
        whenToUse = "Best choice for iPhone-to-iPhone or iPhone-to-Mac transfers"
    ) {
        override fun isAvailable(currentPlatform: Platform, targetPlatform: Platform): Boolean {
            return currentPlatform == Platform.IOS && targetPlatform == Platform.IOS
        }

        override suspend fun initiate(): P2PConnection = TODO("Implement MultipeerConnectivity")
    }
}