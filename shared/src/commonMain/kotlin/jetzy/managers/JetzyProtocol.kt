package jetzy.managers

import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readByte
import io.ktor.utils.io.readFully
import io.ktor.utils.io.readInt
import io.ktor.utils.io.readLong
import io.ktor.utils.io.writeByte
import io.ktor.utils.io.writeFully
import io.ktor.utils.io.writeInt
import io.ktor.utils.io.writeLong
import jetzy.ui.transfer.EntryType
import jetzy.ui.transfer.ManifestEntry
import jetzy.ui.transfer.PeerInfo
import jetzy.ui.transfer.TransferManifest
import jetzy.utils.Platform

/**
 * Jetzy wire protocol v2.
 *
 * All control messages are length-prefixed; file payloads are raw bytes whose
 * length is known from the manifest. Both ends perform a magic/version
 * handshake before anything else so we can evolve the format safely.
 *
 * Handshake:
 *   sender → receiver: MAGIC | VERSION | HelloFrame
 *   receiver → sender: MAGIC | VERSION | HelloFrame
 *
 * After handshake:
 *   sender → receiver: ManifestFrame
 *   receiver → sender: ManifestAckFrame
 *   for each file in manifest (starting at ack.resumeFileIndex):
 *     sender → receiver: raw file bytes (seeking to ack.resumeByteOffset for the resume file)
 *     sender → receiver: CRC32 (int)
 *     receiver → sender: FileAck
 *   sender → receiver: DoneFrame
 */
object JetzyProtocol {
    /** "JETZ" in ASCII — all four bytes sent on connection before anything else. */
    const val MAGIC: Int = 0x4A45545A
    const val VERSION: Int = 2

    enum class AckStatus(val code: Byte) {
        OK(0),
        INSUFFICIENT_SPACE(1),
        REJECTED(2),
        RESUME(3);

        companion object {
            fun fromCode(c: Byte): AckStatus =
                entries.firstOrNull { it.code == c } ?: REJECTED
        }
    }

    enum class FileAck(val code: Byte) {
        OK(0),
        CRC_MISMATCH(1),
        CANCELLED(2);

        companion object {
            fun fromCode(c: Byte): FileAck =
                entries.firstOrNull { it.code == c } ?: CANCELLED
        }
    }

    data class HelloFrame(
        val name: String,
        val platform: Platform,
        val capabilities: Long = 0L,
    )

    data class ManifestFrame(
        val sessionId: String,
        val manifest: TransferManifest,
    )

    data class ManifestAckFrame(
        val status: AckStatus,
        val receiver: PeerInfo?,
        val resumeFileIndex: Int = 0,
        val resumeByteOffset: Long = 0L,
        val reason: String = "",
    )

    // ── Writers ──────────────────────────────────────────────────────────────

    suspend fun writeHandshake(out: ByteWriteChannel) {
        out.writeInt(MAGIC)
        out.writeInt(VERSION)
        out.flush()
    }

    /** Returns the peer's declared version; throws on protocol mismatch. */
    suspend fun readHandshake(input: ByteReadChannel): Int {
        val m = input.readInt()
        if (m != MAGIC) throw ProtocolException("Bad magic 0x${m.toString(16)}; expected 0x${MAGIC.toString(16)}")
        val v = input.readInt()
        if (v != VERSION) throw ProtocolException("Unsupported protocol v$v; this build speaks v$VERSION")
        return v
    }

    suspend fun writeHello(out: ByteWriteChannel, hello: HelloFrame) {
        writeString(out, hello.name)
        writeString(out, hello.platform.name)
        out.writeLong(hello.capabilities)
        out.flush()
    }

    suspend fun readHello(input: ByteReadChannel): HelloFrame {
        val name = readString(input)
        val platformName = readString(input)
        val caps = input.readLong()
        val plat = runCatching { Platform.valueOf(platformName) }.getOrDefault(Platform.Android)
        return HelloFrame(name = name, platform = plat, capabilities = caps)
    }

    suspend fun writeManifest(out: ByteWriteChannel, frame: ManifestFrame) {
        writeString(out, frame.sessionId)
        val mf = frame.manifest
        out.writeInt(mf.totalFiles)
        out.writeLong(mf.totalBytes)
        writeString(out, mf.senderName)
        writeString(out, mf.senderPlatform.name)
        out.writeInt(mf.entries.size)
        for (entry in mf.entries) {
            writeString(out, entry.name)
            out.writeLong(entry.sizeBytes)
            writeString(out, entry.mimeType ?: "")
            writeString(out, entry.relativePath)
            writeString(out, entry.entryType.name)
        }
        out.flush()
    }

