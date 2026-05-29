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
import kotlinx.coroutines.flow.MutableStateFlow
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
    private var selectorManager: SelectorManager? = null

    private fun selector(): SelectorManager =
        selectorManager ?: SelectorManager(PreferablyIO).also { selectorManager = it }

    /**
     * Non-null when [joinWithRetry] gave up because iOS associated with a
     * *different* network than the one in the QR — i.e. the auto-join policy
     * raced our join and won. Carries the requested SSID so the UI can name it
     * in the recovery dialog. Cleared on [retryConnect] and after a successful
     * join. Stays null for cellular-only users (they have nothing to race
     * against), for users with Auto-Join disabled on conflicting networks, and
     * for non-race failures like an apply-timeout or user cancellation — those
     * still go through the regular snack-based error path.
     */
    val joinRaceDetected = MutableStateFlow<String?>(null)

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
        // Clear race state so the dialog dismisses; if the race repeats, the
        // next [joinWithRetry] pass will set it again.
        joinRaceDetected.value = null
        isHandshaking.value = true  // show the overlay again for the duration of this attempt
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
                    // Clear the blocking handshake overlay so the snackbar / race-recovery
                    // dialog is actually reachable (otherwise the spinner traps the screen).
                    isHandshaking.value = false
                    // When the auto-join race lost, the UI's race-recovery dialog
                    // owns the messaging — don't double up with a snack. Only the
                    // generic non-race failure path falls through to snacky.
                    if (joinRaceDetected.value == null) {
                        viewmodel.snacky("Couldn't join ${qrData.hotspotSSID}. Tap Retry to try again.")
                    }
                    return
                }
                diag("Wi-Fi joined; waiting for network to settle")
                delay(2.seconds)
            }

            diag("connecting to ${qrData.ipAddress}:${qrData.port}…")
            val connected = tcpConnectWithTimeout(qrData)
            if (!connected) {
                diag("TCP connect gave up after $TCP_CONNECT_ATTEMPTS attempts")
                isHandshaking.value = false
                viewmodel.snacky("Couldn't reach sender at ${qrData.ipAddress}:${qrData.port}. Tap Retry to try again.")
                return
            }
            diag("connected")
        } catch (e: Exception) {
            isHandshaking.value = false
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
        // Cleared at the top of each call so a stale race-state from a prior
        // attempt doesn't keep the dialog up if the radio finally settles right.
        joinRaceDetected.value = null
        repeat(WIFI_JOIN_ATTEMPTS) { attempt ->
            val ok = withTimeoutOrNull(WIFI_JOIN_TIMEOUT) {
                runCatching {
                    connectToWifi(qrData)
                    if (!verifyJoinedExpectedSsid(qrData.hotspotSSID)) {
                        diag("apply OK but device is not on '${qrData.hotspotSSID}' — auto-join race")
                        joinRaceDetected.value = qrData.hotspotSSID
                        throw Exception("Joined wrong SSID")
                    }
                }.isSuccess
            }
            if (ok == true) {
                joinRaceDetected.value = null
                return true
            }
            diag("Wi-Fi join attempt ${attempt + 1}/$WIFI_JOIN_ATTEMPTS failed")
            // Auto-join policy is deterministic over short windows — if the
            // race lost once, retrying with the same network conditions will
            // lose again. Bail immediately so the UI can prompt the user to
            // disable Auto-Join (the one thing that actually unblocks this).
            // Non-race failures (apply-timeout, user-cancelled join) still
            // get the full retry budget.
            if (joinRaceDetected.value != null) {
                diag("race detected; skipping remaining retries — user action required")
                return false
            }
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
                val ktor = aSocket(selector())
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
        runCatching { selectorManager?.close() }
        selectorManager = null
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
