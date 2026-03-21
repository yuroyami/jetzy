package jetzy.models

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import io.github.vinceglb.filekit.PlatformFile
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

private object FolderFlattenerHelper : KoinComponent {
    val context: Context by inject()
}

actual suspend fun flattenFolder(folder: PlatformFile): List<FlatFile> {
    val context = FolderFlattenerHelper.context
    val rootDoc = DocumentFile.fromTreeUri(context, folder.uri)
        ?: return emptyList()

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
    return result
}
