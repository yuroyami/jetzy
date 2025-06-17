package jetzy.screens.sendscreens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.FilePresent
import androidx.compose.material3.Icon
import androidx.compose.material3.SmallExtendedFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment.Companion.BottomEnd
import androidx.compose.ui.Alignment.Companion.CenterHorizontally
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.vinceglb.filekit.dialogs.FileKitMode
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.name
import jetzy.screens.LocalViewmodel
import jetzy.utils.rememberDirectoryPickerLauncher

@Composable
fun SendFilesScreenUI() {
    val scope = rememberCoroutineScope()
    val viewmodel = LocalViewmodel.current

    val filePicker = rememberFilePickerLauncher(
        type = FileKitType.File(), mode = FileKitMode.Multiple(),
    ) { files ->
        files?.forEach {
            viewmodel.files.add(it)
        }
    }

    val folderPicker = rememberDirectoryPickerLauncher { folder ->
        folder?.let { viewmodel.files.add(it) }

    }

    Box(modifier = Modifier.fillMaxSize()) {

        Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = CenterHorizontally) {
            Text(
                text = "Select files to send to your peer.\nFiles can be of any size and any format.",
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 12.dp, top = 28.dp)
            )

            Surface(
                modifier = Modifier.fillMaxSize().padding(top = 16.dp, start = 8.dp, end = 8.dp, bottom = 168.dp),
                tonalElevation = 34.dp,
                shadowElevation = 8.dp,
                shape = RoundedCornerShape(6.dp)
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(viewmodel.files) {
                        TextButton(
                            onClick = {}
                        ) {
                            Text(it.name)
                        }
                    }
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