    suspend fun readManifest(input: ByteReadChannel): ManifestFrame {
        val sessionId = readString(input)
        val totalFiles = input.readInt()
        val totalBytes = input.readLong()
        val senderName = readString(input)
        val senderPlat = readString(input)
        val entryCount = input.readInt()
        require(entryCount == totalFiles) { "Manifest entryCount=$entryCount totalFiles=$totalFiles" }
        val entries = (0 until entryCount).map {
            val name = readString(input)
            val size = input.readLong()
            val mime = readString(input).takeIf { it.isNotEmpty() }
            val relPath = readString(input)
            val typeStr = readString(input)
            ManifestEntry(
                name = name,
                sizeBytes = size,
                mimeType = mime,
                relativePath = relPath,
                entryType = runCatching { EntryType.valueOf(typeStr) }.getOrDefault(EntryType.FILE),
            )
        }
        val manifest = TransferManifest(
            totalFiles = totalFiles,
            totalBytes = totalBytes,
            entries = entries,
            senderName = senderName,
            senderPlatform = runCatching { Platform.valueOf(senderPlat) }.getOrDefault(Platform.Android),
        )
        return ManifestFrame(sessionId = sessionId, manifest = manifest)
    }

    suspend fun writeManifestAck(out: ByteWriteChannel, ack: ManifestAckFrame) {
        out.writeByte(ack.status.code)
        val peer = ack.receiver
        val hasPeer = peer != null
        out.writeByte(if (hasPeer) 1 else 0)
        if (peer != null) {
            writeString(out, peer.name)
            writeString(out, peer.platform.name)
        }
        out.writeInt(ack.resumeFileIndex)
        out.writeLong(ack.resumeByteOffset)
        writeString(out, ack.reason)
        out.flush()
    }

    suspend fun readManifestAck(input: ByteReadChannel): ManifestAckFrame {
        val status = AckStatus.fromCode(input.readByte())
        val hasPeer = input.readByte() == 1.toByte()
        val receiver = if (hasPeer) {
            val name = readString(input)
            val platName = readString(input)
            PeerInfo(
                name = name,
                platform = runCatching { Platform.valueOf(platName) }.getOrDefault(Platform.Android),
            )
        } else null
        val resumeIdx = input.readInt()
        val resumeOff = input.readLong()
        val reason = readString(input)
        return ManifestAckFrame(
            status = status,
            receiver = receiver,
            resumeFileIndex = resumeIdx,
            resumeByteOffset = resumeOff,
            reason = reason,
        )
    }

    suspend fun writeFileAck(out: ByteWriteChannel, ack: FileAck) {
        out.writeByte(ack.code)
        out.flush()
    }

    suspend fun readFileAck(input: ByteReadChannel): FileAck = FileAck.fromCode(input.readByte())

    /** Marker at the very end of a successful transfer. */
    const val DONE_MARKER: Int = -1

    suspend fun writeDone(out: ByteWriteChannel) {
        out.writeInt(DONE_MARKER)
        out.flush()
    }

    suspend fun readDone(input: ByteReadChannel) {
        val m = input.readInt()
        if (m != DONE_MARKER) throw ProtocolException("Expected DONE marker, got $m")
    }

    // ── Low level string codec ───────────────────────────────────────────────
    private suspend fun writeString(out: ByteWriteChannel, s: String) {
        val bytes = s.encodeToByteArray()
        out.writeInt(bytes.size)
        if (bytes.isNotEmpty()) out.writeFully(bytes)
    }

    private suspend fun readString(input: ByteReadChannel): String {
        val len = input.readInt()
        if (len < 0 || len > MAX_STRING_BYTES) {
            throw ProtocolException("String length out of range: $len")
        }
        if (len == 0) return ""
        val buf = ByteArray(len)
        input.readFully(buf)
        return buf.decodeToString()
    }

    private const val MAX_STRING_BYTES = 1 shl 20 // 1 MB cap — protects against corruption
}

class ProtocolException(message: String) : RuntimeException(message)
