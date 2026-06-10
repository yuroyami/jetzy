package jetzy.ui.main

import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.awtTransferable
import io.github.vinceglb.filekit.PlatformFile
import jetzy.models.JetzyElement
import java.awt.datatransfer.DataFlavor
import java.io.File

/**
 * Desktop: dropping files/folders from Finder/Explorer anywhere on the target stages them —
 * the desktop-native counterpart of Android's share sheet, replacing the AWT file-dialog
 * round trip as the only intake.
 */
@OptIn(ExperimentalComposeUiApi::class)
actual fun Modifier.jetzyDropTarget(onDropped: (List<JetzyElement>) -> Unit): Modifier =
    this.dragAndDropTarget(
        shouldStartDragAndDrop = { true },
        target = object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean {
                val files = runCatching {
                    @Suppress("UNCHECKED_CAST")
                    event.awtTransferable.getTransferData(DataFlavor.javaFileListFlavor) as List<File>
                }.getOrNull().orEmpty()
                if (files.isEmpty()) return false
                onDropped(
                    files.map {
                        if (it.isDirectory) JetzyElement.Folder(PlatformFile(it))
                        else JetzyElement.File(PlatformFile(it))
                    }
                )
                return true
            }
        },
    )
