package jetzy.managers

import io.ktor.utils.io.ByteChannel
import jetzy.ui.transfer.EntryType
import jetzy.ui.transfer.ManifestEntry
import jetzy.ui.transfer.PeerInfo
import jetzy.ui.transfer.TransferManifest
import jetzy.utils.Platform
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * End-to-end wire round-trips for [JetzyProtocol], driven over an in-memory Ktor ByteChannel (JVM
 * only — runBlocking). Locks the on-wire contract: every field a writer emits must be read back
 * identically and IN THE SAME ORDER. This is the regression net for the v3 HELLO additions
 * (offeringFiles + tiebreaker) — a single misordered read/write would corrupt the whole stream.
 */
class ProtocolWireTest {

    @Test
    fun handshake_roundTripsMagicAndVersion() = runBlocking {
        val ch = ByteChannel(autoFlush = true)
        JetzyProtocol.writeHandshake(ch)
        assertEquals(JetzyProtocol.VERSION, JetzyProtocol.readHandshake(ch))
    }

    @Test
    fun hello_roundTripsEveryField_includingV3Additions() = runBlocking {
        val ch = ByteChannel(autoFlush = true)
        val hello = JetzyProtocol.HelloFrame(
            name = "John's iPhone: work",
            platform = Platform.IOS,
            capabilities = 0x47,
            offeringFiles = true,
            tiebreaker = -123456,
        )
        JetzyProtocol.writeHello(ch, hello)
        assertEquals(hello, JetzyProtocol.readHello(ch))
    }

    @Test
    fun hello_defaultsRoundTrip() = runBlocking {
        val ch = ByteChannel(autoFlush = true)
        val hello = JetzyProtocol.HelloFrame("Dev", Platform.Android)
        JetzyProtocol.writeHello(ch, hello)
        val got = JetzyProtocol.readHello(ch)
        assertEquals(false, got.offeringFiles)
        assertEquals(0L, got.capabilities)
        assertEquals(hello, got)
    }

    @Test
    fun manifest_roundTripsWithFoldersTextAndNullMime() = runBlocking {
        val ch = ByteChannel(autoFlush = true)
        val mf = TransferManifest(
            totalFiles = 3,
            totalBytes = 350,
            entries = listOf(
                ManifestEntry("a.txt", 100, "text/plain", "folder/sub/a.txt", EntryType.FILE),
                ManifestEntry("note", 50, null, "", EntryType.TEXT),
                ManifestEntry("b.bin", 200), // null mime, empty relPath, default FILE
            ),
            senderName = "Sender",
            senderPlatform = Platform.PC,
        )
        JetzyProtocol.writeManifest(ch, JetzyProtocol.ManifestFrame("session-xyz", mf))
        val got = JetzyProtocol.readManifest(ch)
        assertEquals("session-xyz", got.sessionId)
        assertEquals(mf, got.manifest)
        assertEquals(null, got.manifest.entries[2].mimeType) // "" decoded back to null
    }

    @Test
    fun manifestAck_roundTripsResumePoint() = runBlocking {
        val ch = ByteChannel(autoFlush = true)
        val ack = JetzyProtocol.ManifestAckFrame(
            status = JetzyProtocol.AckStatus.RESUME,
            receiver = PeerInfo("Receiver", Platform.Android),
            resumeFileIndex = 2,
            resumeByteOffset = 4096,
            reason = "",
        )
        JetzyProtocol.writeManifestAck(ch, ack)
        assertEquals(ack, JetzyProtocol.readManifestAck(ch))
    }

    @Test
    fun manifestAck_noReceiver_roundTrips() = runBlocking {
        val ch = ByteChannel(autoFlush = true)
        val ack = JetzyProtocol.ManifestAckFrame(
            status = JetzyProtocol.AckStatus.INSUFFICIENT_SPACE,
            receiver = null,
            reason = "need 5 bytes",
        )
        JetzyProtocol.writeManifestAck(ch, ack)
        assertEquals(ack, JetzyProtocol.readManifestAck(ch))
    }
}
