package jetzy.utils

import jetzy.managers.SafePath
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * One fully-received file sitting in the temp/staging dir, ready to be moved to its final,
 * user-visible home. [tempPath] is the absolute staging path; [name] and [relativePath] are
 * the *sender-controlled* manifest values and MUST be sanitized via [SafePath] before use.
 */
data class StagedReceivedFile(
    val tempPath: String,
    val name: String,
    val relativePath: String,
)

/**
 * Auto-saves received files to the platform's default, **user-visible**, **permission-free**
 * location the instant a transfer's CRC checks pass — the fix for the silent data-loss footgun
 * where tapping "Done" before a manual "Save" purged everything.
 *
 * Returns a short human-readable destination label (e.g. "Downloads/Jetzy") on success, or
 * `null` if the save failed entirely (caller then leaves the manual folder-picker available).
 *
 * Per platform (none require a runtime permission prompt):
 *  - **Android**: MediaStore `Downloads/Jetzy` on API 29+ (survives uninstall, shows in the
 *    Files/Downloads app); app-specific external `Download` dir on API ≤28 (no `WRITE_EXTERNAL_STORAGE`).
 *  - **iOS / macOS**: `<Documents>/Jetzy` — visible under "On My iPhone › Jetzy" in the Files app
 *    once `UIFileSharingEnabled` + `LSSupportsOpeningDocumentsInPlace` are set in Info.plist.
 *  - **Desktop (JVM)**: `~/Downloads/Jetzy` (`XDG_DOWNLOAD_DIR` honoured on Linux).
 */
expect suspend fun saveReceivedFilesToDefault(files: List<StagedReceivedFile>): String?

/**
 * Shared, path-based mover used by the actuals whose default location is a real filesystem path
 * (iOS, macOS, desktop, and Android's API-≤28 fallback). Creates [destDirRoot] and any sanitized
 * subfolders, then moves each staged file in, suffixing the basename on collision so nothing is
 * ever overwritten. Uses an atomic rename where possible, falling back to copy+delete across
 * volumes (e.g. tmpfs `/tmp` → home partition on Linux, where `atomicMove` throws). Returns the
 * number of files moved. Throws on unrecoverable I/O failure — the actual wraps this in runCatching.
 */
fun moveStagedFilesToDir(files: List<StagedReceivedFile>, destDirRoot: String): Int {
    val root = Path(destDirRoot)
    if (!SystemFileSystem.exists(root)) SystemFileSystem.createDirectories(root)

    var moved = 0
    for (f in files) {
        val safeName = SafePath.safeName(f.name)
        val parentSegments = SafePath.safeSegments(f.relativePath.substringBeforeLast('/', ""))

        var dirPath = destDirRoot
        for (seg in parentSegments) {
            dirPath = "$dirPath/$seg"
            val p = Path(dirPath)
            if (!SystemFileSystem.exists(p)) SystemFileSystem.createDirectories(p)
        }

        val dest = uniqueDestination("$dirPath/$safeName")
        moveOrCopy(Path(f.tempPath), Path(dest))
        moved++
    }
    return moved
}

/** Returns [candidate] if free, else inserts _1, _2, … before the extension until a free name is found. */
private fun uniqueDestination(candidate: String): String {
    if (!SystemFileSystem.exists(Path(candidate))) return candidate
    val slash = candidate.lastIndexOf('/')
    val dir = if (slash >= 0) candidate.substring(0, slash) else ""
    val base = candidate.substring(slash + 1)
    val dot = base.lastIndexOf('.')
    val stem = if (dot > 0) base.substring(0, dot) else base
    val ext = if (dot > 0) base.substring(dot) else ""
    var i = 1
    while (true) {
        val attempt = if (dir.isEmpty()) "${stem}_$i$ext" else "$dir/${stem}_$i$ext"
        if (!SystemFileSystem.exists(Path(attempt))) return attempt
        i++
    }
}

/** Atomic rename when the two paths share a volume; otherwise stream-copy then delete the source. */
private fun moveOrCopy(from: Path, to: Path) {
    try {
        SystemFileSystem.atomicMove(from, to)
        return
    } catch (_: Exception) {
        // Cross-volume (EXDEV) or platform that can't atomic-move — fall through to copy+delete.
    }
    val src = SystemFileSystem.source(from).buffered()
    try {
        val dst = SystemFileSystem.sink(to).buffered()
        try {
            src.transferTo(dst)
            dst.flush()
        } finally {
            dst.close()
        }
    } finally {
        src.close()
    }
    runCatching { SystemFileSystem.delete(from) }
}
