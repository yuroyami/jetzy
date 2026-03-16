package jetzy.managers

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.connection
import jetzy.models.QRData
import jetzy.utils.PreferablyIO
import jetzy.utils.loggy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSError
import platform.NetworkExtension.NEHotspotConfiguration
import platform.NetworkExtension.NEHotspotConfigurationManager
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration.Companion.seconds

class LanWifiP2PM : QRDiscoveryP2PM() {

    fun establishTcpClient(qrData: QRData) = p2pScope.async(PreferablyIO) {
        try {
            loggy("Establishing WiFi Connection: $qrData")

            connectToWifi(qrData)
            delay(3.seconds)

            while (connection == null) {
                loggy("Trying to connect to '${qrData.ipAddress}' on port '${qrData.port}'")
                runCatching {
                    val ktor = aSocket(SelectorManager(Dispatchers.IO))
                        .tcp()
                        .connect(qrData.ipAddress, qrData.port)

                    connection = ktor.connection()
                }.onFailure { e ->
                    loggy("Connection attempt failed: ${e.message}, retrying in 2s...")
                    delay(2.seconds)
                }
            }

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
}