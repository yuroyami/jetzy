package jetzy.utils

import android.content.ContentValues
import android.net.Uri
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
actual suspend fun saveReceivedFilesToDefault(files: List<StagedReceivedFile>): SaveReport? =
    withContext(PreferablyIO) {
        if (files.isEmpty()) return@withContext null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { saveToMediaStoreDownloads(files) }.getOrNull()
        } else {
            runCatching { saveToAppSpecificDownloads(files) }.getOrNull()
        }
    }

/**
 * API 29+: publish each file into the public Downloads/Jetzy collection via the content resolver.
 * Per-item: one file failing (or its temp having vanished) doesn't abort the rest — its pending
 * MediaStore row is rolled back and the loop continues; the report carries what actually landed.
 */
private fun saveToMediaStoreDownloads(files: List<StagedReceivedFile>): SaveReport? {
    val resolver = MainActivity.contextGetter().contentResolver
    val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    val saved = mutableListOf<String>()
    for (f in files) {
        val temp = File(f.tempPath)
        if (!temp.exists()) continue
        var uri: Uri? = null
        try {
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
            uri = resolver.insert(collection, pending)
                ?: error("MediaStore insert returned null for '$safeName'")

            resolver.openOutputStream(uri).use { out ->
                requireNotNull(out) { "openOutputStream null for '$safeName'" }
                temp.inputStream().use { input -> input.copyTo(out) }
            }

            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                null, null,
            )
            // Published — the staged temp copy is no longer needed.
            runCatching { temp.delete() }
            saved.add(f.tempPath)
        } catch (e: Exception) {
            loggy("save: couldn't publish '${f.name}': ${e.message}")
            // Roll back the half-written pending row so it can't linger invisible-but-counted.
            uri?.let { runCatching { resolver.delete(it, null, null) } }
        }
    }
    return if (saved.isEmpty()) null else SaveReport("Downloads/Jetzy", saved)
}

/** API ≤28 fallback: app-specific external Download dir — no permission on any API level. */
private fun saveToAppSpecificDownloads(files: List<StagedReceivedFile>): SaveReport? {
    val ctx = MainActivity.contextGetter()
    val base = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: File(ctx.filesDir, "Download")
    if (!base.exists()) base.mkdirs()
    val saved = moveStagedFilesToDir(files, base.absolutePath)
    return if (saved.isEmpty()) null else SaveReport("App storage › Download", saved)
}
