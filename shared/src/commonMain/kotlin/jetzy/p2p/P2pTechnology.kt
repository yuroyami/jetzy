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

/**
 * Registry of every transport Jetzy knows about. Each entry declares *what it is* and *who
 * can use it* — the actual selection is done by [TransportNegotiator] from two
 * [CapabilityProfile]s (so the choice is based on what the peer *actually* supports, exchanged
 * out-of-band via QR / discovery advert, not a guess from its platform).
 */
sealed class P2pTechnology(
    val id: String,
    val icon: ImageVector,
    val supportedPlatforms: Set<Platform>,
    val priority: P2pTechPriority,
    val discoveryMode: P2pDiscoveryMode,
    /**
     * Stable bit assignment for the [jetzy.managers.JetzyProtocol.HelloFrame.capabilities]
     * bitmask and the `caps` field in [jetzy.models.QRData]. Each technology owns one bit
     * for life; new technologies take the next free bit. Never re-number a shipped bit —
     * older peers will misinterpret the mask. 64 slots available (Long width).
     */
    val capabilityBit: Int,
    /**
     * Intrinsic desirability (0–100) of this transport *when it works* — a blend of
     * throughput, setup friction, and how disruptive it is to the device's existing
     * connectivity. [TransportNegotiator] ranks the mutually-supported transports by this.
     * Finer-grained than [priority] (which is kept for display/tiering).
     */
    val quality: Int,
) {
    companion object Registry {
        // `by lazy` here is load-bearing, not a style choice: the objects below subclass
        // P2pTechnology, so initializing any one of them forces this companion to initialize
        // first (superclass-before-subclass). If this list were eager it would run during that
        // re-entrant init and capture not-yet-constructed (null) object instances. Deferring the
        // build to first use guarantees every object is fully initialized by then.
        val allMethods: List<P2pTechnology> by lazy {
            listOf(
                WiFiAware, WiFiDirect, NearbyConnections, MultipeerConnectivity,
                LocalNetworkMdns, LocalNetwork, HotspotLAN, BluetoothSpp, Bluetooth,
            )
        }

        fun getMethodById(id: String): P2pTechnology? = allMethods.find { it.id == id }

        /**
         * This device's capability bitmask — every transport it can participate in (as host
         * or client) given its platform + runtime support (e.g. [WiFiAware] also needs the
         * Wi-Fi Aware chip). Shipped in QR codes and HELLO frames so the peer can negotiate
         * the best mutual transport with zero round-trips.
         */
        fun localCapabilitiesMask(): Long =
            allMethods.filter { it.isLocallyCapable(platform) }
                .fold(0L) { acc, t -> acc or (1L shl t.capabilityBit) }

        /** Inverse of the bitmask — the set of technologies a mask encodes. */
        fun Long.toCapabilities(): Set<P2pTechnology> =
            allMethods.filterTo(mutableSetOf()) { (this and (1L shl it.capabilityBit)) != 0L }
    }

    /**
     * Can THIS device participate in this transport at all — as host *or* client — given its
     * platform and runtime support? Role-agnostic; [hostAffinity] decides who actually hosts.
     * Drives [localCapabilitiesMask]. (Example: a PC can't *host* a hotspot but can *join* one,
     * so [HotspotLAN] is locally-capable on PC; [hostAffinity] then makes the PC the client.)
     */
    abstract fun isLocallyCapable(currentPlatform: Platform): Boolean

    /**
     * For a peer pair, which side hosts this transport's data plane. Must be symmetric:
     * `A.hostAffinity(a, b)` and `B.hostAffinity(b, a)` must agree on the host
     * (LOCAL mirrors REMOTE; EITHER is resolved by [TransportNegotiator]'s tiebreak).
     * Returns [HostAffinity.NEITHER] when the pair can't use this transport.
     */
    abstract fun hostAffinity(localPlatform: Platform, remotePlatform: Platform): HostAffinity

    // ── Transport instances ───────────────────────────────────────────────────

    /**
     * Wi-Fi Aware (NAN): the cross-vendor 802.11 peer-to-peer standard. Android 8+ (with
     * FEATURE_WIFI_AWARE) and iOS 26+. The best link when both sides have it — sub-second
     * discovery, full Wi-Fi speed, and no Wi-Fi disconnect. A device only advertises this
     * capability when its chip+OS actually support it, so the mask naturally excludes old gear.
     */
    object WiFiAware : P2pTechnology(
        id = "wifi_aware",
        supportedPlatforms = setOf(Platform.Android, Platform.IOS),
        priority = P2pTechPriority.RECOMMENDED,
        icon = Icons.Outlined.Sensors,
        discoveryMode = P2pDiscoveryMode.PeerDiscovery,
        capabilityBit = 0,
        quality = 92,
    ) {
        override fun isLocallyCapable(currentPlatform: Platform): Boolean =
            currentPlatform in supportedPlatforms && isWifiAwareSupported()

        override fun hostAffinity(localPlatform: Platform, remotePlatform: Platform): HostAffinity =
            if (localPlatform in supportedPlatforms && remotePlatform in supportedPlatforms)
                HostAffinity.EITHER else HostAffinity.NEITHER
    }

    /**
     * Wi-Fi Direct (IEEE 802.11 P2P): Android-native, Linux via wpa_supplicant. macOS is
     * excluded — Apple never exposed it publicly. (Desktop data path is WIP, so the desktop
     * coordinator currently keeps it off its ladder.)
     */
    object WiFiDirect : P2pTechnology(
        id = "wifi_direct",
        supportedPlatforms = setOf(Platform.Android, Platform.PC),
        priority = P2pTechPriority.RECOMMENDED,
        icon = Icons.Outlined.LeakAdd,
        discoveryMode = P2pDiscoveryMode.PeerDiscovery,
        capabilityBit = 1,
        quality = 84,
    ) {
        override fun isLocallyCapable(currentPlatform: Platform): Boolean =
            currentPlatform in supportedPlatforms

        override fun hostAffinity(localPlatform: Platform, remotePlatform: Platform): HostAffinity =
            if (localPlatform in supportedPlatforms && remotePlatform in supportedPlatforms)
                HostAffinity.EITHER else HostAffinity.NEITHER
    }

    /** Google Nearby Connections — Android↔Android, robust auto-discovery + auto medium pick. */
    object NearbyConnections : P2pTechnology(
        id = "nearby_connections",
        supportedPlatforms = setOf(Platform.Android),
        priority = P2pTechPriority.RECOMMENDED,
        icon = Icons.Outlined.Hub,
        discoveryMode = P2pDiscoveryMode.PeerDiscovery,
        capabilityBit = 2,
        quality = 86,
    ) {
        override fun isLocallyCapable(currentPlatform: Platform): Boolean =
            currentPlatform == Platform.Android

        override fun hostAffinity(localPlatform: Platform, remotePlatform: Platform): HostAffinity =
            if (localPlatform == Platform.Android && remotePlatform == Platform.Android)
                HostAffinity.EITHER else HostAffinity.NEITHER
    }

    /** Apple MultipeerConnectivity — iOS↔iOS over Apple's native stack. */
    object MultipeerConnectivity : P2pTechnology(
        id = "multipeer",
        supportedPlatforms = setOf(Platform.IOS),
        priority = P2pTechPriority.RECOMMENDED,
        icon = Icons.Outlined.Hub,
        discoveryMode = P2pDiscoveryMode.PeerDiscovery,
        capabilityBit = 3,
        quality = 88,
    ) {
        override fun isLocallyCapable(currentPlatform: Platform): Boolean =
            currentPlatform == Platform.IOS

        override fun hostAffinity(localPlatform: Platform, remotePlatform: Platform): HostAffinity =
            if (localPlatform == Platform.IOS && remotePlatform == Platform.IOS)
                HostAffinity.EITHER else HostAffinity.NEITHER
    }

    /**
     * Same-LAN discovery via mDNS / Bonjour (`_jetzy._tcp.local.`). Universal across platforms
     * (Android NsdManager, iOS NWBrowser/NWListener, desktop jmdns). No QR — peers auto-discover
     * — as long as both are on the same Wi-Fi/Ethernet segment and it isn't multicast-filtered.
     */
    object LocalNetworkMdns : P2pTechnology(
        id = "local_network_mdns",
        supportedPlatforms = setOf(Platform.Android, Platform.IOS, Platform.PC, Platform.Web),
        priority = P2pTechPriority.RECOMMENDED,
        icon = Icons.Outlined.Lan,
        discoveryMode = P2pDiscoveryMode.PeerDiscovery,
        capabilityBit = 4,
        quality = 80,
    ) {
        override fun isLocallyCapable(currentPlatform: Platform): Boolean =
            currentPlatform in supportedPlatforms

        override fun hostAffinity(localPlatform: Platform, remotePlatform: Platform): HostAffinity =
            if (localPlatform in supportedPlatforms && remotePlatform in supportedPlatforms)
                HostAffinity.EITHER else HostAffinity.NEITHER
    }

    /** Legacy QR-paste LAN entry (explicit IP+port). Kept for multicast-blocked networks. */
    object LocalNetwork : P2pTechnology(
        id = "local_network",
        supportedPlatforms = setOf(Platform.Web, Platform.PC),
        priority = P2pTechPriority.ACCEPTABLE,
        icon = Icons.Outlined.Lan,
        discoveryMode = P2pDiscoveryMode.QRCode,
        capabilityBit = 5,
        quality = 55,
    ) {
        override fun isLocallyCapable(currentPlatform: Platform): Boolean =
            currentPlatform in supportedPlatforms

        override fun hostAffinity(localPlatform: Platform, remotePlatform: Platform): HostAffinity =
            if (localPlatform in supportedPlatforms && remotePlatform in supportedPlatforms)
                HostAffinity.EITHER else HostAffinity.NEITHER
    }

    /**
     * Local hotspot bridge: an Android device provisions a WPA2 hotspot, the peer joins, and
     * payloads stream over a TCP socket on that network. **Only Android can host** (iOS's
     * NEHotspotConfiguration only *joins*; PC can join too). The universal cross-platform path
     * when there's no shared Wi-Fi — disruptive (toggles the host's Wi-Fi), hence mid-quality.
     */
    object HotspotLAN : P2pTechnology(
        id = "hotspot_lan",
        supportedPlatforms = setOf(Platform.Android, Platform.IOS),
        priority = P2pTechPriority.ACCEPTABLE,
        icon = Icons.Outlined.WifiTethering,
        discoveryMode = P2pDiscoveryMode.QRCode,
        capabilityBit = 6,
        quality = 62,
    ) {
        // Android hosts; iOS and PC can join. (Anyone but a host-less pair can take part.)
        override fun isLocallyCapable(currentPlatform: Platform): Boolean =
            currentPlatform in setOf(Platform.Android, Platform.IOS, Platform.PC)

        override fun hostAffinity(localPlatform: Platform, remotePlatform: Platform): HostAffinity {
            val localCanHost = localPlatform == Platform.Android
            val remoteCanHost = remotePlatform == Platform.Android
            return when {
                localCanHost && remoteCanHost -> HostAffinity.EITHER
                localCanHost -> HostAffinity.LOCAL
                remoteCanHost -> HostAffinity.REMOTE
                else -> HostAffinity.NEITHER // neither side can create the AP
            }
        }
    }

    /**
     * Bluetooth Classic RFCOMM SPP — last-resort when every Wi-Fi path fails. Android & Linux/
     * macOS PCs; iOS excluded (Apple gates Classic BT behind MFi). Slow (~2 Mbps) but works.
     */
    object BluetoothSpp : P2pTechnology(
        id = "bluetooth_spp",
        supportedPlatforms = setOf(Platform.Android, Platform.PC),
        priority = P2pTechPriority.FALLBACK,
        icon = Icons.Outlined.Bluetooth,
        discoveryMode = P2pDiscoveryMode.PeerDiscovery,
        capabilityBit = 7,
        quality = 25,
    ) {
        override fun isLocallyCapable(currentPlatform: Platform): Boolean =
            currentPlatform in supportedPlatforms

        override fun hostAffinity(localPlatform: Platform, remotePlatform: Platform): HostAffinity =
            if (localPlatform in supportedPlatforms && remotePlatform in supportedPlatforms)
                HostAffinity.EITHER else HostAffinity.NEITHER
    }

    /** Reserved BLE / Object-Push slot — not wired to a manager yet, so never locally capable. */
    object Bluetooth : P2pTechnology(
        id = "bluetooth",
        supportedPlatforms = setOf(Platform.Android, Platform.IOS, Platform.PC),
        priority = P2pTechPriority.FALLBACK,
        icon = Icons.Outlined.Bluetooth,
        discoveryMode = P2pDiscoveryMode.PeerDiscovery,
        capabilityBit = 8,
        quality = 0,
    ) {
        override fun isLocallyCapable(currentPlatform: Platform): Boolean = false
        override fun hostAffinity(localPlatform: Platform, remotePlatform: Platform): HostAffinity =
            HostAffinity.NEITHER
    }
}
