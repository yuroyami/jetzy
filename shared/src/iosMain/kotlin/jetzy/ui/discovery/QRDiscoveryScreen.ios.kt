package jetzy.ui.discovery

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import jetzy.managers.QRDiscoveryP2PM
import jetzy.p2p.P2pQrController
import kotlinx.cinterop.ExperimentalForeignApi
import platform.UIKit.UIView

var qrController: P2pQrController? = null

lateinit var cameraContainer: UIView

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun P2pQrContent(modifier: Modifier, manager: QRDiscoveryP2PM) {
    UIKitView(
        modifier = modifier,
        factory = {
            cameraContainer = UIView()
            qrController = P2pQrController()
            cameraContainer.addSubview(qrController!!.view)
            cameraContainer
        },
        update = {

        },
//        onResize = { view: UIView, rect: CValue<CGRect> ->
//            CATransaction.begin()
//            CATransaction.setValue(true, kCATransactionDisableActions)
//            view.layer.setFrame(rect)
//            qrController?.previewLayer?.setFrame(rect)
//            cameraContainer.layer.frame = rect
//            CATransaction.commit()
//        },
        onRelease = {
            /*TODO*/
        },
        properties = UIKitInteropProperties(
            isInteractive = false,
            isNativeAccessibilityEnabled = true
        )
    )
}