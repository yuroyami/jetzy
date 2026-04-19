package jetzy

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeUIViewController
import jetzy.managers.LanWifiP2PM
import jetzy.managers.MpcP2PM
import jetzy.managers.P2PManager
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
    }
) {
    AdamScreen(
        onViewmodel = {
            viewmodel = it

            viewmodel.platformCallback = object: P2pPlatformCallback {
                override fun getSuitableP2pManager(peerPlatform: jetzy.utils.Platform): P2PManager? {
                    return when (peerPlatform) {
                        jetzy.utils.Platform.Android -> LanWifiP2PM()
                        Platform.IOS -> MpcP2PM()
                        else -> null
                    }
                }
            }
        }
    )
}
