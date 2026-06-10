package jetzy.utils

import kotlinx.coroutines.withContext
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask

/**
 * iOS: move into `<Documents>/Jetzy` — a real POSIX path, so the shared [moveStagedFilesToDir]
 * applies unchanged. No runtime permission; the folder shows under "On My iPhone › Jetzy" in the
 * Files app once `UIFileSharingEnabled` + `LSSupportsOpeningDocumentsInPlace` are set in Info.plist.
 */
actual suspend fun saveReceivedFilesToDefault(files: List<StagedReceivedFile>): SaveReport? =
    withContext(PreferablyIO) {
        if (files.isEmpty()) return@withContext null
        val docs = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true)
            .firstOrNull() as? String ?: return@withContext null
        val saved = runCatching { moveStagedFilesToDir(files, "$docs/Jetzy") }.getOrDefault(emptyList())
        if (saved.isEmpty()) null else SaveReport("On My iPhone › Jetzy", saved)
    }
