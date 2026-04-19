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
import jetzy.theme.sdp
import io.github.vinceglb.filekit.dialogs.FileKitMode
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.name
import jetzy.models.JetzyElement
import jetzy.shared.generated.resources.Res
import jetzy.shared.generated.resources.no_videos_added
import jetzy.shared.generated.resources.remove
import jetzy.shared.generated.resources.select_videos_btn
import jetzy.shared.generated.resources.select_videos_desc
import jetzy.ui.LocalViewmodel
import org.jetbrains.compose.resources.stringResource

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
                text = stringResource(Res.string.select_videos_desc),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 8.sdp, top = 16.sdp)
            )

            val videosForSending by viewmodel.videos2Send.collectAsState()

            Surface(
                modifier = Modifier.fillMaxSize().padding(top = 10.sdp, start = 6.sdp, end = 6.sdp, bottom = 60.sdp),
                tonalElevation = 28.dp,
                shadowElevation = 3.dp,
                shape = RoundedCornerShape(6.sdp)
            ) {
                if (videosForSending.isEmpty()) {
                    Text(
                        text = stringResource(Res.string.no_videos_added),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(vertical = 8.sdp).fillMaxSize(),
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
                                    .padding(horizontal = 8.sdp, vertical = 4.sdp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.sdp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.VideoFile,
                                    contentDescription = null,
                                    modifier = Modifier.size(22.sdp),
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
                                        contentDescription = stringResource(Res.string.remove),
                                        modifier = Modifier.size(14.sdp)
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
            text = { Text(stringResource(Res.string.select_videos_btn)) },
            modifier = Modifier.align(BottomEnd).padding(6.sdp).padding(bottom = 8.sdp),
            expanded = true
        )
    }
}