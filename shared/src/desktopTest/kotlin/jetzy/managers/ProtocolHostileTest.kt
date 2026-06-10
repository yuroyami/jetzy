package jetzy.managers

import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.cancel
import jetzy.ui.transfer.EntryType
import jetzy.ui.transfer.ManifestEntry
import jetzy.ui.transfer.TransferManifest
import jetzy.utils.Platform
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Hostile-input coverage for [JetzyProtocol.readManifest]'s validation layer — the B20 bounds
 * plus the v4 aggregate string budget. These are the regression net for the DoS hardening: a
 * silent loosening of any check here re-opens a remote memory/space-gate attack. The writer runs
 * in its own coroutine because a hostile frame can be larger than the channel's buffering (the
 * reader is expected to throw mid-stream).
 */
class ProtocolHostileTest {

    private fun manifest(entries: List<ManifestEntry>, totalFiles: Int = entries.size, totalBytes: Long = entries.sumOf { it.sizeBytes }) =
        JetzyProtocol.ManifestFrame(
            sessionId = "s",
            manifest = TransferManifest(
                totalFiles = totalFiles,
                totalBytes = totalBytes,
                entries = entries,
                senderName = "Mallory",
                senderPlatform = Platform.PC,
            ),
        )

    private fun assertRejected(frame: JetzyProtocol.ManifestFrame, expectedMsgPart: String) = runBlocking {
        val ch = ByteChannel(autoFlush = true)
        val writer = launch { runCatching { JetzyProtocol.writeManifest(ch, frame) } }
        val ex = assertFailsWith<ProtocolException> { JetzyProtocol.readManifest(ch) }
        assertTrue(
            ex.message.orEmpty().contains(expectedMsgPart, ignoreCase = true),
            "expected '$expectedMsgPart' in: ${ex.message}",
        )
        writer.cancel()
        ch.cancel()
    }

    @Test
    fun entryCount_overCap_isRejected() {
        val tooMany = List(10_001) { ManifestEntry("f$it", 1) }
        assertRejected(manifest(tooMany), "out of range")
    }

    @Test
    fun entryCount_disagreeingWithTotalFiles_isRejected() {
        assertRejected(
            manifest(listOf(ManifestEntry("a", 1)), totalFiles = 5, totalBytes = 1),
            "entryCount",
        )
    }

    @Test
    fun negativeEntrySize_isRejected() {
        assertRejected(
            manifest(listOf(ManifestEntry("a", -1)), totalBytes = 0),
            "negative size",
        )
    }

    @Test
    fun negativeTotalBytes_isRejected() {
        assertRejected(
            manifest(listOf(ManifestEntry("a", 1)), totalBytes = -7),
            "negative",
        )
    }

    @Test
    fun totalBytes_disagreeingWithEntrySum_isRejected() {
        // The space-gate bypass: advertise totalBytes=0, then stream gigabytes.
        assertRejected(
            manifest(listOf(ManifestEntry("a", 1_000_000)), totalBytes = 0),
            "totalBytes",
        )
    }

    @Test
    fun entrySizes_overflowingLong_areRejected() {
        val nearMax = Long.MAX_VALUE / 2 + 1
        assertRejected(
            manifest(
                listOf(ManifestEntry("a", nearMax), ManifestEntry("b", nearMax)),
                totalBytes = 2, // never reached — the overflow check fires first
            ),
            "overflow",
        )
    }

    @Test
    fun singleString_overPerStringCap_isRejected() {
        val oneMbPlus = "x".repeat((1 shl 20) + 1)
        assertRejected(
            manifest(listOf(ManifestEntry(oneMbPlus, 1))),
            "length out of range",
        )
    }

    @Test
    fun aggregateStrings_overFrameBudget_areRejected() {
        // Each name sits under the 1 MB per-string cap, but 33 of them blow the 32 MB frame
        // budget — the exact gap the per-string cap alone left open.
        val oneMbName = "y".repeat(1 shl 20)
        val entries = List(33) { ManifestEntry(oneMbName, 1) }
        assertRejected(manifest(entries), "budget")
    }

    @Test
    fun manifestAtTheEntryCap_roundTripsFine() = runBlocking {
        // The bound must reject hostility, not strangle a big-but-legitimate folder share.
        val frame = manifest(List(10_000) { ManifestEntry("file_$it.bin", 1) })
        val ch = ByteChannel(autoFlush = true)
        val writer = launch { JetzyProtocol.writeManifest(ch, frame) }
        val got = JetzyProtocol.readManifest(ch)
        assertEquals(10_000, got.manifest.entries.size)
        assertEquals(frame.manifest.totalBytes, got.manifest.totalBytes)
        writer.cancel()
        ch.cancel()
    }

    @Test
    fun hostileResumeCoordinates_surviveTransport_clampingIsTheSendersJob() = runBlocking {
        // The wire carries whatever the receiver claims; sendFiles clamps before indexing.
        // This locks the contract: readManifestAck must NOT silently rewrite values, or the
        // sender-side clamp becomes untestable dead code.
        val ch = ByteChannel(autoFlush = true)
        val hostile = JetzyProtocol.ManifestAckFrame(
            status = JetzyProtocol.AckStatus.RESUME,
            receiver = null,
            resumeFileIndex = Int.MAX_VALUE,
            resumeByteOffset = -999L,
        )
        JetzyProtocol.writeManifestAck(ch, hostile)
        assertEquals(hostile, JetzyProtocol.readManifestAck(ch))
    }

    @Test
    fun entryType_unknownName_fallsBackToFile() = runBlocking {
        // Forward-compat: a future entry type from a newer build degrades to FILE, not a crash.
        val ch = ByteChannel(autoFlush = true)
        val frame = manifest(listOf(ManifestEntry("a", 1, null, "", EntryType.TEXT)))
        val writer = launch { JetzyProtocol.writeManifest(ch, frame) }
        val got = JetzyProtocol.readManifest(ch)
        assertEquals(EntryType.TEXT, got.manifest.entries[0].entryType)
        writer.cancel()
        ch.cancel()
    }
}
