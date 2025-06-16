package jetzy.screens.sendscreens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.FilePresent
import androidx.compose.material3.Icon
import androidx.compose.material3.MediumExtendedFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment.Companion.BottomEnd
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.vinceglb.filekit.dialogs.FileKitMode
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import jetzy.screens.LocalViewmodel
import jetzy.utils.rememberDirectoryPickerLauncher

@Composable
fun SendFilesScreenUI(modifier: Modifier = Modifier) {
    val viewmodel = LocalViewmodel.current

    val filePicker = rememberFilePickerLauncher(
        type = FileKitType.File(), mode = FileKitMode.Multiple(),
    ) { files ->
        files?.forEach {
            viewmodel.files.add(it)
        }
    }

    val folderPicker = rememberDirectoryPickerLauncher {folder ->
        folder?.let { viewmodel.files.add(it) }

    }

    Box(modifier = Modifier.fillMaxSize()) {
        Surface(
            modifier = Modifier.fillMaxWidth()
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxWidth()
            ) {

            }
        }

        MediumExtendedFloatingActionButton(
            icon = {
                Icon(Icons.Filled.FilePresent, null)
            },
            onClick = {

            },
            text = { Text("Select File(s)") },
            modifier = Modifier.align(BottomEnd).padding(8.dp).padding(bottom = 12.dp),
            expanded = true
        )

        if (folderPicker != null) {
            MediumExtendedFloatingActionButton(
                icon = {
                    Icon(Icons.Filled.CreateNewFolder, null)
                },
                onClick = {

                },
                text = { Text("Select Folder") },
                modifier = Modifier.align(BottomEnd).padding(8.dp).padding(bottom = 112.dp),
                expanded = true
            )
        }
    }
}