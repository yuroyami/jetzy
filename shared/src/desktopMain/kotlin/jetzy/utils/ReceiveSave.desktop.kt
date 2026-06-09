package jetzy.utils

import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * Desktop JVM: move into `~/Downloads/Jetzy` (a real path → shared [moveStagedFilesToDir]).
 * No permission needed; lands where users actually look. Honours `XDG_DOWNLOAD_DIR` on Linux.
 */
actual suspend fun saveReceivedFilesToDefault(files: List<StagedReceivedFile>): String? =
    withContext(PreferablyIO) {
        if (files.isEmpty()) return@withContext null
        val dest = defaultDownloadsDir()
        runCatching { moveStagedFilesToDir(files, dest) }
            .fold(onSuccess = { "Downloads/Jetzy" }, onFailure = { null })
    }

private fun defaultDownloadsDir(): String {
    val home = System.getProperty("user.home") ?: "."
    val os = System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT)
    val downloads = when {
        "win" in os -> File(home, "Downloads")
        "mac" in os || "darwin" in os -> File(home, "Downloads")
        else -> {
            val xdg = System.getenv("XDG_DOWNLOAD_DIR")?.takeIf { it.isNotBlank() }
            if (xdg != null) File(xdg.replace("\$HOME", home).replace("\${HOME}", home))
            else File(home, "Downloads")
        }
    }
    val jetzy = File(downloads, "Jetzy")
    if (!jetzy.exists()) jetzy.mkdirs()
    return jetzy.absolutePath
}
