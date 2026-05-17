package jetzy.managers

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.connection
import jetzy.models.QRData
import jetzy.utils.PreferablyIO
import jetzy.utils.generateTimestampMillis
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
import platform.NetworkExtension.NEHotspotNetwork
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration.Companion.milliseconds
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

            if (qrData.hotspotSSID.isBlank()) {
                // PC (LanHostP2PM) emits a QR with no SSID — both devices are expected
                // to already share a LAN, so we skip the Wi-Fi join and dial directly.
                diag("no SSID in QR; assuming same-LAN, skipping Wi-Fi join")
            } else {
                diag("joining Wi-Fi '${qrData.hotspotSSID}'…")
                val joined = joinWithRetry(qrData)
                if (!joined) {
                    diag("Wi-Fi join gave up after $WIFI_JOIN_ATTEMPTS attempts")
                    viewmodel.snacky("Couldn't join ${qrData.hotspotSSID}. Tap Retry to try again.")
                    return
                }
                diag("Wi-Fi joined; waiting for network to settle")
                delay(2.seconds)
            }

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
     * Repeatedly applies the hotspot config until iOS reports success *and* the
     * device is actually associated with the requested SSID, or we exhaust attempts.
     * Each attempt is bounded by a timeout so a wedged call can't hang forever.
     *
     * The two-stage success criterion (apply + verify) is deliberate: `applyConfiguration`
     * fires its completion handler when iOS *accepts* the join, not when the radio
     * finishes associating. Between those two moments iOS's auto-join policy can
     * race the new join — if a strong known network (e.g. home Wi-Fi) is in range,
     * the device may briefly flap back to it and the apply "succeeds" while the
     * radio is actually on the wrong SSID. The TCP step would then chase an IP
     * we're not on the same subnet as and time out 20s later. Verifying via
     * [verifyJoinedExpectedSsid] catches the race in seconds so we can retry.
     */
    private suspend fun joinWithRetry(qrData: QRData): Boolean {
        repeat(WIFI_JOIN_ATTEMPTS) { attempt ->
            val ok = withTimeoutOrNull(WIFI_JOIN_TIMEOUT) {
                runCatching {
                    connectToWifi(qrData)
                    if (!verifyJoinedExpectedSsid(qrData.hotspotSSID)) {
                        diag("apply OK but device is not on '${qrData.hotspotSSID}' — auto-join race")
                        throw Exception("Joined wrong SSID")
                    }
                }.isSuccess
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

    /**
     * Polls [NEHotspotNetwork.fetchCurrentWithCompletionHandler] until the
     * device's current SSID matches [expectedSsid] or [JOIN_VERIFY_TIMEOUT]
     * elapses. The apply-configuration callback doesn't wait for association
     * to finish, so the radio may still be settling when we get here.
     *
     * Returns true once the SSID matches; false on timeout (which the caller
     * treats as a failed attempt eligible for retry).
     */
    private suspend fun verifyJoinedExpectedSsid(expectedSsid: String): Boolean {
        val deadline = generateTimestampMillis() + JOIN_VERIFY_TIMEOUT.inWholeMilliseconds
        while (generateTimestampMillis() < deadline) {
            val ssid = fetchCurrentSsid()
            if (ssid == expectedSsid) return true
            delay(JOIN_VERIFY_POLL_INTERVAL)
        }
        return false
    }

    private suspend fun fetchCurrentSsid(): String? = suspendCancellableCoroutine { cont ->
        NEHotspotNetwork.fetchCurrentWithCompletionHandler { network ->
            cont.resume(network?.SSID)
        }
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
        // Per-attempt budget for fetchCurrent to start returning the expected SSID
        // after apply. Tuned so a clean join settles inside one attempt while a
        // lost race still bails fast enough that we get all three retries inside
        // [WIFI_JOIN_TIMEOUT].
        private val JOIN_VERIFY_TIMEOUT = 6.seconds
        private val JOIN_VERIFY_POLL_INTERVAL = 400.milliseconds
    }
}
