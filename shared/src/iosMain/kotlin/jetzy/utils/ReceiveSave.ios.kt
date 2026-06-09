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
actual suspend fun saveReceivedFilesToDefault(files: List<StagedReceivedFile>): String? =
    withContext(PreferablyIO) {
        if (files.isEmpty()) return@withContext null
        val docs = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true)
            .firstOrNull() as? String ?: return@withContext null
        runCatching { moveStagedFilesToDir(files, "$docs/Jetzy") }
            .fold(onSuccess = { "On My iPhone › Jetzy" }, onFailure = { null })
    }
