package jetzy.managers

import jetzy.ui.transfer.ManifestEntry

/**
 * Pure resume arithmetic, extracted from [P2PManager] so the app's trickiest correctness logic is
 * unit-testable in isolation — no sockets, no IO, no instance state. Operates on the manifest
 * entries plus a minimal view of the receiver's ledger (how many bytes are already staged per file).
 */
object ResumePlanner {

    /** What the receiver already has on disk for one file. */
    data class Slot(val bytesWritten: Long, val sizeBytes: Long)

    /**
     * The first not-yet-complete file as `(index, byteOffsetToResumeAt)`. A file is incomplete if
     * it's missing from the ledger, its recorded size disagrees with the manifest (→ re-send whole),
     * or fewer bytes are on disk than its declared size.
     *
     * **If every file is already complete, returns `(entries.size, 0L)`** so the sender's
     * `drop(resumeFileIndex)` loop runs zero times and it skips straight to DONE. Returning `(0, 0L)`
     * here (the old behavior) meant a disconnect landing *right before the DONE marker* re-transferred
     * the entire batch. The offset is clamped to `[0, sizeBytes]` to survive a corrupt ledger value.
     */
    fun resumePoint(entries: List<ManifestEntry>, ledger: Map<Int, Slot>): Pair<Int, Long> {
        for ((i, entry) in entries.withIndex()) {
            val s = ledger[i]
            when {
                // Missing, or the on-disk data is for a different size than the manifest declares:
                // the staged bytes are untrustworthy, so re-send the whole file from offset 0.
                // (The old code returned bytesWritten here, which made the sender skip the file and
                // the receiver then fail the CRC over wrong data instead of cleanly re-sending.)
                s == null || s.sizeBytes != entry.sizeBytes -> return i to 0L
                // Same file, partially received: resume at exactly what's on disk.
                s.bytesWritten < entry.sizeBytes -> return i to s.bytesWritten.coerceIn(0L, entry.sizeBytes)
                // else: complete — keep scanning.
            }
        }
        return entries.size to 0L
    }

    /**
     * Bytes still owed to the receiver, counting what's already staged — but only when the session
     * matches (a fresh session owes the full [totalBytes]). A size mismatch on a known file owes the
     * whole file again; an over-written ledger entry owes nothing.
     */
    fun remainingBytes(
        entries: List<ManifestEntry>,
        totalBytes: Long,
        sessionMatches: Boolean,
        ledger: Map<Int, Slot>,
    ): Long {
        if (!sessionMatches) return totalBytes
        var remaining = 0L
        for ((i, entry) in entries.withIndex()) {
            val s = ledger[i]
            remaining += when {
                s == null -> entry.sizeBytes
                s.sizeBytes != entry.sizeBytes -> entry.sizeBytes
                s.bytesWritten >= entry.sizeBytes -> 0L
                else -> entry.sizeBytes - s.bytesWritten
            }
        }
        return remaining
    }
}
