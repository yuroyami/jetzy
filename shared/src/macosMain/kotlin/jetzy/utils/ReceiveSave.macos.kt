package jetzy.utils

import kotlinx.coroutines.withContext
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask

/**
 * macOS native: move into `<Documents>/Jetzy` (a real POSIX path → shared [moveStagedFilesToDir]).
 * No permission; visible in Finder under the user's Documents.
 */
actual suspend fun saveReceivedFilesToDefault(files: List<StagedReceivedFile>): String? =
    withContext(PreferablyIO) {
        if (files.isEmpty()) return@withContext null
        val docs = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true)
            .firstOrNull() as? String ?: return@withContext null
        runCatching { moveStagedFilesToDir(files, "$docs/Jetzy") }
            .fold(onSuccess = { "Documents › Jetzy" }, onFailure = { null })
    }
