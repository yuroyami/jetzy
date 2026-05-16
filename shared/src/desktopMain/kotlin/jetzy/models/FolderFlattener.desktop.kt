package jetzy.models

import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

actual suspend fun flattenFolder(folder: PlatformFile): List<FlatFile> = withContext(Dispatchers.IO) {
    val root = File(folder.path)
    if (!root.exists() || !root.isDirectory) return@withContext emptyList()

    val rootName = root.name.ifEmpty { "folder" }
    val rootPath = root.absolutePath
    val out = mutableListOf<FlatFile>()

    root.walkTopDown()
        .filter { it.isFile }
        .forEach { file ->
            val relative = file.absolutePath
                .removePrefix(rootPath)
                .trimStart(File.separatorChar)
                .replace(File.separatorChar, '/')
            out += FlatFile(
                relativePath = if (relative.isEmpty()) "$rootName/${file.name}" else "$rootName/$relative",
                file = PlatformFile(file),
            )
        }

    out
}
