package jetzy.web

import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.readByte
import io.ktor.utils.io.writeFully
import jetzy.managers.JetzyProtocol

/** What a freshly-accepted socket is speaking, decided purely from the bytes it leads with. */
enum class WireKind { JETZY, HTTP, UNKNOWN }

/** A file the web receiver can hand to a browser. */
data class WebShareItem(val name: String, val sizeBytes: Long, val mimeType: String? = null)

/**
 * Where the served bytes come from. Abstract on purpose so this module stays independent of
 * JetzyElement / the transfer engine — the sender adapts its staged files into this at the
 * integration seam.
 */
interface WebShareSource {
    val deviceName: String
    val items: List<WebShareItem>
    /** A streaming reader for item [index], or null if it can't be opened. */
    suspend fun open(index: Int): ByteReadChannel?
}

/**
 * **App-less receive.** The host already binds one TCP socket for the native protocol. This lets
 * that *same* socket also answer a plain web browser: peek the first four bytes — the Jetzy magic
 * `0x4A45545A` ("JETZ") means the native app; an HTTP method line (`GET `, `HEAD`, …) means a
 * browser with no app installed. One port, two worlds, auto-detected — so a phone can send to a
 * laptop that only has Chrome. The pure pieces here (classification, request parsing, HTML/headers)
 * are unit-tested; [serve] is the IO loop wired in at the accept site.
 */
object WebReceiver {

    /** 4-byte ASCII prefixes of the HTTP methods a browser might lead with. */
    private val HTTP_PREFIXES = setOf("GET ", "POST", "HEAD", "PUT ", "DELE", "OPTI", "PATC")

    /**
     * Classify a connection from the 4 bytes it opens with. This is the whole trick: the preamble
     * is self-describing, so no second port or out-of-band signal is needed to route app vs browser.
     */
    fun classify(firstFour: ByteArray): WireKind {
        if (firstFour.size < 4) return WireKind.UNKNOWN
        val asInt = ((firstFour[0].toInt() and 0xFF) shl 24) or
            ((firstFour[1].toInt() and 0xFF) shl 16) or
            ((firstFour[2].toInt() and 0xFF) shl 8) or
            (firstFour[3].toInt() and 0xFF)
        if (asInt == JetzyProtocol.MAGIC) return WireKind.JETZY
        // Only the leading 4 bytes describe the method — the caller may hand us more.
        return if (firstFour.decodeToString(0, 4) in HTTP_PREFIXES) WireKind.HTTP else WireKind.UNKNOWN
    }

    data class HttpRequest(val method: String, val path: String)

    /** Parse a request line like `GET /dl/2 HTTP/1.1` → (GET, /dl/2). Null if it isn't one. */
    fun parseRequestLine(line: String): HttpRequest? {
        val parts = line.trim().split(' ').filter { it.isNotEmpty() }
        if (parts.size < 2) return null
        return HttpRequest(parts[0].uppercase(), parts[1])
    }

    /** A status line + headers (CRLF-terminated, ending in a blank line). Body follows separately. */
    fun responseHead(
        status: String,
        contentType: String,
        contentLength: Long,
        extra: Map<String, String> = emptyMap(),
    ): String = buildString {
        append("HTTP/1.1 ").append(status).append("\r\n")
        append("Content-Type: ").append(contentType).append("\r\n")
        append("Content-Length: ").append(contentLength).append("\r\n")
        append("Connection: close\r\n")
        for ((k, v) in extra) append(k).append(": ").append(v).append("\r\n")
        append("\r\n")
    }

    /** The download landing page — one tap per file, no app required. Kept self-contained (inline CSS). */
    fun indexHtml(deviceName: String, items: List<WebShareItem>): String = buildString {
        append("<!doctype html><html><head><meta charset=utf-8>")
        append("<meta name=viewport content=\"width=device-width,initial-scale=1\">")
        append("<title>Jetzy · ").append(deviceName.htmlEscape()).append("</title>")
        append("<style>")
        append("body{font-family:-apple-system,Segoe UI,Roboto,sans-serif;margin:0;background:#0b0b10;color:#f5f5f7}")
        append(".wrap{max-width:560px;margin:0 auto;padding:28px 18px}")
        append("h1{font-size:20px;font-weight:600;margin:0 0 4px}.sub{color:#9a9aa6;font-size:13px;margin:0 0 22px}")
        append("a.row{display:flex;justify-content:space-between;align-items:center;gap:12px;text-decoration:none;")
        append("color:inherit;background:#16161f;border:1px solid #23232e;border-radius:14px;padding:14px 16px;margin:8px 0}")
        append("a.row:active{background:#1d1d29}.nm{font-size:15px;word-break:break-all}.sz{color:#9a9aa6;font-size:13px;white-space:nowrap}")
        append(".dl{font-size:13px;color:#7c5cff;font-weight:600}")
        append("</style></head><body><div class=wrap>")
        append("<h1>").append(deviceName.htmlEscape()).append(" is sharing</h1>")
        if (items.isEmpty()) {
            append("<p class=sub>Nothing staged yet.</p>")
        } else {
            append("<p class=sub>").append(items.size).append(" file")
            append(if (items.size == 1) "" else "s").append(" · tap to download</p>")
            items.forEachIndexed { i, it ->
                append("<a class=row href=\"/dl/").append(i).append("\">")
                append("<span class=nm>").append(it.name.htmlEscape()).append("</span>")
                append("<span class=sz>").append(humanSize(it.sizeBytes)).append(" <span class=dl>↓</span></span>")
                append("</a>")
            }
        }
        append("</div></body></html>")
    }

