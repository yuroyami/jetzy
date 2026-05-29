package jetzy.models

import jetzy.models.QRData.Companion.toQRData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Regression tests for CRIT-2: the QR payload used to be a raw `split(":")`, so any ':' in a
 * password / device name / IPv6 host shifted every later field. These pin down that every field
 * now round-trips through [QRData.toString] → [toQRData] regardless of its contents.
 */
class QRDataTest {

    private fun assertRoundTrips(data: QRData) {
        val decoded = data.toString().toQRData()
        assertEquals(data.hotspotSSID, decoded.hotspotSSID, "SSID")
        assertEquals(data.hotspotPassword, decoded.hotspotPassword, "password")
        assertEquals(data.ipAddress, decoded.ipAddress, "ip")
        assertEquals(data.port, decoded.port, "port")
        assertEquals(data.deviceName, decoded.deviceName, "deviceName")
        assertEquals(data.sessionId, decoded.sessionId, "sessionId")
        assertEquals(data.capabilities, decoded.capabilities, "capabilities")
    }

    @Test
    fun plainFieldsRoundTrip() {
        assertRoundTrips(
            QRData("AndroidShare_1234", "abcd1234", "192.168.43.1", 41234, "Pixel 7")
        )
    }

    @Test
    fun colonsInPasswordRoundTrip() {
        // WPA2 passphrases legally contain ':' — this used to corrupt ip/port/name.
        assertRoundTrips(
            QRData("Net", "a:b:c:d:e", "192.168.43.1", 41234, "Pixel 7", sessionId = "s1", capabilities = 0x47)
        )
    }

    @Test
    fun colonsAndSpacesInDeviceNameRoundTrip() {
        assertRoundTrips(
            QRData("Net", "pw", "192.168.43.1", 41234, "John's iPhone: work", sessionId = "abc", capabilities = 0x10)
        )
    }

    @Test
    fun ipv6WithZoneRoundTrips() {
        // IPv6 is full of ':' and link-local carries a '%scope' — both special chars.
        assertRoundTrips(
            QRData("Net", "pw", "fe80::1%en0", 41234, "Mac", sessionId = "s", capabilities = 0xFF)
        )
    }

    @Test
    fun literalPercentRoundTrips() {
        // A field that itself contains a '%XX'-looking sequence must survive (the '%' is escaped).
        assertRoundTrips(
            QRData("Net", "100%pass%3Aword", "10.0.0.5", 8080, "x%y")
        )
    }

    @Test
    fun capsWithoutSessionIdRoundTrips() {
        // toString writes an empty session field so caps don't get mis-parsed as the session id.
        val decoded = QRData("Net", "pw", "10.0.0.5", 80, "Dev", sessionId = null, capabilities = 0x40).toString().toQRData()
        assertNull(decoded.sessionId)
        assertEquals(0x40L, decoded.capabilities)
    }

    @Test
    fun legacyFiveFieldQrStillParses() {
        // Older v1 QR (no session/caps), colon-free fields — must still decode.
        val decoded = "MySSID:mypass:192.168.1.5:5000:OldPhone".toQRData()
        assertEquals("MySSID", decoded.hotspotSSID)
        assertEquals("mypass", decoded.hotspotPassword)
        assertEquals("192.168.1.5", decoded.ipAddress)
        assertEquals(5000, decoded.port)
        assertEquals("OldPhone", decoded.deviceName)
        assertNull(decoded.sessionId)
        assertEquals(0L, decoded.capabilities)
    }
}
