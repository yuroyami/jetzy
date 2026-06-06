package jetzy.web

import jetzy.managers.JetzyProtocol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The one-socket multiplexer + HTTP surface for app-less receive. The IO loop ([WebReceiver.serve])
 * is exercised on-device; these pin the pure parts that decide app-vs-browser and frame the response.
 */
class WebReceiverTest {

    private fun magicBytes(): ByteArray = byteArrayOf(
        (JetzyProtocol.MAGIC ushr 24).toByte(),
        (JetzyProtocol.MAGIC ushr 16).toByte(),
        (JetzyProtocol.MAGIC ushr 8).toByte(),
        JetzyProtocol.MAGIC.toByte(),
    )

    @Test
    fun classify_jetzyMagic_isNative() {
        assertEquals(WireKind.JETZY, WebReceiver.classify(magicBytes()))
    }

    @Test
    fun classify_httpMethods_areHttp() {
        assertEquals(WireKind.HTTP, WebReceiver.classify("GET /".encodeToByteArray()))
        assertEquals(WireKind.HTTP, WebReceiver.classify("HEAD".encodeToByteArray()))
        assertEquals(WireKind.HTTP, WebReceiver.classify("POST".encodeToByteArray()))
    }

    @Test
    fun classify_garbageOrShort_isUnknown() {
        assertEquals(WireKind.UNKNOWN, WebReceiver.classify(byteArrayOf(1, 2, 3, 4)))
        assertEquals(WireKind.UNKNOWN, WebReceiver.classify(byteArrayOf(0x4A, 0x45))) // too short
    }

    @Test
    fun magicAndGet_areNeverConfused() {
        // The whole trick only works if these two preambles are disjoint.
        assertTrue(WebReceiver.classify(magicBytes()) != WebReceiver.classify("GET ".encodeToByteArray()))
    }

    @Test
    fun parseRequestLine_extractsMethodAndPath() {
        assertEquals(WebReceiver.HttpRequest("GET", "/dl/2"), WebReceiver.parseRequestLine("GET /dl/2 HTTP/1.1"))
        assertEquals(WebReceiver.HttpRequest("GET", "/"), WebReceiver.parseRequestLine("get / HTTP/1.0"))
        assertNull(WebReceiver.parseRequestLine("garbage"))
    }

    @Test
    fun responseHead_isWellFormedAndEndsBlankLine() {
        val head = WebReceiver.responseHead("200 OK", "text/html", 12, mapOf("X-A" to "b"))
        assertTrue(head.startsWith("HTTP/1.1 200 OK\r\n"))
        assertTrue("Content-Length: 12\r\n" in head)
        assertTrue("Connection: close\r\n" in head)
        assertTrue("X-A: b\r\n" in head)
        assertTrue(head.endsWith("\r\n\r\n"))
    }

    @Test
    fun indexHtml_listsItemsWithDownloadLinks_andEscapes() {
        val html = WebReceiver.indexHtml("Alice's PC", listOf(WebShareItem("a<b>.png", 2048)))
        assertTrue("/dl/0" in html)
        assertTrue("a&lt;b&gt;.png" in html)          // name escaped
        assertTrue("2.0 KB" in html)
        assertTrue(html.startsWith("<!doctype html>"))
    }

    @Test
    fun sanitizeFilename_stripsCrlfAndQuotes_preventingHeaderInjection() {
        val evil = "a\"b\r\nX-Injected: 1\r\n.png"
        val safe = WebReceiver.sanitizeFilename(evil)
        assertTrue('\r' !in safe && '\n' !in safe && '"' !in safe)
        assertEquals("abX-Injected: 1.png", safe)
    }

    @Test
    fun humanSize_isReadable() {
        assertEquals("512 B", WebReceiver.humanSize(512))
        assertEquals("1.0 KB", WebReceiver.humanSize(1024))
        assertEquals("1.5 KB", WebReceiver.humanSize(1536))
    }
}
