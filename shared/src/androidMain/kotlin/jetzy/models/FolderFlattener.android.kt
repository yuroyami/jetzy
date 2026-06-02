package jetzy.models

import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.path
import jetzy.MainActivity
import jetzy.utils.PreferablyIO
import kotlinx.coroutines.withContext

actual suspend fun flattenFolder(folder: PlatformFile): List<FlatFile> = withContext(PreferablyIO) {
    val context = MainActivity.contextGetter()
    val rootDoc = DocumentFile.fromTreeUri(context, folder.path.toUri())
        ?: return@withContext emptyList()

    val rootName = rootDoc.name ?: "folder"
    val result = mutableListOf<FlatFile>()

    fun traverse(doc: DocumentFile, currentPath: String) {
        for (child in doc.listFiles()) {
            if (child.isDirectory) {
                val childName = child.name ?: "unnamed"
                traverse(child, "$currentPath/$childName")
            } else if (child.isFile) {
                val childUri = child.uri
                val childName = child.name ?: "unnamed"
                result.add(
                    FlatFile(
                        relativePath = "$currentPath/$childName",
                        file = PlatformFile(childUri)
                    )
                )
            }
        }
    }

    traverse(rootDoc, rootName)
    result
}
