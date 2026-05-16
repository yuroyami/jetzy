package jetzy.utils

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import compose.icons.FontAwesomeIcons
import compose.icons.fontawesomeicons.Brands
import compose.icons.fontawesomeicons.Solid
import compose.icons.fontawesomeicons.brands.Android
import compose.icons.fontawesomeicons.brands.Apple
import compose.icons.fontawesomeicons.brands.Chrome
import compose.icons.fontawesomeicons.solid.Desktop
import kotlinx.coroutines.CoroutineDispatcher

enum class Platform(val label: String, val brandColor: Color, val icon: ImageVector, val canScanQR: Boolean) {
    Android("Android", Color(0xff32de84), FontAwesomeIcons.Brands.Android, canScanQR = true),
    IOS("iOS", Color(0xffa2aaad), FontAwesomeIcons.Brands.Apple, canScanQR = true),
    Web("Browser", Color(0xff3778bf), FontAwesomeIcons.Brands.Chrome, canScanQR = false),
    PC("PC", Color(0xfff14f21), FontAwesomeIcons.Solid.Desktop, canScanQR = false);

    /**
     * Label to show when choosing this platform as a peer.
     * To satisfy app-store guidelines we avoid naming the competing platform —
     * only the device's own platform is named explicitly; everything else is
     * shown as "Another platform".
     */
    val peerLabel: String get() = if (this == platform) label else "Another platform"

    /**
     * Icon to show when representing this platform *as a peer*. Same rationale
     * as [peerLabel]: only the device's own platform shows its real brand mark —
     * any other platform shows a neutral smartphone silhouette so we don't
     * ship competitor branding inside either store.
     */
    val peerIcon: ImageVector get() = if (this == platform) icon else Icons.Filled.Smartphone

    /** Color to tint [peerIcon] with. Real brand color for our own platform, neutral gray otherwise. */
    val peerBrandColor: Color get() = if (this == platform) brandColor else Color(0xff8e918f)
}

expect val platform: Platform

expect fun generateTimestampMillis(): Long

expect fun getDeviceName(): String

expect val PreferablyIO: CoroutineDispatcher

/**
 * Returns the number of free bytes on the volume that backs the app's
 * temporary directory. Returns `Long.MAX_VALUE` if the platform can't tell us —
 * callers should treat that as "unknown, proceed" rather than "zero, block".
 */
expect fun getAvailableStorageBytes(): Long

/** Absolute filesystem path to the app's persistent data directory (per-user, sandboxed). */
expect fun getPersistentStoragePath(): String

/**
 * Whether Wi-Fi Aware (NAN — Neighbor Awareness Networking, IEEE 802.11) is usable
 * on *this device, right now*. Distinct from compile-time support: even when both
 * the OS version and the framework are present, the underlying Wi-Fi chipset may
 * not implement NAN. Read every time it matters — `P2pTechnology.WiFiAware.isAvailable`
 * delegates here, and the transport selector uses it to silently fall back to LAN/hotspot
 * for users on un-blessed hardware or older OS releases.
 *
 * Per-platform truth table:
 *  - Android: API 26+ AND `PackageManager.FEATURE_WIFI_AWARE` declared by the device.
 *  - iOS: 26.0+ runtime (the framework class isn't present below).
 *  - macOS / Windows / Linux / Web: not supported (Apple hasn't shipped it on macOS;
 *    Microsoft has no roadmap; Linux support via wpa_supplicant exists but is too
 *    hardware-dependent to claim in this shape).
 */
expect fun isWifiAwareSupported(): Boolean

/** Service name advertised by Jetzy on Wi-Fi Aware NAN clusters. Both sides publish *and* subscribe to it. */
const val JETZY_WIFI_AWARE_SERVICE = "jetzy"

/**
 * mDNS / Bonjour service type advertised on the LAN. The leading underscore and
 * `._tcp.local.` suffix are the standard form RFC 6763 wants; Android NsdManager
 * sometimes strips/re-adds the trailing dot, iOS's NWListener keeps it explicit,
 * jmdns normalises to one of the two — all three end up speaking the same wire.
 */
const val JETZY_MDNS_SERVICE_TYPE = "_jetzy._tcp."
const val JETZY_MDNS_SERVICE_TYPE_NO_DOT = "_jetzy._tcp"

/** RFCOMM SPP UUID for Bluetooth Classic between Jetzy peers. v4 random, stable across builds. */
const val JETZY_BLUETOOTH_SPP_UUID = "f4f8e3d1-2b8a-4d6c-9f1e-7a8b3c4d5e6f"
