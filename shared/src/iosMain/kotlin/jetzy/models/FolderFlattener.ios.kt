package jetzy.models

import io.github.vinceglb.filekit.PlatformFile
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.NSDirectoryEnumerationSkipsHiddenFiles
import platform.Foundation.pathExtension
import platform.Foundation.lastPathComponent

actual suspend fun flattenFolder(folder: PlatformFile): List<FlatFile> {
    val fileManager = NSFileManager.defaultManager
    val folderUrl = folder.nsUrl
    val folderName = folderUrl.lastPathComponent ?: "folder"

    // Start security-scoped access
    val hasAccess = folderUrl.startAccessingSecurityScopedResource()

    try {
        val enumerator = fileManager.enumeratorAtURL(
            url = folderUrl,
            includingPropertiesForKeys = null,
            options = NSDirectoryEnumerationSkipsHiddenFiles,
            errorHandler = null
        ) ?: return emptyList()

        val result = mutableListOf<FlatFile>()

        var nextObject = enumerator.nextObject()
        while (nextObject != null) {
            val fileUrl = nextObject as? NSURL
            if (fileUrl != null) {
                // Check if it's a file (has an extension or is not a directory)
                val isDirectory = fileManager.fileExistsAtPath(fileUrl.path ?: "", isDirectory = null)
                val relativePath = fileUrl.path?.removePrefix(folderUrl.path ?: "")?.trimStart('/')
                if (relativePath != null && relativePath.isNotEmpty()) {
                    // Only add files, not directories
                    val pathExtension = fileUrl.pathExtension
                    if (pathExtension != null && pathExtension.isNotEmpty()) {
                        result.add(
                            FlatFile(
                                relativePath = "$folderName/$relativePath",
                                file = PlatformFile(fileUrl)
                            )
                        )
                    }
                }
            }
            nextObject = enumerator.nextObject()
        }

        return result
    } finally {
        if (hasAccess) {
            folderUrl.stopAccessingSecurityScopedResource()
        }
    }
}
