package jetzy.models

import io.github.vinceglb.filekit.PlatformFile
import platform.Foundation.NSDirectoryEnumerationSkipsHiddenFiles
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.lastPathComponent
import platform.Foundation.pathExtension

/**
 * macOS folder flattener. Effectively identical to iOS — both use the same
 * NSFileManager + NSURL enumerator. macOS doesn't need the security-scoped
 * resource dance that iOS document picker uses, so it's actually simpler here.
 */
actual suspend fun flattenFolder(folder: PlatformFile): List<FlatFile> {
    val fileManager = NSFileManager.defaultManager
    val folderUrl = folder.nsUrl
    val folderName = folderUrl.lastPathComponent ?: "folder"

    val enumerator = fileManager.enumeratorAtURL(
        url = folderUrl,
        includingPropertiesForKeys = null,
        options = NSDirectoryEnumerationSkipsHiddenFiles,
        errorHandler = null,
    ) ?: return emptyList()

    val result = mutableListOf<FlatFile>()
    var next = enumerator.nextObject()
    while (next != null) {
        val fileUrl = next as? NSURL
        if (fileUrl != null) {
            val relativePath = fileUrl.path?.removePrefix(folderUrl.path ?: "")?.trimStart('/')
            if (!relativePath.isNullOrEmpty()) {
                val ext = fileUrl.pathExtension
                if (ext != null && ext.isNotEmpty()) {
                    result += FlatFile(
                        relativePath = "$folderName/$relativePath",
                        file = PlatformFile(fileUrl),
                    )
                }
            }
        }
        next = enumerator.nextObject()
    }
    return result
}
