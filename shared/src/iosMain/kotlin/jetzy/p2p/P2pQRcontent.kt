package jetz.common.p2p

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.interop.UIKitView
import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreGraphics.CGRect
import platform.QuartzCore.CATransaction
import platform.QuartzCore.kCATransactionDisableActions
import platform.UIKit.UIView

var qrController: P2pQrController? = null

lateinit var cameraContainer: UIView

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun P2pQRcontent(modifier: Modifier) {
    UIKitView(
        modifier = modifier,
        factory = {
            cameraContainer = UIView()
            qrController = P2pQrController()
            cameraContainer.addSubview(qrController!!.view)
            cameraContainer
        },
        onResize = { view: UIView, rect: CValue<CGRect> ->
            CATransaction.begin()
            CATransaction.setValue(true, kCATransactionDisableActions)
            view.layer.setFrame(rect)
            qrController?.previewLayer?.setFrame(rect)
            cameraContainer.layer.frame = rect
            CATransaction.commit()
        },
        update = {

        }
    )
}