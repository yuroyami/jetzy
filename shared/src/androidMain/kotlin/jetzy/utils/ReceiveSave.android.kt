package jetzy.utils

import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import jetzy.MainActivity
import jetzy.managers.SafePath
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Android needs no runtime permission for either path:
 *  - API 29+ → MediaStore `Downloads/Jetzy` (public, visible in Files/Downloads, survives uninstall).
 *  - API ≤28 → app-specific external `Download` dir (public Downloads would need
 *    WRITE_EXTERNAL_STORAGE, which we deliberately don't request).
 */
actual suspend fun saveReceivedFilesToDefault(files: List<StagedReceivedFile>): String? =
    withContext(PreferablyIO) {
        if (files.isEmpty()) return@withContext null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { saveToMediaStoreDownloads(files) }.getOrNull()
        } else {
            runCatching { saveToAppSpecificDownloads(files) }.getOrNull()
        }
    }

/** API 29+: publish each file into the public Downloads/Jetzy collection via the content resolver. */
private fun saveToMediaStoreDownloads(files: List<StagedReceivedFile>): String {
    val resolver = MainActivity.contextGetter().contentResolver
    val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    for (f in files) {
        val safeName = SafePath.safeName(f.name)
        val sub = SafePath.safeSegments(f.relativePath.substringBeforeLast('/', "")).joinToString("/")
        val relPath = buildString {
            append(Environment.DIRECTORY_DOWNLOADS)
            append("/Jetzy")
            if (sub.isNotEmpty()) { append('/'); append(sub) }
        }

        val pending = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, safeName)
            put(MediaStore.Downloads.RELATIVE_PATH, relPath)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, pending)
            ?: error("MediaStore insert returned null for '$safeName'")

        resolver.openOutputStream(uri).use { out ->
            requireNotNull(out) { "openOutputStream null for '$safeName'" }
            File(f.tempPath).inputStream().use { input -> input.copyTo(out) }
        }

        resolver.update(
            uri,
            ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
            null, null,
        )
        // Published — the staged temp copy is no longer needed.
        runCatching { File(f.tempPath).delete() }
    }
    return "Downloads/Jetzy"
}

/** API ≤28 fallback: app-specific external Download dir — no permission on any API level. */
private fun saveToAppSpecificDownloads(files: List<StagedReceivedFile>): String {
    val ctx = MainActivity.contextGetter()
    val base = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: File(ctx.filesDir, "Download")
    if (!base.exists()) base.mkdirs()
    moveStagedFilesToDir(files, base.absolutePath)
    return "App storage › Download"
}
