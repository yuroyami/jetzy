package jetzy.utils

import kotlinx.coroutines.withContext
import platform.AppKit.NSWorkspace
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask

/**
 * macOS native: move into `<Documents>/Jetzy` (a real POSIX path → shared [moveStagedFilesToDir]).
 * No permission; visible in Finder under the user's Documents.
 */
actual suspend fun saveReceivedFilesToDefault(files: List<StagedReceivedFile>): SaveReport? =
    withContext(PreferablyIO) {
        if (files.isEmpty()) return@withContext null
        val docs = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true)
            .firstOrNull() as? String ?: return@withContext null
        val saved = runCatching { moveStagedFilesToDir(files, "$docs/Jetzy") }.getOrDefault(emptyList())
        if (saved.isEmpty()) null else SaveReport("Documents › Jetzy", saved)
    }

actual fun openReceivedLocation(): Boolean {
    val docs = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true)
        .firstOrNull() as? String ?: return false
    return NSWorkspace.sharedWorkspace.openFile("$docs/Jetzy")
}