    /**
     * Strip the characters that could break out of a header value — the quote that delimits the
     * filename and, critically, CR/LF, which would otherwise let a crafted filename inject
     * arbitrary response headers (HTTP response splitting).
     */
    fun sanitizeFilename(name: String): String =
        name.replace("\"", "").replace("\r", "").replace("\n", "")

    fun humanSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = listOf("KB", "MB", "GB", "TB")
        var v = bytes.toDouble() / 1024
        var u = 0
        while (v >= 1024 && u < units.size - 1) { v /= 1024; u++ }
        val rounded = (v * 10).toLong() / 10.0
        return "$rounded ${units[u]}"
    }

    private fun String.htmlEscape(): String =
        replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /**
     * Serve one HTTP request on an already-classified browser connection, then return (Connection:
     * close). [leadBytes] are the 4 bytes already peeked for classification — they belong to the
     * request line, so we resume reading from there. Wired in at the accept site; exercised
     * end-to-end on-device.
     */
    suspend fun serve(
        input: ByteReadChannel,
        output: ByteWriteChannel,
        source: WebShareSource,
        leadBytes: ByteArray,
    ) {
        val requestLine = readLine(input, leadBytes)
        val req = parseRequestLine(requestLine) ?: return respondText(output, "400 Bad Request", "bad request")
        // Drain the rest of the request headers (until the blank line) so the socket is clean.
        while (true) { val l = readLine(input, ByteArray(0)); if (l.isEmpty()) break }

        when {
            req.method != "GET" && req.method != "HEAD" ->
                respondText(output, "405 Method Not Allowed", "method not allowed")
            req.path == "/" || req.path.isEmpty() -> {
                val html = indexHtml(source.deviceName, source.items)
                val bytes = html.encodeToByteArray()
                output.writeFully(responseHead("200 OK", "text/html; charset=utf-8", bytes.size.toLong()).encodeToByteArray())
                if (req.method == "GET") output.writeFully(bytes)
                output.flush()
            }
            req.path.startsWith("/dl/") -> {
                val idx = req.path.removePrefix("/dl/").toIntOrNull()
                val item = idx?.let { source.items.getOrNull(it) }
                if (idx == null || item == null) return respondText(output, "404 Not Found", "no such file")
                output.writeFully(
                    responseHead(
                        "200 OK",
                        item.mimeType ?: "application/octet-stream",
                        item.sizeBytes,
                        mapOf("Content-Disposition" to "attachment; filename=\"${sanitizeFilename(item.name)}\""),
                    ).encodeToByteArray(),
                )
                if (req.method == "GET") {
                    val src = source.open(idx)
                    if (src != null) {
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = src.readAvailable(buf, 0, buf.size)
                            if (n <= 0) break
                            output.writeFully(buf, 0, n)
                        }
                    }
                }
                output.flush()
            }
            else -> respondText(output, "404 Not Found", "not found")
        }
    }

    private suspend fun respondText(output: ByteWriteChannel, status: String, body: String) {
        val b = body.encodeToByteArray()
        output.writeFully(responseHead(status, "text/plain; charset=utf-8", b.size.toLong()).encodeToByteArray())
        output.writeFully(b)
        output.flush()
    }

    /** Read one CRLF/LF-terminated line, seeding from [lead] (bytes already consumed for classify). */
    private suspend fun readLine(input: ByteReadChannel, lead: ByteArray): String {
        val sb = StringBuilder()
        for (b in lead) if (b != '\r'.code.toByte() && b != '\n'.code.toByte()) sb.append(b.toInt().toChar())
        // If the lead already contained the line terminator we'd miss it, but a 4-byte method
        // prefix never reaches end-of-line, so reading on is safe here.
        while (true) {
            val c = input.readByte()
            if (c == '\n'.code.toByte()) break
            if (c != '\r'.code.toByte()) sb.append(c.toInt().toChar())
        }
        return sb.toString()
    }
}
