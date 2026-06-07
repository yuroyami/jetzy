package jetzy

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeUIViewController
import jetzy.managers.LanMdnsP2PM
import jetzy.managers.LanWifiP2PM
import jetzy.managers.MpcP2PM
import jetzy.managers.P2PManager
import jetzy.managers.WifiAwareBridge
import jetzy.managers.WifiAwareP2PM
import jetzy.p2p.P2pPlatformCallback
import jetzy.ui.AdamScreen
import jetzy.utils.Platform
import jetzy.viewmodel.JetzyViewmodel
import platform.UIKit.UIViewController

lateinit var viewmodel: JetzyViewmodel

/**
 * Set from Swift (in `iOSApp.swift`) before `MainViewController()` is invoked. Holds
 * the [WifiAwareBridge] implementation — `WifiAwareBridgeImpl` — that wraps Apple's
 * Swift-only `WiFiAware` framework. Left null on iOS < 26 or when the chipset
 * doesn't support NAN, in which case the platform callback falls through to
 * the existing transports.
 */
@Suppress("unused")
var wifiAwareBridge: WifiAwareBridge? = null

@Suppress("unused", "FunctionName")
@OptIn(ExperimentalComposeUiApi::class)
fun MainViewController(): UIViewController = ComposeUIViewController(
    configure = {
        parallelRendering = true
    }
) {
    AdamScreen(
        onViewmodel = {
            viewmodel = it

            viewmodel.platformCallback = object: P2pPlatformCallback {
                override fun getSuitableP2pManager(peerPlatform: jetzy.utils.Platform): P2PManager? {
                    return when (peerPlatform) {
                        jetzy.utils.Platform.Android -> {
                            // Default to the QR/hotspot-join path. Wi-Fi Aware needs
                            // *both* sides to support it and there's no pre-pairing
                            // handshake to know that — optimistically picking it
                            // leaves the iPhone on a NAN cluster while the Android
                            // side is hosting a hotspot the user is trying to scan
                            // a QR for. LanWifi works against any Android we
                            // support. Wi-Fi Aware is available via the manual
                            // fallback ladder for users who know both sides do.
                            LanWifiP2PM()
                        }
                        Platform.IOS -> MpcP2PM()
                        Platform.PC -> LanMdnsP2PM()
                        else -> null
                    }
                }

                override fun getFallbackP2pManagers(peerPlatform: jetzy.utils.Platform): List<() -> P2PManager?> = when (peerPlatform) {
                    jetzy.utils.Platform.Android -> listOf(
                        // First rung mirrors the primary — re-scanning the QR cleanly
                        // restarts a broken hotspot session before we change strategy.
                        { LanWifiP2PM() },
                        // Same-Wi-Fi shortcut: skip the hotspot dance if both sides
                        // happen to be on the same network already.
                        { LanMdnsP2PM() },
                        // Wi-Fi Aware: opportunistic upgrade for users who know both
                        // sides support it (iOS 26+ on a NAN-capable chip + an
                        // Android with FEATURE_WIFI_AWARE). Falls back to LanWifi
                        // when the bridge isn't available.
                        { wifiAwareBridge?.let { WifiAwareP2PM.create(it) } ?: LanWifiP2PM() },
                    )
                    Platform.IOS -> listOf(
                        { MpcP2PM() },
                        { LanMdnsP2PM() },
                    )
                    Platform.PC -> listOf(
                        { LanMdnsP2PM() },
                        { LanWifiP2PM() },
                    )
                    else -> emptyList()
                }

                override fun getManagerForTechnology(technology: jetzy.p2p.P2pTechnology, role: jetzy.p2p.Role): P2PManager? =
                    when (technology) {
                        jetzy.p2p.P2pTechnology.MultipeerConnectivity -> MpcP2PM()
                        jetzy.p2p.P2pTechnology.LocalNetworkMdns -> LanMdnsP2PM()
                        jetzy.p2p.P2pTechnology.HotspotLAN -> LanWifiP2PM() // iOS joins the AP
                        jetzy.p2p.P2pTechnology.WiFiAware -> wifiAwareBridge?.let { WifiAwareP2PM.create(it) }
                        else -> null
                    }
            }
        }
    )
}
