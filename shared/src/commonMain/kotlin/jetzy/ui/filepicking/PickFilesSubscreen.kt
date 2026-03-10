package jetzy.ui.filepicking

import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.FilePresent
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderSpecial
import androidx.compose.material3.Icon
import androidx.compose.material3.LeadingIconTab
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SmallExtendedFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment.Companion.BottomEnd
import androidx.compose.ui.Alignment.Companion.BottomStart
import androidx.compose.ui.Alignment.Companion.CenterHorizontally
import androidx.compose.ui.Alignment.Companion.TopEnd
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.vinceglb.filekit.dialogs.FileKitMode
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberDirectoryPickerLauncher
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.name
import jetzy.models.JetzyElement
import jetzy.theme.jetzyYellow
import jetzy.theme.ssp
import jetzy.ui.LocalViewmodel
import jetzy.utils.ComposeUtils.scheme

enum class FileFolderViewMode { Files, Folders }

@Composable
fun PickFilesSubscreenUI() {
    val viewmodel = LocalViewmodel.current

    var tab by remember { mutableStateOf(FileFolderViewMode.Files) }

    val filePicker = rememberFilePickerLauncher(
        type = FileKitType.File(), mode = FileKitMode.Multiple(),
    ) { files ->
        files?.map { JetzyElement.File(it) }?.forEach {
            viewmodel.elementsToSend.add(it)
        }
        viewmodel.snacky("Added ${files?.size} item(s)")
        tab = FileFolderViewMode.Files
    }

    val folderPicker = rememberDirectoryPickerLauncher { folder ->
        folder?.let { viewmodel.elementsToSend.add(JetzyElement.Folder(it)) }
        viewmodel.snacky("Added folder: ${folder?.name}")
        tab = FileFolderViewMode.Folders
    }

    val longClickedFiles = remember { mutableStateListOf<Int>() }
    val longClickedFolders = remember { mutableStateListOf<Int>() }

    Box(modifier = Modifier.fillMaxSize()) {

        Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = CenterHorizontally) {
            Text(
                text = "Select files (and/or folders) to send to your peer.\nFiles can be of any size and any format.",
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 12.dp, top = 28.dp)
            )

            Surface(
                modifier = Modifier.fillMaxSize().padding(top = 16.dp, start = 8.dp, end = 8.dp, bottom = 168.dp),
                tonalElevation = 28.dp,
                shadowElevation = 3.dp,
                shape = RoundedCornerShape(6.dp)
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    PrimaryTabRow(
                        selectedTabIndex = tab.ordinal
                    ) {
                        LeadingIconTab(
                            selected = tab.ordinal == 0,
                            onClick = {
                                tab = FileFolderViewMode.Files
                            },
                            text = { Text("Files") },
                            icon = { Icon(Icons.Filled.FilePresent, null) }
                        )
                        LeadingIconTab(
                            selected = tab.ordinal == 1,
                            onClick = {
                                tab = FileFolderViewMode.Folders
                            },
                            text = { Text("Folders") },
                            icon = { Icon(Icons.Filled.FolderSpecial, null) }
                        )
                    }

                    val filesForSending by viewmodel.file2Send.collectAsState()
                    val foldersForSending by viewmodel.folders2Send.collectAsState()

                    FileFolderGridView(
                        viewMode = tab,
                        allItems = when (tab) {
                            FileFolderViewMode.Files -> filesForSending
                            FileFolderViewMode.Folders -> foldersForSending
                        },
                        highlightedItems = when (tab) {
                            FileFolderViewMode.Files -> longClickedFiles
                            FileFolderViewMode.Folders -> longClickedFolders
                        },
                    )
                }
            }
        }

        SmallExtendedFloatingActionButton(
            icon = {
                Icon(Icons.Filled.FilePresent, null)
            },
            onClick = {
                filePicker.launch()
            },
            text = { Text("Select File(s)") },
            modifier = Modifier.align(BottomEnd).padding(8.dp).padding(bottom = 12.dp),
            expanded = true
        )

        if (folderPicker != null) {
            SmallExtendedFloatingActionButton(
                icon = {
                    Icon(Icons.Filled.CreateNewFolder, null)
                },
                onClick = {
                    folderPicker.launch()
                },
                text = { Text("Select Folder") },
                modifier = Modifier.align(BottomEnd).padding(8.dp).padding(bottom = 86.dp),
                expanded = true
            )
        }

        if ((longClickedFiles.isNotEmpty() && tab == FileFolderViewMode.Files) || (longClickedFolders.isNotEmpty() && tab == FileFolderViewMode.Folders)) {
            SmallExtendedFloatingActionButton(
                icon = {
                    Icon(Icons.Filled.ClearAll, null)
                },
                onClick = {
                    val (longClickedItems, items, itemType) = when (tab) {
                        FileFolderViewMode.Files -> Triple(longClickedFiles, viewmodel.elementsToSend, "file(s)")
                        FileFolderViewMode.Folders -> Triple(longClickedFolders, viewmodel.elementsToSend, "folder(s)")
                    }

                    val count = longClickedItems.size
                    val processedIndices = mutableListOf<Int>()

                    for (index in longClickedItems) {
                        items.removeAt(index)
                        processedIndices.add(index)
                    }
                    longClickedItems.removeAll(processedIndices)

                    viewmodel.snacky("Excluded $count $itemType from the list")
                },
                text = { Text("Exclude", fontSize = 10.ssp) },
                modifier = Modifier.align(BottomStart).padding(8.dp).padding(bottom = 12.dp),
                expanded = true
            )
        }
    }
}

