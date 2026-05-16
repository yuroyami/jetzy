package jetzy.managers

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.connection
import jetzy.models.QRData
import jetzy.utils.PreferablyIO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

/**
 * Desktop counterpart of iOS's [jetzy.managers.LanWifiP2PM]: the PC sits on the
 * client side of the [JetzyProtocol] handshake. The QR is scanned (or its text
 * pasted) by the user via [jetzy.ui.discovery.P2pQrContent]; from there we just
 * need to dial the peer's TCP server.
 *
 * Unlike iOS we cannot join the peer's Wi-Fi programmatically — `NEHotspotConfigurationManager`
 * has no JVM equivalent. So the contract for the user is:
 *  1. If the QR carries a hotspot SSID/password (Android sender), join that
 *     network manually via the OS Wi-Fi picker, then return and confirm.
 *  2. Otherwise (PC↔PC on the same LAN), we connect straight away.
 *
 * The QR composable surfaces a prompt for case (1).
 */
class LanP2PM : P2PManager() {

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

    /** Retry from the QR composable after a failed dial; reuses the last scanned QR. */
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
            qrData.sessionId?.let { setSessionId(it) }

            diag("connecting to ${qrData.ipAddress}:${qrData.port}…")
            if (!tcpConnectWithRetry(qrData)) {
                diag("TCP connect gave up after $TCP_CONNECT_ATTEMPTS attempts")
                viewmodel.snacky(
                    "Couldn't reach sender at ${qrData.ipAddress}:${qrData.port}. " +
                            "Are you joined to ${qrData.hotspotSSID.ifBlank { "the right Wi-Fi" }}?"
                )
                return
            }
            diag("connected")
        } catch (e: Exception) {
            diag("connect attempt failed: ${e.message ?: e::class.simpleName}")
            viewmodel.snacky("Connection error: ${e.message ?: "unknown"}")
        }
    }

    private suspend fun tcpConnectWithRetry(qrData: QRData): Boolean {
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

    override suspend fun cleanup() {
        activeJob?.cancel()
        activeJob = null
        super.cleanup()
    }

    companion object {
        private const val TCP_CONNECT_ATTEMPTS = 5
    }
}
