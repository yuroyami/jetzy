package jetzy.models

/**
 * Payload encoded in the QR shown by the sender and scanned by the receiver.
 *
 * Wire form: `SSID:PASSWORD:IP:PORT:DEVICE_NAME[:SESSION_ID[:CAPS_HEX]]` — seven fields in v3,
 * six in v2 (added session id), five in legacy v1. Older receivers ignore unknown trailing
 * fields, so the format stays forward-compatible.
 *
 * Fields are joined with ':'. Because SSIDs, WPA2 passphrases, device names and IPv6 addresses
 * can themselves legally contain ':', every text field is percent-escaped (`%` → `%25`,
 * `:` → `%3A`) before joining and unescaped after splitting, so the delimiter is unambiguous.
 * Without this, a single ':' in a password or an IPv6 host shifts every later field — the
 * receiver would join the wrong network and dial a garbage IP/port. Capabilities are encoded
 * as a hex string of the bitmask defined by [jetzy.p2p.P2pTechnology.capabilityBit].
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
        append(hotspotSSID.qrEscape()); append(':')
        append(hotspotPassword.qrEscape()); append(':')
        append(ipAddress.qrEscape()); append(':')
        append(port); append(':')
        append(deviceName.qrEscape())
        // sessionId must be present (even if empty) when we want to include caps,
        // otherwise the receiver would parse caps as the session id.
        if (sessionId != null || capabilities != 0L) {
            append(':'); append(sessionId.orEmpty().qrEscape())
        }
        if (capabilities != 0L) {
            append(':'); append(capabilities.toString(16))
        }
    }

    companion object {
        fun String.toQRData(): QRData {
            val data = split(":", limit = 7)
            return QRData(
                hotspotSSID = data.getOrNull(0).orEmpty().qrUnescape(),
                hotspotPassword = data.getOrNull(1).orEmpty().qrUnescape(),
                ipAddress = data.getOrNull(2).orEmpty().qrUnescape(),
                port = data.getOrNull(3)?.toIntOrNull() ?: 80,
                deviceName = data.getOrNull(4).orEmpty().qrUnescape(),
                sessionId = data.getOrNull(5)?.takeIf { it.isNotEmpty() }?.qrUnescape(),
                capabilities = data.getOrNull(6)?.toLongOrNull(16) ?: 0L,
            )
        }
    }
}

/**
 * Percent-escapes the only two characters that would otherwise break the colon-delimited QR
 * payload: '%' (the escape marker itself) and ':' (the field separator). Everything else —
 * spaces, non-ASCII, etc. — passes through untouched, since only the delimiter must be
 * unambiguous. Numeric fields (port, caps) never contain these characters, so they are joined
 * raw and need no escaping.
 */
private fun String.qrEscape(): String {
    if ('%' !in this && ':' !in this) return this
    return buildString {
        for (c in this@qrEscape) {
            when (c) {
                '%' -> append("%25")
                ':' -> append("%3A")
                else -> append(c)
            }
        }
    }
}

/**
 * Reverses [qrEscape]: decodes any `%XX` hex pair back to its character. A stray '%' that
 * isn't followed by two hex digits is left as-is, so malformed input degrades gracefully
 * rather than throwing.
 */
private fun String.qrUnescape(): String {
    if ('%' !in this) return this
    val s = this
    return buildString {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length) {
                val hi = s[i + 1].digitToIntOrNull(16)
                val lo = s[i + 2].digitToIntOrNull(16)
                if (hi != null && lo != null) {
                    append(((hi shl 4) or lo).toChar())
                    i += 3
                    continue
                }
            }
            append(c)
            i++
        }
    }
}
