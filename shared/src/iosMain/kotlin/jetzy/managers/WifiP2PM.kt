package jetzy.managers

import androidx.lifecycle.viewModelScope
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.Connection
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.connection
import jetzy.models.JetzyElement
import jetzy.models.QRData
import jetzy.utils.PreferablyIO
import jetzy.utils.loggy
import jetzy.viewmodel
import jetzy.viewmodel.JetzyViewmodel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSError
import platform.NetworkExtension.NEHotspotConfiguration
import platform.NetworkExtension.NEHotspotConfigurationManager
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration.Companion.seconds

class WifiP2PM(viewmodel: JetzyViewmodel) : QRDiscoveryP2PM() {

    override val coroutineScope: CoroutineScope = viewmodel.viewModelScope

    private var socket: Socket? = null
    private var socketJob: Job? = null
    private var ktorConnection: Connection? = null

    fun establishTcpClient(qrData: QRData) = coroutineScope.async(PreferablyIO) {
        try {
            loggy("Establishing WiFi Connection: $qrData")

            connectToWifi(qrData)

            loggy("Trying to connect to '${qrData.ipAddress}' on port '${qrData.port}")

            delay(2.seconds)

            val ktor = aSocket(SelectorManager(Dispatchers.IO))
                .tcp()
                .connect(qrData.ipAddress, qrData.port)

            //Successfully connected by QR code
            ktorConnection = ktor.connection()

            viewmodel.snacky("Successfully connected via QR!")

            loggy("Successfully connected!")
        } catch (e: Exception) {
            loggy(e.stackTraceToString())
            null
        }
    }

    suspend fun connectToWifi(qrData: QRData): Unit = suspendCancellableCoroutine { cont ->
        val config = NEHotspotConfiguration(sSID = qrData.hotspotSSID, passphrase = qrData.hotspotPassword, isWEP = false)
        config.joinOnce = true // disconnects when app goes to background, cleaner for P2P

        NEHotspotConfigurationManager.sharedManager.applyConfiguration(config) { error: NSError? ->
            when {
                error == null -> cont.resume(Unit)
                // error code 13 means "already connected to this network" which is fine
                error.code == 13L -> cont.resume(Unit)
                else -> cont.resumeWithException(Exception("WiFi connection failed: ${error.localizedDescription}"))
            }
        }

        cont.invokeOnCancellation {
            NEHotspotConfigurationManager.sharedManager.removeConfigurationForSSID(qrData.hotspotSSID)
        }
    }

    override suspend fun cleanup() {
    }

    override suspend fun sendFiles(files: List<JetzyElement>): Result<Unit> {
        TODO("Not yet implemented")
    }

    override suspend fun receiveFiles(outputDir: JetzyElement): Result<List<JetzyElement>> {
        TODO("Not yet implemented")
    }

    override suspend fun disconnect() {
        TODO("Not yet implemented")
    }
}