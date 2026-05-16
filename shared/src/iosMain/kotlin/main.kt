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
                            wifiAwareBridge?.let { WifiAwareP2PM.create(it) } ?: LanWifiP2PM()
                        }
                        Platform.IOS -> MpcP2PM()
                        Platform.PC -> LanMdnsP2PM()
                        else -> null
                    }
                }

                override fun getFallbackP2pManagers(peerPlatform: jetzy.utils.Platform): List<() -> P2PManager?> = when (peerPlatform) {
                    jetzy.utils.Platform.Android -> listOf(
                        { wifiAwareBridge?.let { WifiAwareP2PM.create(it) } ?: LanWifiP2PM() },
                        { LanMdnsP2PM() },
                        { LanWifiP2PM() },
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
            }
        }
    )
}
