package jetzy

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeUIViewController
import jetzy.managers.LanWifiP2PM
import jetzy.managers.MpcP2PM
import jetzy.managers.P2PManager
import jetzy.managers.P2PManager.Companion.platformCallback
import jetzy.p2p.P2pPlatformCallback
import jetzy.ui.AdamScreen
import jetzy.utils.Platform
import jetzy.viewmodel.JetzyViewmodel
import platform.UIKit.UIViewController

lateinit var viewmodel: JetzyViewmodel

@Suppress("unused", "FunctionName")
@OptIn(ExperimentalComposeUiApi::class)
fun MainViewController(): UIViewController = ComposeUIViewController(
    configure = {
        parallelRendering = true

        platformCallback = object: P2pPlatformCallback {
            override fun getSuitableP2pManager(peerPlatform: Platform): P2PManager? {
                return when (peerPlatform) {
                    Platform.Android -> LanWifiP2PM()
                    Platform.IOS -> MpcP2PM()
                    else -> null
                }
            }
        }
    }
) {
    AdamScreen(
        onViewmodel = {
            viewmodel = it
        }
    )
}
