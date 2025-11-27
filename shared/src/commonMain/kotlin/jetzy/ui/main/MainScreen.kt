package jetzy.ui.main

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment.Companion.CenterHorizontally
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import jetzy.p2p.P2PMethodRegistry
import jetzy.theme.sdp
import jetzy.theme.ssp
import jetzy.ui.Screen
import jetzy.ui.adam.LocalViewmodel
import jetzy.utils.ComposeUtils.JetzyText
import jetzy.utils.ComposeUtils.scheme
import jetzy.utils.Platform

@Composable
fun MainScreenUI() {
    val haptic = LocalHapticFeedback.current
    val viewmodel = LocalViewmodel.current

    val operation by viewmodel.currentOperation.collectAsState()
    val peerPlatform by viewmodel.currentPeerPlatform.collectAsState()
    val transferMethod by viewmodel.currentTransferMethod.collectAsState()

    Scaffold(
        bottomBar = {
            Column {
                HorizontalDivider()
                BottomAppBar {
                    FilledTonalButton(
                        modifier = Modifier.fillMaxWidth().height(58.sdp).padding(4.dp),
                        onClick = c@{
                            if (viewmodel.currentOperation.value == null) {
                                viewmodel.snacky("Select an operation!")
                                haptic.performHapticFeedback(HapticFeedbackType.Reject)
                                return@c
                            }
                            if (viewmodel.currentPeerPlatform.value == null) {
                                viewmodel.snacky("Select which platform your peer has!")
                                haptic.performHapticFeedback(HapticFeedbackType.Reject)
                                return@c
                            }

                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)

                            viewmodel.currentOperation.value?.let { operation ->
                                viewmodel.navigateTo(
                                    when (operation) {
                                        Operation.SEND -> Screen.FilePickingScreen
                                        Operation.RECEIVE -> Screen.ReceiveScreen
                                    }
                                )
                            }
                        },
                        shape = RoundedCornerShape(8.sdp)
                    ) {
                        Text("Proceed", style = MaterialTheme.typography.titleLarge)
                    }
                }
            }
        }
    ) { pv ->
        val parentScrollState = rememberScrollState()
        Column(
            modifier = Modifier.fillMaxSize().padding(pv).verticalScroll(parentScrollState),
            horizontalAlignment = CenterHorizontally,
            verticalArrangement = Arrangement.SpaceEvenly
        ) {

            AnimatedVisibility(visible = operation == null) {
                Column(
                    horizontalAlignment = CenterHorizontally,
                    modifier = Modifier.fillMaxWidth().padding(8.dp)
                ) {
                    JetzyText(
                        text = "Welcome to Jetzy",
                        size = 16.ssp,
                        strokeThickness = 8f,
                    )

                    Text(
                        text = "Quickly send & receive files across different platforms",
                        style = MaterialTheme.typography.bodyMediumEmphasized,
                    )
                }

                Spacer(Modifier.height(8.sdp))
            }

            Surface(
                modifier = Modifier.fillMaxWidth().padding(8.sdp),
                tonalElevation = 0.dp,
                shadowElevation = 8.dp
            ) {
                Column(
                    horizontalAlignment = CenterHorizontally,
                    modifier = Modifier.fillMaxWidth().padding(8.dp)
                ) {
                    AnimatedVisibility(operation == null) {
                        Text(
                            text = "What would you like to do?",
                            modifier = Modifier.fillMaxWidth().padding(8.sdp),
                            style = MaterialTheme.typography.titleMedium,
                            textAlign = TextAlign.Center,
                            color = lerp(scheme.onSurface, scheme.outlineVariant, 0.65f)
                        )
                    }

                    Row(modifier = Modifier.fillMaxWidth()) {
                        OperationButton(operation = Operation.SEND, modifier = Modifier.weight(1f).padding(horizontal = 4.dp))
                        OperationButton(operation = Operation.RECEIVE, modifier = Modifier.weight(1f).padding(horizontal = 4.dp))
                    }
                }
            }

            AnimatedVisibility(operation == Operation.SEND) {
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(8.sdp),
                    tonalElevation = 0.dp,
                    shadowElevation = 8.dp
                ) {
                    Column(
                        horizontalAlignment = CenterHorizontally,
                        modifier = Modifier.fillMaxWidth().padding(8.dp)
                    ) {
                        Text(
                            text = "Pick the files to send",
                            modifier = Modifier.fillMaxWidth().padding(8.sdp),
                            style = MaterialTheme.typography.titleMedium,
                            textAlign = TextAlign.Center,
                            color = lerp(scheme.onSurface, scheme.outlineVariant, 0.65f)
                        )

                        FilledTonalButton(
                            modifier = Modifier.fillMaxWidth(0.7f).padding(4.dp),
                            onClick = {
                                viewmodel.navigateTo(Screen.FilePickingScreen)
                            },
                            shape = RoundedCornerShape(20)
                        ) {
                            Text("Choose files", style = MaterialTheme.typography.titleLarge)
                        }

                        val filesForSending by viewmodel.file2Send.collectAsState()
                        val foldersForSending by viewmodel.folders2Send.collectAsState()
                        val photosForSending by viewmodel.photos2Send.collectAsState()
                        val videosForSending by viewmodel.videos2Send.collectAsState()
                        val textForSending by viewmodel.texts2Send.collectAsState()

                        val countText = remember(
                            filesForSending.size,
                            foldersForSending.size,
                            photosForSending.size,
                            videosForSending.size,
                            textForSending.size
                        ) {
                            buildList {
                                if (filesForSending.isNotEmpty()) add("${filesForSending.size} file${if (filesForSending.size > 1) "s" else ""}")
                                if (foldersForSending.isNotEmpty()) add("${foldersForSending.size} folder${if (foldersForSending.size > 1) "s" else ""}")
                                if (photosForSending.isNotEmpty()) add("${photosForSending.size} photo${if (photosForSending.size > 1) "s" else ""}")
                                if (videosForSending.isNotEmpty()) add("${videosForSending.size} video${if (videosForSending.size > 1) "s" else ""}")
                                if (textForSending.isNotEmpty()) add("${textForSending.size} text${if (textForSending.size > 1) "s" else ""}")
                            }.joinToString(", ").let {
                                if (it.isEmpty()) "0 files selected"
                                else "Sending: $it"
                            }
                        }

                        Text(
                            text = countText,
                            modifier = Modifier.fillMaxWidth().padding(8.sdp),
                            style = MaterialTheme.typography.titleMedium,
                            textAlign = TextAlign.Center,
                            color = lerp(scheme.onSurface, scheme.outlineVariant, 0.65f)
                        )
                    }
                }
            }

            val showPeerPlatform by derivedStateOf {
                (operation == Operation.SEND && viewmodel.elementsToSend.isNotEmpty()) || operation == Operation.RECEIVE
            }

            AnimatedVisibility(showPeerPlatform) {
                operation?.let {
                    Surface(
                        modifier = Modifier.fillMaxWidth().padding(8.sdp),
                        tonalElevation = 0.dp,
                        shadowElevation = 8.dp
                    ) {
                        Column(
                            horizontalAlignment = CenterHorizontally,
                            modifier = Modifier.fillMaxWidth().padding(8.dp)
                        ) {
                            AnimatedVisibility(peerPlatform == null) {
                                val text = when (it) {
                                    Operation.SEND -> "Sending to..."
                                    Operation.RECEIVE -> "Receiving from..."
                                }

                                Text(
                                    text = text,
                                    modifier = Modifier.fillMaxWidth().padding(8.sdp),
                                    style = MaterialTheme.typography.titleMedium,
                                    textAlign = TextAlign.Center,
                                    color = lerp(scheme.onSurface, scheme.outlineVariant, 0.65f)
                                )
                            }


                            Row(
                                modifier = Modifier.fillMaxWidth().padding(4.dp),
                                horizontalArrangement = Arrangement.SpaceEvenly
                            ) {
                                val platforms = listOf(Platform.Android, Platform.IOS, Platform.PC, Platform.Web)
                                platforms.forEach { platform ->
                                    val isSelected by derivedStateOf { platform == peerPlatform }
                                    VerticalCardButton(
                                        modifier = Modifier.weight(1f).padding(horizontal = 3.dp),
                                        text = platform.label,
                                        icon = platform.icon,
                                        selectedIconTint = platform.brandColor,
                                        isSelected = isSelected,
                                        onClick = {
                                            viewmodel.currentPeerPlatform.value = platform
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            AnimatedVisibility(peerPlatform != null) {
                peerPlatform?.let { pp ->
                    Surface(
                        modifier = Modifier.fillMaxWidth().padding(8.sdp),
                        tonalElevation = 0.dp,
                        shadowElevation = 8.dp
                    ) {
                        Column(
                            horizontalAlignment = CenterHorizontally,
                            modifier = Modifier.fillMaxWidth().padding(8.dp)
                        ) {
                            Text(
                                text = "Select transfer technology",
                                modifier = Modifier.fillMaxWidth().padding(8.sdp),
                                style = MaterialTheme.typography.titleMedium,
                                textAlign = TextAlign.Center,
                                color = lerp(scheme.onSurface, scheme.outlineVariant, 0.65f)
                            )

                            FlowRow(
                                modifier = Modifier.fillMaxWidth().padding(4.dp),
                                horizontalArrangement = Arrangement.SpaceEvenly,
                                maxItemsInEachRow = 4,
                                maxLines = 2
                            ) {
                                val transferMethods = remember(peerPlatform) { P2PMethodRegistry.getAvailableMethods(pp) }
                                transferMethods.forEach { method ->
                                    val isSelected by derivedStateOf { method == transferMethod }
                                    VerticalCardButton(
                                        modifier = Modifier.width(82.dp),
                                        text = method.displayName,
                                        icon = method.icon,
                                        selectedIconTint = Color(10, 50, 200),
                                        isSelected = isSelected,
                                        onClick = {
                                            viewmodel.currentTransferMethod.value = method
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(70.sdp))
        }
    }
}