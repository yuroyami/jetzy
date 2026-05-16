package jetzy.p2p

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Lan
import androidx.compose.material.icons.outlined.LeakAdd
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material.icons.outlined.WifiTethering
import androidx.compose.ui.graphics.vector.ImageVector
import jetzy.utils.Platform
import jetzy.utils.isWifiAwareSupported
import jetzy.utils.platform

sealed class P2pTechnology(
    val id: String,
    val icon: ImageVector,
    val supportedPlatforms: Set<Platform>,
    val priority: P2pTechPriority,
    val discoveryMode: P2pDiscoveryMode
) {
    companion object Registry {
        val allMethods = listOf(
            WiFiAware, WiFiDirect, NearbyConnections, MultipeerConnectivity,
            LocalNetworkMdns, LocalNetwork, HotspotLAN, BluetoothSpp, Bluetooth,
        )

        fun getBestTechnology(targetPlatform: Platform) = getAvailableMethods(targetPlatform).firstOrNull()

        fun getAvailableMethods(targetPlatform: Platform): List<P2pTechnology> {
            return allMethods.filter { it.isAvailable(platform, targetPlatform) }.sortedByDescending { it.priority }
        }

        fun getMethodById(id: String): P2pTechnology? = allMethods.find { it.id == id }
    }

    abstract fun isAvailable(currentPlatform: Platform, targetPlatform: Platform): Boolean

    // Common method instances

    /**
     * Wi-Fi Aware (NAN): the cross-vendor 802.11 peer-to-peer standard. Available
     * on Android 8+ (with FEATURE_WIFI_AWARE) and iOS 26+. Preferred over the
     * Android-hotspot dance when both peers support it — sub-second discovery,
     * no Wi-Fi disconnect, no QR-paste. Falls back transparently via [isAvailable]
     * when either device is too old or lacks the chip.
     */
    object WiFiAware : P2pTechnology(
        id = "wifi_aware",
        supportedPlatforms = setOf(Platform.Android, Platform.IOS),
        priority = P2pTechPriority.RECOMMENDED,
        icon = Icons.Outlined.Sensors,
        discoveryMode = P2pDiscoveryMode.PeerDiscovery,
    ) {
        override fun isAvailable(currentPlatform: Platform, targetPlatform: Platform): Boolean {
            if (currentPlatform !in supportedPlatforms || targetPlatform !in supportedPlatforms) return false
            // We only know the local device's capabilities; we trust the peer to also
            // be running a Wi-Fi Aware-capable Jetzy build, and gracefully fall through
            // to HotspotLAN if it isn't (the publish times out → next-best transport).
            return isWifiAwareSupported()
        }
    }

    /**
     * Same-LAN discovery via mDNS / Bonjour (`_jetzy._tcp.local.`). Universal across
     * platforms — Android uses NsdManager, iOS uses Network framework's NWBrowser/NWListener,
     * desktop uses jmdns. No QR-paste, peers auto-discover each other. Works for any
     * pair as long as both devices are on the same Wi-Fi / Ethernet segment.
     */
    object LocalNetworkMdns : P2pTechnology(
        id = "local_network_mdns",
        supportedPlatforms = setOf(Platform.Android, Platform.IOS, Platform.PC, Platform.Web),
        priority = P2pTechPriority.RECOMMENDED,
        icon = Icons.Outlined.Lan,
        discoveryMode = P2pDiscoveryMode.PeerDiscovery,
    ) {
        override fun isAvailable(currentPlatform: Platform, targetPlatform: Platform): Boolean {
            // We can't *know* the peer is on the same LAN, only that both platforms support mDNS.
            // The discovery screen will surface "no peers found" if they're not, and the user
            // can back out to try another transport.
            return currentPlatform in supportedPlatforms && targetPlatform in supportedPlatforms
        }
    }

    /** Legacy QR-paste LAN entry. Kept as a fallback when mDNS is filtered (some corporate
     *  networks block multicast) or when the user wants the explicit IP+port flow. */
    object LocalNetwork : P2pTechnology(
        id = "local_network",
        supportedPlatforms = setOf(Platform.Web, Platform.PC),
        priority = P2pTechPriority.ACCEPTABLE,
        icon = Icons.Outlined.Lan,
        discoveryMode = P2pDiscoveryMode.QRCode

    ) {
        override fun isAvailable(currentPlatform: Platform, targetPlatform: Platform): Boolean {
            return currentPlatform in supportedPlatforms && targetPlatform in supportedPlatforms
        }
    }

    /**
     * Bluetooth Classic RFCOMM SPP — fallback when Wi-Fi-based transports aren't available
     * (no shared LAN, no Wi-Fi P2P pair, etc.). Cross-platform between Android and Linux/macOS
     * PCs; **iOS is excluded** because Apple gates Classic Bluetooth behind MFi certification.
     * Slow (~2 Mbps practical) but works in scenarios where every Wi-Fi path fails.
     */
    object BluetoothSpp : P2pTechnology(
        id = "bluetooth_spp",
        supportedPlatforms = setOf(Platform.Android, Platform.PC),
        priority = P2pTechPriority.FALLBACK,
        icon = Icons.Outlined.Bluetooth,
        discoveryMode = P2pDiscoveryMode.PeerDiscovery,
    ) {
        override fun isAvailable(currentPlatform: Platform, targetPlatform: Platform): Boolean {
            return currentPlatform in supportedPlatforms && targetPlatform in supportedPlatforms
        }
    }

    /** Generic Bluetooth marker for the registry — represents the BLE / Object-Push family,
     *  not actually wired to a manager yet. Kept distinct from [BluetoothSpp] which we DO ship. */
    object Bluetooth : P2pTechnology(
        id = "bluetooth",
        supportedPlatforms = setOf(Platform.Android, Platform.IOS, Platform.PC),
        priority = P2pTechPriority.FALLBACK,
        icon = Icons.Outlined.Bluetooth,
        discoveryMode = P2pDiscoveryMode.PeerDiscovery
    ) {
        override fun isAvailable(currentPlatform: Platform, targetPlatform: Platform): Boolean {
            // Until we ship a Bluetooth Classic stack on iOS (MFi-gated) or BLE-based transfer,
            // this slot exists for completeness but reports unavailable.
            return false
        }
    }

    /**
     * Wi-Fi Direct (IEEE 802.11 P2P): Android-native, Linux via wpa_supplicant,
     * Windows via WinRT (Windows path not shipped yet — JNA/COM bindings outstanding).
     * macOS is excluded — Apple has never exposed Wi-Fi Direct publicly.
     */
    object WiFiDirect : P2pTechnology(
        id = "wifi_direct",
        supportedPlatforms = setOf(Platform.Android, Platform.PC),
        priority = P2pTechPriority.RECOMMENDED,
        icon = Icons.Outlined.LeakAdd,
        discoveryMode = P2pDiscoveryMode.PeerDiscovery
    ) {
        override fun isAvailable(currentPlatform: Platform, targetPlatform: Platform): Boolean {
            return currentPlatform in supportedPlatforms && targetPlatform in supportedPlatforms
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