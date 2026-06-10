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
import platform.UIKit.UIApplication
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
                // mDNS is the platform-agnostic bootstrap — it finds any Jetzy peer on the shared
                // LAN regardless of OS, so we no longer ask the user to guess the peer's platform.
                override fun getDefaultP2pManager(): P2PManager? = LanMdnsP2PM()

                /**
                 * Per-host fallback ladder (peer-platform-agnostic). Best→worst: same-LAN mDNS,
                 * then MultipeerConnectivity for an iOS↔iOS pair with no infrastructure, then
                 * joining an Android-hosted hotspot via QR, then Wi-Fi Aware on iOS 26+ NAN chips.
                 */
                override fun getDefaultFallbackManagers(): List<() -> P2PManager?> = listOf(
                    { LanMdnsP2PM() },                                                     // same Wi-Fi, any OS
                    { MpcP2PM() },                                                          // iOS↔iOS, no infra
                    { LanWifiP2PM() },                                                      // join Android AP via QR
                    { wifiAwareBridge?.let { WifiAwareP2PM.create(it) } ?: LanWifiP2PM() }, // iOS 26 NAN
                )

                override fun getManagerForTechnology(technology: jetzy.p2p.P2pTechnology, role: jetzy.p2p.Role): P2PManager? =
                    when (technology) {
                        jetzy.p2p.P2pTechnology.MultipeerConnectivity -> MpcP2PM()
                        jetzy.p2p.P2pTechnology.LocalNetworkMdns -> LanMdnsP2PM()
                        jetzy.p2p.P2pTechnology.HotspotLAN -> LanWifiP2PM() // iOS joins the AP
                        jetzy.p2p.P2pTechnology.WiFiAware -> wifiAwareBridge?.let { WifiAwareP2PM.create(it) }
                        else -> null
                    }

                // iOS has no foreground service; what these hooks scope here is the idle timer.
                // The old global isIdleTimerDisabled=true in iOSApp.swift meant the phone never
                // auto-locked even while idling on the main menu — keep the screen awake only
                // from proceed (discovery) until cleanup.
                override fun startBackgroundService() {
                    UIApplication.sharedApplication.idleTimerDisabled = true
                }

                override fun stopBackgroundService() {
                    UIApplication.sharedApplication.idleTimerDisabled = false
                }
            }
        }
    )
}
