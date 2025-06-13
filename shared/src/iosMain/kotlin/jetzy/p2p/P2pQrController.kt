package jetzy.p2p

import jetz.common.ui.p2pHandler
import jetz.common.utils.loggy
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFoundation.AVCaptureConnection
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVCaptureDeviceInput
import platform.AVFoundation.AVCaptureMetadataOutput
import platform.AVFoundation.AVCaptureMetadataOutputObjectsDelegateProtocol
import platform.AVFoundation.AVCaptureOutput
import platform.AVFoundation.AVCaptureSession
import platform.AVFoundation.AVCaptureVideoPreviewLayer
import platform.AVFoundation.AVLayerVideoGravityResizeAspectFill
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.AVMetadataMachineReadableCodeObject
import platform.AVFoundation.AVMetadataObjectTypeQRCode
import platform.UIKit.UIViewController
import platform.darwin.dispatch_get_main_queue

class P2pQrController : UIViewController(null, null), AVCaptureMetadataOutputObjectsDelegateProtocol {

    private var captureSession: AVCaptureSession? = null
    var previewLayer: AVCaptureVideoPreviewLayer? = null
    private var qrInput: AVCaptureDeviceInput? = null
    private var qrCaptureDevice: AVCaptureDevice? = null
    private var qrMetadataOutput: AVCaptureMetadataOutput? = null

    @OptIn(ExperimentalForeignApi::class)
    override fun viewDidLoad() {
        super.viewDidLoad()

        try {
            captureSession = AVCaptureSession()

            qrCaptureDevice = AVCaptureDevice.defaultDeviceWithMediaType(AVMediaTypeVideo) ?: failed()

            qrInput = AVCaptureDeviceInput.deviceInputWithDevice(qrCaptureDevice ?: failed(), null) ?: failed()

            if (captureSession?.canAddInput(qrInput ?: failed()) == true) {
                captureSession?.addInput(qrInput ?: failed())
            } else failed()

            qrMetadataOutput = AVCaptureMetadataOutput.new()

            if (captureSession?.canAddOutput(qrMetadataOutput ?: failed()) == true) {
                captureSession?.addOutput(qrMetadataOutput!!)
                qrMetadataOutput!!.setMetadataObjectsDelegate(objectsDelegate = this, dispatch_get_main_queue())
                //qrMetadataOutput!!.metadataObjectTypes = listOf(AVMetadataObjectTypeQRCode)
                qrMetadataOutput!!.setMetadataObjectTypes(listOf(AVMetadataObjectTypeQRCode))
            } else failed()

            previewLayer = AVCaptureVideoPreviewLayer.layerWithSession(captureSession ?: failed())
            previewLayer?.frame = cameraContainer.layer.bounds
            previewLayer?.videoGravity = AVLayerVideoGravityResizeAspectFill
            //cameraContainer.layer.addSublayer(previewLayer ?: return@UIKitView cameraContainer)
            cameraContainer.layer.insertSublayer(previewLayer ?: failed(), atIndex = 0u)

            if (captureSession?.isRunning() == false) {
                //MainScope().launch {
                captureSession?.startRunning()
                //}
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun viewWillAppear(animated: Boolean) {
        super.viewWillAppear(animated)

        if (captureSession?.isRunning() == false) {
            captureSession?.startRunning()
        }
    }

    override fun viewWillDisappear(animated: Boolean) {
        super.viewWillDisappear(animated)

        if (captureSession?.isRunning() == true) {
            captureSession?.stopRunning()
        }
    }

    private fun failed(): Nothing {
        captureSession = null
        throw Exception()
    }

    override fun captureOutput(output: AVCaptureOutput, didOutputMetadataObjects: List<*>, fromConnection: AVCaptureConnection) {
        captureSession?.stopRunning()

        val metadataObject = didOutputMetadataObjects.firstOrNull() as? AVMetadataMachineReadableCodeObject ?: return
        val stringValue = metadataObject.stringValue ?: return

        loggy("QR CODE DETECTOR: $stringValue")

        val p = stringValue.split(":")
        if (p.isEmpty()) return

        (p2pHandler as? P2pAppleHandler)?.crossPeer = p[2]
        (p2pHandler as? P2pAppleHandler)?.connectCrossPlatform(
            host = p[0],
            port = p[1].toIntOrNull() ?: return
        )
    }
}