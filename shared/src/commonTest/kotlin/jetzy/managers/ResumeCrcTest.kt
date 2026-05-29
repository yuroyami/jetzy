package jetzy.managers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Regression tests for CRIT-1: resumed transfers always failed their integrity check because the
 * sender hashed only the re-sent tail `[offset, size)` while the receiver hashed the whole file
 * `[0, size)`. The fix makes the sender feed the skipped prefix through its CRC too, so both ends
 * compute the whole-file CRC. These tests pin down the streaming-CRC property that makes that work.
 */
class ResumeCrcTest {

    private fun crcOf(bytes: ByteArray, from: Int = 0, to: Int = bytes.size): Int =
        Crc32().apply { update(bytes, from, to - from) }.finish()

    private val sample = ByteArray(50_000) { (it * 31 + 7).toByte() }

    @Test
    fun canonicalCheckValue() {
        // The well-known CRC-32 check value for "123456789".
        val bytes = "123456789".encodeToByteArray()
        assertEquals(0xCBF43926.toInt(), crcOf(bytes))
    }

    @Test
    fun streamingSplitMatchesWholeAtEveryOffset() {
        val whole = ByteArray(70_000) { ((it * 131 + 17) and 0xFF).toByte() }
        val full = crcOf(whole)
        for (split in intArrayOf(0, 1, 1000, 35_000, 69_999, 70_000)) {
            val c = Crc32()
            c.update(whole, 0, split)                 // prefix [0, split)
            c.update(whole, split, whole.size - split) // tail [split, size)
            assertEquals(full, c.finish(), "two-part CRC at split=$split must equal whole-file CRC")
        }
    }

    @Test
    fun resumeCrcMatchesWhenPrefixIntact() {
        val offset = 33_333
        // Fixed sender: hash the locally-read prefix, then the re-sent tail, on one instance.
        val senderCrc = Crc32().apply {
            update(sample, 0, offset)
            update(sample, offset, sample.size - offset)
        }.finish()
        // Fixed receiver: hash its on-disk prefix (= exactly what the sender already sent), then
        // the freshly received tail.
        val receiverPrefix = sample.copyOfRange(0, offset)
        val receiverCrc = Crc32().apply {
            update(receiverPrefix, 0, offset)
            update(sample, offset, sample.size - offset)
        }.finish()

        assertEquals(senderCrc, receiverCrc, "sender and receiver CRCs must agree on resume")
        assertEquals(crcOf(sample), senderCrc, "and both must equal the whole-file CRC")
    }

    @Test
    fun resumeCrcDetectsCorruptPrefix() {
        val offset = 33_333
        val corruptPrefix = sample.copyOfRange(0, offset).also { it[5] = (it[5] + 1).toByte() }
        val receiverCrc = Crc32().apply {
            update(corruptPrefix, 0, offset)
            update(sample, offset, sample.size - offset)
        }.finish()
        assertNotEquals(crcOf(sample), receiverCrc, "a corrupt partial must still fail verification")
    }

    @Test
    fun oldTailOnlyCrcWouldHaveMismatched() {
        // Documents the bug: the old sender hashed only [offset, size). For any non-zero offset
        // that differs from the receiver's whole-file CRC, so every resumed file failed.
        val offset = 12_345
        val tailOnly = crcOf(sample, offset, sample.size)
        assertNotEquals(crcOf(sample), tailOnly)
    }
}
