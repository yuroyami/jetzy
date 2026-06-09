package jetzy.ui.main

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment.Companion.CenterHorizontally
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import jetzy.shared.generated.resources.Res
import jetzy.shared.generated.resources.choose_files
import jetzy.shared.generated.resources.welcome_subtitle
import jetzy.shared.generated.resources.welcome_title
import jetzy.theme.sdp
import jetzy.theme.ssp
import jetzy.ui.LocalViewmodel
import jetzy.ui.Screen
import jetzy.utils.ComposeUtils.JetzyText
import jetzy.utils.ComposeUtils.scheme
import org.jetbrains.compose.resources.stringResource

/**
 * The home screen, collapsed to a single gate-free surface. The old Send/Receive toggle and the
 * peer-platform picker are gone: direction is *derived* (staged files ⇒ you send; nothing staged ⇒
 * you receive) by [jetzy.p2p.DirectionResolver] on the wire, and the transport is the
 * platform-agnostic mDNS default ([jetzy.p2p.P2pPlatformCallback.getDefaultP2pManager]) with a
 * per-host ladder behind "Try a different transport". So the only input here is "add files (or
 * don't)" and one Connect tap.
 */
@Composable
fun MainScreenUI() {
    val haptic = LocalHapticFeedback.current
    val viewmodel = LocalViewmodel.current

    val filesForSending by viewmodel.file2Send.collectAsState()
    val foldersForSending by viewmodel.folders2Send.collectAsState()
    val photosForSending by viewmodel.photos2Send.collectAsState()
    val videosForSending by viewmodel.videos2Send.collectAsState()
    val textForSending by viewmodel.texts2Send.collectAsState()

    val hasFiles = filesForSending.isNotEmpty() || foldersForSending.isNotEmpty() ||
        photosForSending.isNotEmpty() || videosForSending.isNotEmpty() || textForSending.isNotEmpty()

    Scaffold(
        bottomBar = {
            Column {
                HorizontalDivider()
                BottomAppBar {
                    FilledTonalButton(
                        modifier = Modifier.fillMaxWidth().height(48.sdp).padding(3.sdp),
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            // No operation/platform args: direction is derived from what's staged,
                            // and the bootstrap transport is the mDNS default. The wire stays
                            // authoritative and may flip the direction once HELLOs are exchanged.
                            viewmodel.proceed()
                        },
                        shape = RoundedCornerShape(8.sdp)
                    ) {
                        // Single action that reads either way: "Send" when files are staged,
                        // "Connect to receive" when the tray is empty.
                        Text(
                            if (hasFiles) "Send" else "Connect to receive",
                            style = MaterialTheme.typography.titleLarge,
                        )
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

            // The one and only input: add files to share (optional — connect with an empty tray to
            // receive). No mode toggle, no platform guess.
            MainScreenSurface {
                Column(
                    horizontalAlignment = CenterHorizontally,
                    modifier = Modifier.fillMaxWidth().padding(6.sdp).animateContentSize()
                ) {
                    Text(
                        text = "Add files to share",
                        modifier = Modifier.fillMaxWidth().padding(8.sdp),
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                        color = lerp(scheme.onSurface, scheme.outlineVariant, 0.65f)
                    )

                    FilledTonalButton(
                        modifier = Modifier.fillMaxWidth(0.7f).padding(3.sdp),
                        onClick = { viewmodel.navigateTo(Screen.FilePickingScreen) },
                        shape = RoundedCornerShape(20)
                    ) {
                        Text(stringResource(Res.string.choose_files), style = MaterialTheme.typography.titleLarge)
                    }

                    val countText = remember(
                        filesForSending.size,
                        foldersForSending.size,
                        photosForSending.size,
                        videosForSending.size,
                        textForSending.size,
                    ) {
                        buildList {
                            if (filesForSending.isNotEmpty()) add("${filesForSending.size} file${if (filesForSending.size > 1) "s" else ""}")
                            if (foldersForSending.isNotEmpty()) add("${foldersForSending.size} folder${if (foldersForSending.size > 1) "s" else ""}")
                            if (photosForSending.isNotEmpty()) add("${photosForSending.size} photo${if (photosForSending.size > 1) "s" else ""}")
                            if (videosForSending.isNotEmpty()) add("${videosForSending.size} video${if (videosForSending.size > 1) "s" else ""}")
                            if (textForSending.isNotEmpty()) add("${textForSending.size} text${if (textForSending.size > 1) "s" else ""}")
                        }.joinToString(", ").let {
                            if (it.isEmpty()) "Nothing added — connect with an empty tray to receive"
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

            Spacer(Modifier.height(70.sdp))
        }
    }
}

@Composable
fun MainScreenSurface(content: @Composable () -> Unit) {
    androidx.compose.material3.Surface(
        modifier = Modifier.fillMaxWidth().padding(8.sdp),
        tonalElevation = 0.dp,
        shadowElevation = 8.dp,
        shape = RoundedCornerShape(8.sdp),
        content = content
    )
}
