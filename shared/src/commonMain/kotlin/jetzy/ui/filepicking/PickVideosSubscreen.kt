package jetzy.ui.filepicking

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallExtendedFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Alignment.Companion.BottomEnd
import androidx.compose.ui.Alignment.Companion.CenterHorizontally
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.vinceglb.filekit.dialogs.FileKitMode
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.name
import jetzy.models.JetzyElement
import jetzy.ui.LocalViewmodel

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

            val videosForSending by viewmodel.videos2Send.collectAsState()

            Surface(
                modifier = Modifier.fillMaxSize().padding(top = 16.dp, start = 8.dp, end = 8.dp, bottom = 92.dp),
                tonalElevation = 28.dp,
                shadowElevation = 3.dp,
                shape = RoundedCornerShape(6.dp)
            ) {
                if (videosForSending.isEmpty()) {
                    Text(
                        text = "No video(s) added yet.",
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(vertical = 12.dp).fillMaxSize(),
                        style = MaterialTheme.typography.labelMedium
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize()
                    ) {
                        itemsIndexed(videosForSending) { index, video ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.VideoFile,
                                    contentDescription = null,
                                    modifier = Modifier.size(28.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = video.video.name,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                IconButton(onClick = {
                                    viewmodel.elementsToSend.remove(video)
                                }) {
                                    Icon(
                                        imageVector = Icons.Filled.Close,
                                        contentDescription = "Remove",
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
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