@Composable
fun FileFolderGridView(
    viewMode: FileFolderViewMode,
    allItems: List<JetzyElement>,
    highlightedItems: SnapshotStateList<Int>
) {
    val density = LocalDensity.current

    var cellWidth by remember { mutableStateOf(1.dp) }
    val listIsEmpty by derivedStateOf { allItems.isEmpty() }
    if (listIsEmpty) {
        Text(
            text = when (viewMode) {
                FileFolderViewMode.Files -> "No file(s) added yet."
                FileFolderViewMode.Folders -> "No folder(s) added yet."
            },
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = 12.dp).fillMaxSize(),
            style = MaterialTheme.typography.labelMediumEmphasized
        )
    } else {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(cellWidth),
            modifier = Modifier.fillMaxSize().onGloballyPositioned {
                cellWidth = with(density) { it.size.width.toDp() / 4 }
            }
        ) {
            itemsIndexed(items = allItems) { i, f ->
                val isHighlighted by derivedStateOf { highlightedItems.contains(i) }
                Box(Modifier.size(cellWidth)) {
                    Column(
                        horizontalAlignment = CenterHorizontally,
                        modifier = Modifier
                            .size(cellWidth)
                            .combinedClickable(
                                onClick = {
                                    //TODO
                                },
                                onLongClick = {
                                    if (highlightedItems.contains(i)) {
                                        highlightedItems.remove(i)
                                    } else {
                                        highlightedItems.add(i)
                                    }
                                },
                                indication = ripple(color = jetzyYellow),
                                interactionSource = null
                            )
                            .border((0.1).dp, color = scheme.onSurface.copy(alpha = 0.05f), shape = RectangleShape)

                    ) {
                        Icon(
                            imageVector = when (viewMode) {
                                FileFolderViewMode.Files -> Icons.AutoMirrored.Filled.InsertDriveFile
                                FileFolderViewMode.Folders -> Icons.Filled.Folder
                            }, null,
                            modifier = Modifier.weight(1f).aspectRatio(1f).padding(8.dp)
                        )

                        Text(
                            text = (f as? JetzyElement.File)?.file?.name ?: (f as? JetzyElement.Folder)?.folder?.name ?: "",
                            fontSize = 7.ssp,
                            modifier = Modifier.width(cellWidth - 16.dp).basicMarquee(iterations = 3)
                        )
                    }

                    if (isHighlighted) {
                        Surface(modifier = Modifier.size(cellWidth), color = Color.DarkGray.copy(alpha = 0.4f)) { }
                        Icon(Icons.Filled.Check, null, Modifier.align(TopEnd).padding(12.dp))
                    }
                }
            }
        }
    }
}
