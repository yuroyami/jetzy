package jetzy.ui.main

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment.Companion.CenterHorizontally
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import jetzy.shared.generated.resources.Res
import jetzy.shared.generated.resources.add_files_to_share
import jetzy.shared.generated.resources.added_items
import jetzy.shared.generated.resources.choose_files
import jetzy.shared.generated.resources.no_received_yet
import jetzy.shared.generated.resources.onboarding_got_it
import jetzy.shared.generated.resources.onboarding_step_network
import jetzy.shared.generated.resources.onboarding_step_open
import jetzy.shared.generated.resources.onboarding_step_pick
import jetzy.shared.generated.resources.onboarding_title
import jetzy.shared.generated.resources.received_files
import jetzy.shared.generated.resources.connect_to_receive
import jetzy.shared.generated.resources.send
import jetzy.shared.generated.resources.welcome_subtitle
import jetzy.shared.generated.resources.welcome_title
import jetzy.utils.JetzyPrefs
import jetzy.theme.sdp
import jetzy.theme.ssp
import jetzy.ui.LocalViewmodel
import jetzy.ui.Screen
import jetzy.utils.ComposeUtils.JetzyText
import jetzy.utils.ComposeUtils.scheme
import jetzy.utils.openReceivedLocation
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

    // First-run explainer: the core mechanic ("open Jetzy on BOTH devices, same network") is not
    // obvious, and previously the only hint lived buried in the empty-radar state. Shown once.
    var showOnboarding by remember { mutableStateOf(!JetzyPrefs.onboardingSeen) }
    if (showOnboarding) {
        OnboardingDialog(onDismiss = {
            showOnboarding = false
            JetzyPrefs.onboardingSeen = true
        })
    }

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
                        // "Connect" when nothing is staged (you'll receive).
                        Text(
                            if (hasFiles) stringResource(Res.string.send) else stringResource(Res.string.connect_to_receive),
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
                // Desktop: files/folders dropped from Finder/Explorer land straight in the tray.
                .jetzyDropTarget { dropped ->
                    viewmodel.elementsToSend.addAll(dropped)
                    viewmodel.snackyRes(Res.string.added_items, dropped.size)
                }
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

            // The one and only input: add files to share (optional — connect with nothing staged to
            // receive instead). No mode toggle, no platform guess.
            MainScreenSurface {
                Column(
                    horizontalAlignment = CenterHorizontally,
                    modifier = Modifier.fillMaxWidth().padding(6.sdp).animateContentSize()
                ) {
                    Text(
                        text = stringResource(Res.string.add_files_to_share),
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
                            if (it.isEmpty()) "Nothing added — tap Connect to receive instead"
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

            // Post-transfer access: everything Jetzy received lives in one deterministic
            // folder — jump there without redoing a transfer (the app used to offer no way
            // back to received files once the success screen was dismissed).
            TextButton(onClick = {
                if (!openReceivedLocation()) viewmodel.snackyRes(Res.string.no_received_yet)
            }) {
                Text(stringResource(Res.string.received_files), style = MaterialTheme.typography.titleSmall)
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

/** One-time first-run explainer of the non-obvious core mechanic. */
@Composable
private fun OnboardingDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.onboarding_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.sdp)) {
                OnboardingStep("1", stringResource(Res.string.onboarding_step_open))
                OnboardingStep("2", stringResource(Res.string.onboarding_step_network))
                OnboardingStep("3", stringResource(Res.string.onboarding_step_pick))
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.onboarding_got_it)) }
        },
    )
}

@Composable
private fun OnboardingStep(number: String, text: String) {
    Row(verticalAlignment = androidx.compose.ui.Alignment.Top) {
        Text(
            text = "$number.",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(end = 8.sdp),
        )
        Text(text = text, style = MaterialTheme.typography.bodyMedium)
    }
}
