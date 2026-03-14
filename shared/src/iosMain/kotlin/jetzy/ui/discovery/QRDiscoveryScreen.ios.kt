package jetzy.ui.discovery

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitViewController
import jetzy.managers.QRDiscoveryP2PM
import jetzy.managers.LanWifiP2PM
import jetzy.ui.LocalViewmodel
import jetzy.uiviewcontroller.QRScannerController
import kotlinx.cinterop.ExperimentalForeignApi

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun P2pQrContent(modifier: Modifier, manager: QRDiscoveryP2PM) {
    val viewmodel = LocalViewmodel.current

    val qrController = remember {
        QRScannerController(
            onQrDetected = { qrData ->
                (manager as? LanWifiP2PM)?.establishTcpClient(qrData)
            }
        )
    }

    UIKitViewController(
        modifier = modifier,
        factory = {
            qrController
        },
        update = {},
        properties = UIKitInteropProperties(
            isInteractive = true, // camera preview needs touch passthrough
            isNativeAccessibilityEnabled = true
        )
    )
}