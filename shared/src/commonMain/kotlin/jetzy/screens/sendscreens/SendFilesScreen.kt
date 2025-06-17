package jetzy.screens.sendscreens

import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment.Companion.BottomEnd
import androidx.compose.ui.Alignment.Companion.CenterHorizontally
import androidx.compose.ui.Alignment.Companion.TopEnd
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.FileKitMode
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.name
import jetzy.p2p.ComposeUtils.scheme
import jetzy.screens.LocalViewmodel
import jetzy.theme.jetzyYellow
import jetzy.theme.ssp
import jetzy.utils.rememberDirectoryPickerLauncher


enum class FileFolderViewMode { Files, Folders }

@Composable
fun SendFilesScreenUI() {
    val viewmodel = LocalViewmodel.current

    var tab by remember { mutableStateOf(FileFolderViewMode.Files) }

    val filePicker = rememberFilePickerLauncher(
        type = FileKitType.File(), mode = FileKitMode.Multiple(),
    ) { files ->
        files?.forEach {
            viewmodel.files.add(it)
        }
        viewmodel.snacky("Added ${files?.size} item(s)")
        tab = FileFolderViewMode.Files
    }

    val folderPicker = rememberDirectoryPickerLauncher { folder ->
        folder?.let { viewmodel.files.add(it) }
        viewmodel.snacky("Added folder: ${folder?.name}")
        tab = FileFolderViewMode.Folders
    }

    val noFilesSelected by derivedStateOf { viewmodel.files.isEmpty() }
    val noFoldersSelected by derivedStateOf { viewmodel.folders.isEmpty() }

    Box(modifier = Modifier.fillMaxSize()) {

        Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = CenterHorizontally) {
            Text(
                text = "Select files (and/or folders) to send to your peer.\nFiles can be of any size and any format.",
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 12.dp, top = 28.dp),
                fontSize = 10.ssp
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

                    //key(tab) {
                    FileFolderGridView(
                        viewMode = FileFolderViewMode.Files,
                        isAnythingSelected = when (tab) {
                            FileFolderViewMode.Files -> noFilesSelected
                            FileFolderViewMode.Folders -> noFoldersSelected
                        },
                        items = when (tab) {
                            FileFolderViewMode.Files -> viewmodel.files
                            FileFolderViewMode.Folders -> viewmodel.folders
                        }
                    )
                    //}
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
    }
}

@Composable
fun FileFolderGridView(
    viewMode: FileFolderViewMode,
    items: SnapshotStateList<PlatformFile>,
    isAnythingSelected: Boolean
) {
    val viewmodel = LocalViewmodel.current
    val density = LocalDensity.current

    var cellWidth by remember { mutableStateOf(1.dp) }
    if (isAnythingSelected) {
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
            itemsIndexed(items = items) { i, f ->
                val isHighlighted = true //todo by derivedStateOf { longclickedPhotos.contains(i) }
                Box(Modifier.size(cellWidth)) {
                    Icon(Icons.Filled.Check, null, Modifier.align(TopEnd).padding(12.dp))

                    Column(
                        modifier = Modifier
                            .size(cellWidth)
                            .combinedClickable(
                                onClick = {
                                    //TODO
                                },
                                onLongClick = {
                                    if (isHighlighted) {
                                        //longclickedPhotos.remove(i)
                                    } else {
                                        //longclickedPhotos.add(i)
                                    }
                                },
                                indication = ripple(color = jetzyYellow),
                                interactionSource = null
                            ).border((0.1).dp, color = scheme.onSurface.copy(alpha = 0.05f), shape = RectangleShape),
                        horizontalAlignment = CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Folder, null,
                            modifier = Modifier.size(cellWidth * 0.75f)
                        )

                        Text(
                            text = f.name,
                            fontSize = 7.ssp,
                            modifier = Modifier.width(cellWidth - 16.dp).basicMarquee(iterations = 3)
                        )
                    }
                }
            }
        }
    }
}
