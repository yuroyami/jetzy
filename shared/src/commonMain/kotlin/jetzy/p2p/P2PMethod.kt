package jetzy.p2p

import jetzy.utils.Platform

sealed class P2PMethod(
    val id: String,
    val displayName: String,
    val description: String,
    val supportedPlatforms: Set<Platform>,
    val priority: MethodPriority
) {
    abstract fun isAvailable(currentPlatform: Platform, targetPlatform: Platform): Boolean
    abstract suspend fun initiate(): P2PConnection

    // Common method instances
    object LocalNetwork : P2PMethod(
        id = "local_network",
        displayName = "Local Network",
        description = "Connect via WiFi network",
        supportedPlatforms = setOf(Platform.Android, Platform.IOS, Platform.Web, Platform.PC),
        priority = MethodPriority.RECOMMENDED
    ) {
        override fun isAvailable(currentPlatform: Platform, targetPlatform: Platform): Boolean {
            return currentPlatform in supportedPlatforms && targetPlatform in supportedPlatforms
        }

        override suspend fun initiate(): P2PConnection = TODO("Implement mDNS/Bonjour discovery")
    }

    object WebRTC : P2PMethod(
        id = "webrtc",
        displayName = "WebRTC",
        description = "Direct peer connection",
        supportedPlatforms = setOf(Platform.Android, Platform.IOS, Platform.Web, Platform.PC),
        priority = MethodPriority.ACCEPTABLE
    ) {
        override fun isAvailable(currentPlatform: Platform, targetPlatform: Platform): Boolean {
            return currentPlatform in supportedPlatforms && targetPlatform in supportedPlatforms
        }

        override suspend fun initiate(): P2PConnection = TODO("Implement WebRTC")
    }

    object Bluetooth : P2PMethod(
        id = "bluetooth",
        displayName = "Bluetooth",
        description = "Connect via Bluetooth",
        supportedPlatforms = setOf(Platform.Android, Platform.IOS, Platform.PC),
        priority = MethodPriority.FALLBACK
    ) {
        override fun isAvailable(currentPlatform: Platform, targetPlatform: Platform): Boolean {
            return currentPlatform in supportedPlatforms && targetPlatform in supportedPlatforms
        }

        override suspend fun initiate(): P2PConnection = TODO("Implement Bluetooth")
    }

    // Android-specific methods
    object WiFiDirect : P2PMethod(
        id = "wifi_direct",
        displayName = "WiFi Direct",
        description = "Fast direct WiFi connection",
        supportedPlatforms = setOf(Platform.Android),
        priority = MethodPriority.RECOMMENDED
    ) {
        override fun isAvailable(currentPlatform: Platform, targetPlatform: Platform): Boolean {
            return currentPlatform == Platform.Android && targetPlatform == Platform.Android
        }

        override suspend fun initiate(): P2PConnection = TODO("Implement WiFi Direct")
    }

    object NearbyConnections : P2PMethod(
        id = "nearby_connections",
        displayName = "Nearby Share",
        description = "Google Nearby Connections",
        supportedPlatforms = setOf(Platform.Android),
        priority = MethodPriority.RECOMMENDED
    ) {
        override fun isAvailable(currentPlatform: Platform, targetPlatform: Platform): Boolean {
            return currentPlatform == Platform.Android && targetPlatform == Platform.Android
        }

        override suspend fun initiate(): P2PConnection = TODO("Implement Nearby Connections")
    }

    object HotspotLAN : P2PMethod(
        id = "hotspot_lan",
        displayName = "Hotspot Transfer",
        description = "Create WiFi hotspot for transfer",
        supportedPlatforms = setOf(Platform.Android, Platform.IOS),
        priority = MethodPriority.ACCEPTABLE
    ) {
        override fun isAvailable(currentPlatform: Platform, targetPlatform: Platform): Boolean {
            return (currentPlatform == Platform.Android || currentPlatform == Platform.IOS) &&
                    (targetPlatform == Platform.Android || targetPlatform == Platform.IOS ||
                            targetPlatform == Platform.PC)
        }

        override suspend fun initiate(): P2PConnection = TODO("Implement Hotspot")
    }

    // iOS-specific methods
    object MultipeerConnectivity : P2PMethod(
        id = "multipeer",
        displayName = "MultipeerConnectivity",
        description = "Apple's peer-to-peer framework",
        supportedPlatforms = setOf(Platform.IOS),
        priority = MethodPriority.RECOMMENDED
    ) {
        override fun isAvailable(currentPlatform: Platform, targetPlatform: Platform): Boolean {
            return currentPlatform == Platform.IOS && targetPlatform == Platform.IOS
        }

        override suspend fun initiate(): P2PConnection = TODO("Implement MultipeerConnectivity")
    }
}