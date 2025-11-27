package jetzy.ui.filepicking

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.Icon
import androidx.compose.material3.SmallExtendedFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment.Companion.BottomEnd
import androidx.compose.ui.Alignment.Companion.CenterHorizontally
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.vinceglb.filekit.dialogs.FileKitMode
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import jetzy.models.JetzyElement
import jetzy.ui.adam.LocalViewmodel

@Composable
fun PickVideosSubscreen() {
    val viewmodel = LocalViewmodel.current

    val videoPicker = rememberFilePickerLauncher(
        type = FileKitType.Video, mode = FileKitMode.Multiple(),
    ) { files ->
        files?.map { JetzyElement.Video(it) }?.forEach {
            viewmodel.elementsToSend.add(it)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {

        Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = CenterHorizontally) {
            Text(
                text = "Select videos to send to your peer.",
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 12.dp, top = 28.dp)
            )

            Surface(
                modifier = Modifier.fillMaxSize().padding(top = 16.dp, start = 8.dp, end = 8.dp, bottom = 92.dp),
                tonalElevation = 28.dp,
                shadowElevation = 3.dp,
                shape = RoundedCornerShape(6.dp)
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize()
                ) {

                }
            }
        }


        SmallExtendedFloatingActionButton(
            icon = {
                Icon(Icons.Filled.VideoFile, null)
            },
            onClick = {
                videoPicker.launch()
            },
            text = { Text("Select Video(s)") },
            modifier = Modifier.align(BottomEnd).padding(8.dp).padding(bottom = 12.dp),
            expanded = true
        )
    }
}