package jetzy.managers

import jetzy.ui.transfer.ManifestEntry
import kotlin.test.Test
import kotlin.test.assertEquals

/** Pins the resume arithmetic — including the all-complete case that used to re-transfer everything. */
class ResumePlannerTest {

    private fun e(size: Long, name: String = "f") = ManifestEntry(name = name, sizeBytes = size)
    private fun slot(written: Long, size: Long) = ResumePlanner.Slot(written, size)

    // ── resumePoint ───────────────────────────────────────────────────────────

    @Test
    fun allComplete_returnsPastLastIndex_notZero() {
        // The bug fix: a disconnect right before DONE must NOT re-send the whole batch.
        val entries = listOf(e(100), e(200))
        assertEquals(2 to 0L, ResumePlanner.resumePoint(entries, mapOf(0 to slot(100, 100), 1 to slot(200, 200))))
    }

    @Test
    fun emptyLedger_resumesAtFileZeroOffsetZero() {
        assertEquals(0 to 0L, ResumePlanner.resumePoint(listOf(e(100)), emptyMap()))
    }

    @Test
    fun partialFirstFile_resumesAtItsOffset() {
        assertEquals(0 to 40L, ResumePlanner.resumePoint(listOf(e(100), e(50)), mapOf(0 to slot(40, 100))))
    }

    @Test
    fun firstComplete_secondMissing_resumesAtSecond() {
        assertEquals(1 to 0L, ResumePlanner.resumePoint(listOf(e(100), e(50)), mapOf(0 to slot(100, 100))))
    }

    @Test
    fun sizeMismatch_resendsWholeFileFromZero() {
        assertEquals(0 to 0L, ResumePlanner.resumePoint(listOf(e(100)), mapOf(0 to slot(100, 80))))
    }

    @Test
    fun corruptOverwrittenLedger_treatedComplete_skipped() {
        // bytesWritten > size shouldn't happen; if it does, the file is complete and we move on.
        assertEquals(1 to 0L, ResumePlanner.resumePoint(listOf(e(100), e(50)), mapOf(0 to slot(150, 100))))
    }

    @Test
    fun zeroByteFile_isComplete() {
        assertEquals(1 to 0L, ResumePlanner.resumePoint(listOf(e(0), e(10)), mapOf(0 to slot(0, 0))))
    }

    @Test
    fun negativeLedgerBytes_clampedToZero() {
        assertEquals(0 to 0L, ResumePlanner.resumePoint(listOf(e(100)), mapOf(0 to slot(-5, 100))))
    }

    // ── remainingBytes ──────────────────────────────────────────────────────────

    @Test
    fun remaining_freshSession_isFullTotal() {
        assertEquals(300L, ResumePlanner.remainingBytes(listOf(e(100), e(200)), 300, sessionMatches = false, emptyMap()))
    }

    @Test
    fun remaining_partial_countsOnlyWhatIsOwed() {
        val entries = listOf(e(100), e(200))
        assertEquals(150L, ResumePlanner.remainingBytes(entries, 300, true, mapOf(0 to slot(100, 100), 1 to slot(50, 200))))
    }

    @Test
    fun remaining_allComplete_isZero() {
        val entries = listOf(e(100), e(200))
        assertEquals(0L, ResumePlanner.remainingBytes(entries, 300, true, mapOf(0 to slot(100, 100), 1 to slot(200, 200))))
    }

    @Test
    fun remaining_sizeMismatch_owesWholeFileAgain() {
        assertEquals(100L, ResumePlanner.remainingBytes(listOf(e(100)), 100, true, mapOf(0 to slot(100, 80))))
    }
}
