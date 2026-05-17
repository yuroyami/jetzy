package jetzy.models

/**
 * Payload encoded in the QR shown by the sender and scanned by the receiver.
 *
 * Format: `SSID:PASSWORD:IP:PORT:DEVICE_NAME[:SESSION_ID[:CAPS_HEX]]` — seven fields in v3,
 * six in v2 (added session id), five in legacy v1. Older receivers ignore unknown trailing
 * fields, so the format is forward-compatible. Capabilities are encoded as a hex string of
 * the bitmask defined by [jetzy.p2p.P2pTechnology.capabilityBit].
 */
data class QRData(
    val hotspotSSID: String,
    val hotspotPassword: String,
    val ipAddress: String,
    val port: Int,
    val deviceName: String,
    /** Stable id for this transfer session. Lets a re-scanned QR pick up where the previous one left off. */
    val sessionId: String? = null,
    /**
     * Bitmask of [jetzy.p2p.P2pTechnology] the host supports. Lets a scanner notice that
     * both sides could in principle use a faster transport (e.g. Wi-Fi Aware) than the
     * one this QR is bootstrapping, and offer to upgrade once the initial channel is up.
     * Zero means "host advertises nothing" — legacy QRs land here.
     */
    val capabilities: Long = 0L,
) {
    override fun toString(): String = buildString {
        append(hotspotSSID); append(':')
        append(hotspotPassword); append(':')
        append(ipAddress); append(':')
        append(port); append(':')
        append(deviceName)
        // sessionId must be present (even if empty) when we want to include caps,
        // otherwise the receiver would parse caps as the session id.
        if (sessionId != null || capabilities != 0L) {
            append(':'); append(sessionId.orEmpty())
        }
        if (capabilities != 0L) {
            append(':'); append(capabilities.toString(16))
        }
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
                capabilities = data.getOrNull(6)?.toLongOrNull(16) ?: 0L,
            )
        }
    }
}
