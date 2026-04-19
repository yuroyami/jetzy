package jetzy.ui.main

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import jetzy.p2p.P2pOperation
import jetzy.shared.generated.resources.Res
import jetzy.shared.generated.resources.choose_files
import jetzy.shared.generated.resources.pick_files_to_send
import jetzy.shared.generated.resources.proceed
import jetzy.shared.generated.resources.receiving_from
import jetzy.shared.generated.resources.select_files_to_send
import jetzy.shared.generated.resources.select_operation
import jetzy.shared.generated.resources.select_platform
import jetzy.shared.generated.resources.sending_label
import jetzy.shared.generated.resources.sending_to
import jetzy.shared.generated.resources.welcome_subtitle
import jetzy.shared.generated.resources.welcome_title
import jetzy.shared.generated.resources.what_to_do
import jetzy.shared.generated.resources.zero_files_selected
import jetzy.theme.sdp
import jetzy.theme.ssp
import jetzy.ui.LocalViewmodel
import jetzy.ui.Screen
import jetzy.utils.ComposeUtils.JetzyText
import jetzy.utils.ComposeUtils.scheme
import jetzy.utils.Platform
import org.jetbrains.compose.resources.stringResource

@Composable
fun MainScreenUI() {
    val haptic = LocalHapticFeedback.current
    val viewmodel = LocalViewmodel.current

    val operation by viewmodel.currentOperation.collectAsState()
    val peerPlatform by viewmodel.currentPeerPlatform.collectAsState()

    // Pre-resolve strings for use in non-composable lambdas
    val selectOperationStr = stringResource(Res.string.select_operation)
    val selectPlatformStr = stringResource(Res.string.select_platform)
    val selectFilesStr = stringResource(Res.string.select_files_to_send)

    Scaffold(
        bottomBar = {
            Column {
                HorizontalDivider()
                BottomAppBar {
                    FilledTonalButton(
                        modifier = Modifier.fillMaxWidth().height(48.sdp).padding(3.sdp),
                        onClick = c@{
                            if (viewmodel.currentOperation.value == null) {
                                viewmodel.snacky(selectOperationStr)
                                haptic.performHapticFeedback(HapticFeedbackType.Reject)
                                return@c
                            }
                            if (viewmodel.currentPeerPlatform.value == null) {
                                viewmodel.snacky(selectPlatformStr)
                                haptic.performHapticFeedback(HapticFeedbackType.Reject)
                                return@c
                            }

                            if (viewmodel.currentOperation.value == P2pOperation.SEND && viewmodel.elementsToSend.isEmpty()) {
                                viewmodel.snacky(selectFilesStr)
                                haptic.performHapticFeedback(HapticFeedbackType.Reject)
                                return@c
                            }

                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)

                            viewmodel.currentOperation.value?.let { operation ->
                                viewmodel.currentPeerPlatform.value?.let { platform ->
                                    viewmodel.proceedFromMainScreen(platform, operation)
                                }
                            }
                        },
                        shape = RoundedCornerShape(8.sdp)
                    ) {
                        Text(stringResource(Res.string.proceed), style = MaterialTheme.typography.titleLarge)
                    }
                }
            }
        }
    ) { pv ->
        val parentScrollState = rememberScrollState()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(pv)
                .verticalScroll(parentScrollState)
                .animateContentSize(),
            horizontalAlignment = CenterHorizontally,
            verticalArrangement = Arrangement.Top
        ) {

            Column(
                horizontalAlignment = CenterHorizontally,
                modifier = Modifier.fillMaxWidth().padding(6.sdp)
            ) {
                JetzyText(
                    text = stringResource(Res.string.welcome_title),
                    size = 16.ssp,
                    strokeThickness = 8f,
                )

                Text(
                    text = stringResource(Res.string.welcome_subtitle),
                    style = MaterialTheme.typography.bodyMediumEmphasized,
                )
            }

            MainScreenSurface {
                Column(
                    horizontalAlignment = CenterHorizontally,
                    modifier = Modifier.fillMaxWidth().padding(6.sdp).animateContentSize()
                ) {
                    if (operation == null) {
                        Text(
                            text = stringResource(Res.string.what_to_do),
                            modifier = Modifier.fillMaxWidth().padding(8.sdp),
                            style = MaterialTheme.typography.titleMedium,
                            textAlign = TextAlign.Center,
                            color = lerp(scheme.onSurface, scheme.outlineVariant, 0.65f)
                        )
                    }

                    Row(modifier = Modifier.fillMaxWidth()) {
                        OperationButton(operation = P2pOperation.SEND, modifier = Modifier.weight(1f).padding(horizontal = 3.sdp))
                        OperationButton(operation = P2pOperation.RECEIVE, modifier = Modifier.weight(1f).padding(horizontal = 3.sdp))
                    }
                }
            }

            // Platform selection — shown right after operation is chosen
            if (operation != null) {
                MainScreenSurface {
                    Column(
                        horizontalAlignment = CenterHorizontally,
                        modifier = Modifier.fillMaxWidth().padding(6.sdp)
                    ) {
                        val text = when (operation) {
                            P2pOperation.SEND -> stringResource(Res.string.sending_to)
                            P2pOperation.RECEIVE -> stringResource(Res.string.receiving_from)
                            null -> ""
                        }

                        Text(
                            text = text,
                            modifier = Modifier.fillMaxWidth().padding(8.sdp),
                            style = MaterialTheme.typography.titleMedium,
                            textAlign = TextAlign.Center,
                            color = lerp(scheme.onSurface, scheme.outlineVariant, 0.65f)
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth().padding(3.sdp),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            val platforms = listOf(Platform.Android, Platform.IOS/*, Platform.PC, Platform.Web*/)
                            platforms.forEach { platform ->
                                val isSelected by derivedStateOf { platform == peerPlatform }
                                VerticalCardButton(
                                    modifier = Modifier.weight(1f).padding(horizontal = 2.sdp),
                                    text = platform.peerLabel,
                                    icon = platform.peerIcon,
                                    selectedIconTint = platform.peerBrandColor,
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

            // File picking — shown after platform is selected for SEND
            if (operation == P2pOperation.SEND && peerPlatform != null) {
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(8.sdp),
                    tonalElevation = 0.dp,
                    shadowElevation = 8.dp
                ) {
                    Column(
                        horizontalAlignment = CenterHorizontally,
                        modifier = Modifier.fillMaxWidth().padding(6.sdp)
                    ) {
                        Text(
                            text = stringResource(Res.string.pick_files_to_send),
                            modifier = Modifier.fillMaxWidth().padding(8.sdp),
                            style = MaterialTheme.typography.titleMedium,
                            textAlign = TextAlign.Center,
                            color = lerp(scheme.onSurface, scheme.outlineVariant, 0.65f)
                        )

                        FilledTonalButton(
                            modifier = Modifier.fillMaxWidth(0.7f).padding(3.sdp),
                            onClick = {
                                viewmodel.navigateTo(Screen.FilePickingScreen)
                            },
                            shape = RoundedCornerShape(20)
                        ) {
                            Text(stringResource(Res.string.choose_files), style = MaterialTheme.typography.titleLarge)
                        }

                        val filesForSending by viewmodel.file2Send.collectAsState()
                        val foldersForSending by viewmodel.folders2Send.collectAsState()
                        val photosForSending by viewmodel.photos2Send.collectAsState()
                        val videosForSending by viewmodel.videos2Send.collectAsState()
                        val textForSending by viewmodel.texts2Send.collectAsState()

                        val zeroFilesStr = stringResource(Res.string.zero_files_selected)

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
                                if (it.isEmpty()) zeroFilesStr
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

            Spacer(Modifier.height(70.sdp))
        }
    }
}

@Composable
fun MainScreenSurface(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(8.sdp),
        tonalElevation = 0.dp,
        shadowElevation = 8.dp,
        shape = RoundedCornerShape(8.sdp),
        content = content
    )
}
