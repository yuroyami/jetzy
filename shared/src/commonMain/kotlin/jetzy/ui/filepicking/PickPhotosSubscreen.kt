package jetzy.ui.filepicking

import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallExtendedFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment.Companion.BottomEnd
import androidx.compose.ui.Alignment.Companion.BottomStart
import androidx.compose.ui.Alignment.Companion.CenterHorizontally
import androidx.compose.ui.Alignment.Companion.TopEnd
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.vinceglb.filekit.dialogs.FileKitMode
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import jetzy.models.JetzyElement
import jetzy.theme.jetzyYellow
import jetzy.theme.sdp
import jetzy.theme.ssp
import jetzy.shared.generated.resources.Res
import jetzy.shared.generated.resources.exclude
import jetzy.shared.generated.resources.excluded_images
import jetzy.shared.generated.resources.no_photos_added
import jetzy.shared.generated.resources.select_photos_btn
import jetzy.shared.generated.resources.select_photos_desc
import jetzy.ui.LocalViewmodel
import jetzy.utils.ComposeUtils.scheme
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

@Composable
fun PickPhotosSubscreen() {
    val viewmodel = LocalViewmodel.current
    val density = LocalDensity.current
    val onePx = remember(density) { with(density) { 1.toDp() } }

    val gridState = rememberLazyGridState()

    val longclickedPhotos = remember { mutableStateListOf<Int>() }

    val photoPicker = rememberFilePickerLauncher(
        type = FileKitType.Image, mode = FileKitMode.Multiple(),
    ) {
        it?.map { image -> JetzyElement.Photo(image) }?.forEach { photo ->
            viewmodel.elementsToSend.add(photo)
        }
    }

    val photosForSending by derivedStateOf { viewmodel.elementsToSend.filter { it is JetzyElement.Photo } }
    val listIsEmpty by derivedStateOf { photosForSending.isEmpty() }

    Box(modifier = Modifier.fillMaxSize()) {

        Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = CenterHorizontally) {
            Text(
                text = stringResource(Res.string.select_photos_desc),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 8.sdp, top = 16.sdp)
            )

            Surface(
                modifier = Modifier.fillMaxSize().padding(top = 10.sdp, start = 6.sdp, end = 6.sdp, bottom = 60.sdp),
                tonalElevation = 28.dp,
                shadowElevation = 3.dp,
                shape = RoundedCornerShape(6.sdp)
            ) {
                var cellWidth by remember { mutableStateOf(1.dp) }
                if (listIsEmpty) {
                    Text(
                        text = stringResource(Res.string.no_photos_added),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(vertical = 8.sdp).fillMaxSize(),
                        style = MaterialTheme.typography.labelMediumEmphasized
                    )
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(cellWidth),
                        modifier = Modifier.fillMaxSize().onGloballyPositioned {
                            cellWidth = with(density) { it.size.width.toDp() / 3 }
                        },
                        state = gridState
                    ) {
                        itemsIndexed(viewmodel.elementsToSend ) { i, photoFile ->
                            val isHighlighted by derivedStateOf { longclickedPhotos.contains(i) }
                            Box(Modifier.size(cellWidth)) {
                                Icon(Icons.Filled.Check, null, Modifier.align(TopEnd).padding(8.sdp))

                                var recomposePlease by remember { mutableIntStateOf(0) }

                                LaunchedEffect(i) {
                                    //There is a bug in FileKit Coil that causes images to not continue composing to full quality
                                    //So we force recomposition
                                    delay(300)
                                    recomposePlease += 1
                                }

                                key("$i $recomposePlease") {
                                    AsyncImage(
                                        model = photoFile,
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        colorFilter = if (isHighlighted) ColorFilter.tint(jetzyYellow.copy(alpha = 0.75f), blendMode = BlendMode.DstOut) else null,
                                        modifier = Modifier.size(cellWidth).border(
                                            width = onePx,
                                            color = scheme.onSurface,
                                            shape = RectangleShape
                                        ).combinedClickable(
                                            onClick = {
                                                //TODO Preview
                                            },
                                            onLongClick = {
                                                if (isHighlighted) {
                                                    longclickedPhotos.remove(i)
                                                } else {
                                                    longclickedPhotos.add(i)
                                                }
                                            },
                                            indication = ripple(color = jetzyYellow),
                                            interactionSource = null
                                        )
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
                Icon(Icons.Filled.Image, null)
            },
            onClick = {
                photoPicker.launch()
            },
            text = { Text(stringResource(Res.string.select_photos_btn)) },
            modifier = Modifier.align(BottomEnd).padding(6.sdp).padding(bottom = 8.sdp),
            expanded = true
        )

        if (longclickedPhotos.isNotEmpty()) {
            SmallExtendedFloatingActionButton(
                icon = {
                    Icon(Icons.Filled.ClearAll, null)
                },
                onClick = {
                    val count = longclickedPhotos.size

                    val processedIndices = mutableListOf<Int>()
                    for (photoIndex in longclickedPhotos) {
                        viewmodel.elementsToSend.removeAt(photoIndex)
                        processedIndices.add(photoIndex)
                    }
                    longclickedPhotos.removeAll(processedIndices)

                    viewmodel.snacky("Excluded $count images from the list")

                },
                text = { Text(stringResource(Res.string.exclude), fontSize = 10.ssp) },
                modifier = Modifier.align(BottomStart).padding(6.sdp).padding(bottom = 8.sdp),
                expanded = true
            )
        }

    }
}