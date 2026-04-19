package jetzy.models

/**
 * Payload encoded in the QR shown by the sender and scanned by the receiver.
 *
 * Format: `SSID:PASSWORD:IP:PORT:DEVICE_NAME[:SESSION_ID]` — six fields in v2,
 * five in legacy v1 (no session id). The receiver accepts both and only kicks
 * in "resume" UX when a session id is present.
 */
data class QRData(
    val hotspotSSID: String,
    val hotspotPassword: String,
    val ipAddress: String,
    val port: Int,
    val deviceName: String,
    /** Stable id for this transfer session. Lets a re-scanned QR pick up where the previous one left off. */
    val sessionId: String? = null,
) {
    override fun toString(): String = buildString {
        append(hotspotSSID); append(':')
        append(hotspotPassword); append(':')
        append(ipAddress); append(':')
        append(port); append(':')
        append(deviceName)
        if (sessionId != null) { append(':'); append(sessionId) }
    }

    companion object {
        fun String.toQRData(): QRData {
            val data = split(":")
            return QRData(
                hotspotSSID = data.getOrNull(0).orEmpty(),
                hotspotPassword = data.getOrNull(1).orEmpty(),
                ipAddress = data.getOrNull(2).orEmpty(),
                port = data.getOrNull(3)?.toIntOrNull() ?: 80,
                deviceName = data.getOrNull(4).orEmpty(),
                sessionId = data.getOrNull(5)?.takeIf { it.isNotEmpty() },
            )
        }
    }
}
