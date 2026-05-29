package jetzy.models

import io.github.vinceglb.filekit.PlatformFile
import kotlinx.cinterop.BooleanVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.NSDirectoryEnumerationSkipsHiddenFiles
import platform.Foundation.lastPathComponent

@OptIn(ExperimentalForeignApi::class)
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
                // Properly determine directory-ness via the out-param (the old code passed null,
                // so it dropped extension-less files like README/Makefile and mis-classified
                // dotted folders like "My.Photos" as files).
                val path = fileUrl.path ?: ""
                val isDirectory = memScoped {
                    val isDir = alloc<BooleanVar>()
                    val exists = fileManager.fileExistsAtPath(path, isDirectory = isDir.ptr)
                    exists && isDir.value
                }
                val relativePath = fileUrl.path?.removePrefix(folderUrl.path ?: "")?.trimStart('/')
                if (!isDirectory && relativePath != null && relativePath.isNotEmpty()) {
                    result.add(
                        FlatFile(
                            relativePath = "$folderName/$relativePath",
                            file = PlatformFile(fileUrl)
                        )
                    )
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
