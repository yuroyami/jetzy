package jetzy.managers

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.connection
import jetzy.models.QRData
import jetzy.utils.PreferablyIO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import platform.Foundation.NSError
import platform.NetworkExtension.NEHotspotConfiguration
import platform.NetworkExtension.NEHotspotConfigurationManager
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration.Companion.seconds

class LanWifiP2PM : P2PManager() {

    override val usesPeerDiscovery: Boolean = false

    private var activeJob: Job? = null
    private var lastQrData: QRData? = null

    fun establishTcpClient(qrData: QRData): Job {
        activeJob?.cancel()
        lastQrData = qrData
        val job = p2pScope.launch(PreferablyIO) {
            connectAttempt(qrData)
        }
        activeJob = job
        return job
    }

    /** User-tappable retry after a failed connection attempt; reuses the last scanned QR. */
    fun retryConnect(): Boolean {
        val qr = lastQrData ?: return false
        activeJob?.cancel()
        activeJob = p2pScope.launch(PreferablyIO) {
            diag("user-triggered retry")
            connectAttempt(qr)
        }
        return true
    }

    private suspend fun connectAttempt(qrData: QRData) {
        try {
            // Seed the session id from the QR so the receive loop can detect and honor
            // a resume-scenario (same QR rescanned after a drop).
            qrData.sessionId?.let { setSessionId(it) }

            diag("joining Wi-Fi '${qrData.hotspotSSID}'…")
            val joined = joinWithRetry(qrData)
            if (!joined) {
                diag("Wi-Fi join gave up after $WIFI_JOIN_ATTEMPTS attempts")
                viewmodel.snacky("Couldn't join ${qrData.hotspotSSID}. Tap Retry to try again.")
                return
            }
            diag("Wi-Fi joined; waiting for network to settle")
            delay(2.seconds)

            diag("connecting to ${qrData.ipAddress}:${qrData.port}…")
            val connected = tcpConnectWithTimeout(qrData)
            if (!connected) {
                diag("TCP connect gave up after $TCP_CONNECT_ATTEMPTS attempts")
                viewmodel.snacky("Couldn't reach sender at ${qrData.ipAddress}:${qrData.port}. Tap Retry to try again.")
                return
            }
            diag("connected")
        } catch (e: Exception) {
            diag("connect attempt failed: ${e.message ?: e::class.simpleName}")
            viewmodel.snacky("Connection error: ${e.message ?: "unknown"}")
        }
    }

    /**
     * Repeatedly applies the hotspot config until iOS reports success or we exhaust attempts.
     * Each attempt is bounded by a timeout so a wedged call can't hang forever.
     */
    private suspend fun joinWithRetry(qrData: QRData): Boolean {
        repeat(WIFI_JOIN_ATTEMPTS) { attempt ->
            val ok = withTimeoutOrNull(WIFI_JOIN_TIMEOUT) {
                runCatching { connectToWifi(qrData) }.isSuccess
            }
            if (ok == true) return true
            diag("Wi-Fi join attempt ${attempt + 1}/$WIFI_JOIN_ATTEMPTS failed")
            if (attempt < WIFI_JOIN_ATTEMPTS - 1) {
                // Back off: 1s, 2s, 4s… (capped)
                val backoffSec = (1L shl attempt).coerceAtMost(4L)
                delay(backoffSec.seconds)
            }
        }
        return false
    }

    /** Once we're on the sender's Wi-Fi, dial the TCP server with bounded retries. */
    private suspend fun tcpConnectWithTimeout(qrData: QRData): Boolean {
        repeat(TCP_CONNECT_ATTEMPTS) { attempt ->
            val ok = runCatching {
                val ktor = aSocket(SelectorManager(Dispatchers.IO))
                    .tcp()
                    .connect(qrData.ipAddress, qrData.port)
                connection = ktor.connection()
            }.isSuccess
            if (ok) return true
            diag("TCP attempt ${attempt + 1}/$TCP_CONNECT_ATTEMPTS failed")
            if (attempt < TCP_CONNECT_ATTEMPTS - 1) delay(2.seconds)
        }
        return false
    }

    private suspend fun connectToWifi(qrData: QRData): Unit = suspendCancellableCoroutine { cont ->
        val config = NEHotspotConfiguration(sSID = qrData.hotspotSSID, passphrase = qrData.hotspotPassword, isWEP = false)
        config.joinOnce = true // disconnects when app goes to background, cleaner for P2P

        NEHotspotConfigurationManager.sharedManager.applyConfiguration(config) { error: NSError? ->
            when {
                error == null -> cont.resume(Unit)
                // error code 13 means "already connected to this network" which is fine
                error.code == 13L -> cont.resume(Unit)
                // user cancelled the join prompt — surface distinctly so caller can short-circuit
                error.code == 7L -> cont.resumeWithException(Exception("User cancelled Wi-Fi join"))
                else -> cont.resumeWithException(Exception("WiFi connection failed: ${error.localizedDescription}"))
            }
        }

        cont.invokeOnCancellation {
            NEHotspotConfigurationManager.sharedManager.removeConfigurationForSSID(qrData.hotspotSSID)
        }
    }

    override suspend fun cleanup() {
        activeJob?.cancel()
        activeJob = null
        lastQrData?.let { NEHotspotConfigurationManager.sharedManager.removeConfigurationForSSID(it.hotspotSSID) }
        super.cleanup()
    }

    companion object {
        private const val WIFI_JOIN_ATTEMPTS = 3
        private const val TCP_CONNECT_ATTEMPTS = 5
        private val WIFI_JOIN_TIMEOUT = 20.seconds
    }
}
