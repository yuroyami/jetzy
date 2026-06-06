package jetzy.web

import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * End-to-end exercise of [WebReceiver.serve] — the actual HTTP IO loop, not just the pure helpers —
 * driven over in-memory Ktor channels (JVM runBlocking). Proves a browser GET gets a well-formed
 * response: the index page lists files, and /dl/{i} streams the bytes with download headers.
 */
class WebReceiverServeTest {

    private fun source(vararg files: Pair<String, ByteArray>) = object : WebShareSource {
        override val deviceName = "TestPC"
        override val items = files.map { WebShareItem(it.first, it.second.size.toLong()) }
        override suspend fun open(index: Int): ByteReadChannel? {
            val bytes = files.getOrNull(index)?.second ?: return null
            val c = ByteChannel(autoFlush = true)
            c.writeFully(bytes)
            c.flushAndClose()
            return c
        }
    }

    private suspend fun runRequest(restOfRequest: String, src: WebShareSource): String {
        val input = ByteChannel(autoFlush = true)
        val output = ByteChannel(autoFlush = true)
        input.writeFully(restOfRequest.encodeToByteArray())
        input.flushAndClose()
        WebReceiver.serve(input, output, src, leadBytes = "GET ".encodeToByteArray())
        output.flushAndClose()
        val sb = StringBuilder()
        val buf = ByteArray(8192)
        while (true) {
            val n = output.readAvailable(buf, 0, buf.size)
            if (n <= 0) break
            sb.append(buf.decodeToString(0, n))
        }
        return sb.toString()
    }

    @Test
    fun getRoot_returnsIndexListingFiles() = runBlocking {
        val resp = runRequest("/ HTTP/1.1\r\nHost: x\r\n\r\n", source("hello.txt" to "hi".encodeToByteArray()))
        assertTrue(resp.startsWith("HTTP/1.1 200 OK"), "status line: ${resp.take(40)}")
        assertTrue("text/html" in resp)
        assertTrue("hello.txt" in resp)
        assertTrue("/dl/0" in resp)
    }

    @Test
    fun getDownload_streamsBytesWithAttachmentHeader() = runBlocking {
        val resp = runRequest("/dl/0 HTTP/1.1\r\nHost: x\r\n\r\n", source("a.bin" to "PAYLOAD".encodeToByteArray()))
        assertTrue(resp.startsWith("HTTP/1.1 200 OK"))
        assertTrue("Content-Disposition: attachment; filename=\"a.bin\"" in resp)
        assertTrue("Content-Length: 7" in resp)
        assertTrue(resp.endsWith("PAYLOAD"), "body should be the file bytes; got tail: ${resp.takeLast(20)}")
    }

    @Test
    fun getMissingDownload_is404() = runBlocking {
        val resp = runRequest("/dl/9 HTTP/1.1\r\n\r\n", source("only.txt" to "x".encodeToByteArray()))
        assertTrue(resp.startsWith("HTTP/1.1 404"))
    }

    @Test
    fun maliciousFilename_cannotInjectHeaders() = runBlocking {
        // A CRLF-laden filename must not split the response into extra headers. The real injection
        // vector is a CRLF *before* the fake header; the substring may still appear safely inside
        // the single-line (sanitized) filename value, which is harmless.
        val resp = runRequest("/dl/0 HTTP/1.1\r\n\r\n", source("a\r\nX-Evil: 1\r\n.txt" to "z".encodeToByteArray()))
        assertFalse("\r\nX-Evil:" in resp, "filename CRLF leaked into headers as an injected line")
        assertTrue("aX-Evil: 1.txt" in resp, "CRLF should be stripped, leaving a single-line filename")
    }
}
