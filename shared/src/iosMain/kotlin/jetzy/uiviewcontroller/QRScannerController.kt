package jetzy.uiviewcontroller

import jetzy.models.QRData
import jetzy.models.QRData.Companion.toQRData
import jetzy.utils.loggy
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

class QRScannerController(
    private val onQrDetected: (QRData) -> Unit
) : UIViewController(null, null), AVCaptureMetadataOutputObjectsDelegateProtocol {

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

            qrCaptureDevice = AVCaptureDevice.Companion.defaultDeviceWithMediaType(AVMediaTypeVideo) ?: failed()

            qrInput = AVCaptureDeviceInput.Companion.deviceInputWithDevice(qrCaptureDevice ?: failed(), null) ?: failed()

            if (captureSession?.canAddInput(qrInput ?: failed()) == true) {
                captureSession?.addInput(qrInput ?: failed())
            } else failed()

            qrMetadataOutput = AVCaptureMetadataOutput.Companion.new()

            if (captureSession?.canAddOutput(qrMetadataOutput ?: failed()) == true) {
                captureSession?.addOutput(qrMetadataOutput!!)
                qrMetadataOutput!!.setMetadataObjectsDelegate(
                    objectsDelegate = this,
                    queue = dispatch_get_main_queue()
                )
                qrMetadataOutput!!.setMetadataObjectTypes(listOf(AVMetadataObjectTypeQRCode))
            } else failed()

            previewLayer = AVCaptureVideoPreviewLayer.Companion.layerWithSession(captureSession ?: failed())
            previewLayer?.frame = view.layer.bounds
            previewLayer?.videoGravity = AVLayerVideoGravityResizeAspectFill
            view.layer.insertSublayer(previewLayer ?: failed(), atIndex = 0u)

            captureSession?.startRunning()

        } catch (e: Exception) {
            loggy("QrController setup failed: ${e.stackTraceToString()}")
        }
    }

    override fun viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        // keep the preview layer filling the view as the layout changes
        previewLayer?.frame = view.layer.bounds
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

    override fun captureOutput(
        output: AVCaptureOutput,
        didOutputMetadataObjects: List<*>,
        fromConnection: AVCaptureConnection
    ) {
        captureSession?.stopRunning()

        val metadataObject = didOutputMetadataObjects.firstOrNull() as? AVMetadataMachineReadableCodeObject ?: return
        val stringValue = metadataObject.stringValue ?: return

        loggy("QR CODE DETECTED: $stringValue")

        val qrData = stringValue.toQRData()

        onQrDetected(qrData)
    }

    private fun failed(): Nothing {
        captureSession = null
        throw Exception("QrController setup step failed")
    }
}