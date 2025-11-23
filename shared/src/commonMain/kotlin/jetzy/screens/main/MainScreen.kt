package jetzy.screens.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Arrangement.Absolute.Center
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Downloading
import androidx.compose.material.icons.filled.PresentToAll
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedToggleButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Alignment.Companion.CenterHorizontally
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import jetzy.p2p.ComposeUtils.JetzyText
import jetzy.p2p.ComposeUtils.scheme
import jetzy.screens.Screen
import jetzy.screens.adam.LocalViewmodel
import jetzy.shared.generated.resources.Res
import jetzy.shared.generated.resources.genos
import jetzy.theme.sdp
import jetzy.theme.ssp
import jetzy.utils.Platform
import org.jetbrains.compose.resources.Font

@Composable
fun MainScreenUI() {
    val haptic = LocalHapticFeedback.current
    val viewmodel = LocalViewmodel.current

    Scaffold(
        bottomBar = {
            Column {
                HorizontalDivider()
                BottomAppBar {
                    FilledTonalButton(
                        modifier = Modifier.fillMaxWidth().height(58.sdp).padding(4.dp),
                        onClick = c@ {
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
                                        //Operation.SEND -> Screen.SendScreen
                                        Operation.SEND -> Screen.InitiateSendingScreen
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
        Column(
            modifier = Modifier.fillMaxSize().padding(pv),
            horizontalAlignment = CenterHorizontally,
            verticalArrangement = Arrangement.SpaceEvenly
        ) {
            Column(
                horizontalAlignment = CenterHorizontally,
                modifier = Modifier.fillMaxWidth().padding(8.dp)
            ) {
                JetzyText(
                    text = "Welcome to Jetzy",
                    size = 18.ssp,
                    strokeThickness = 8f,
                )

                Text(
                    text = "Quickly send & receive files across different platforms",
                        style = MaterialTheme.typography.bodyMediumEmphasized,
                )
            }

            Spacer(Modifier.height(8.sdp))

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
                        text = "Select your Jetzy operation",
                        modifier = Modifier.fillMaxWidth().padding(8.sdp),
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                        color = lerp(scheme.onSurface, scheme.outlineVariant, 0.65f)
                    )

                    Row(modifier = Modifier.fillMaxWidth()){
                        OperationButton(operation = Operation.SEND, modifier = Modifier.weight(1f).padding(horizontal = 4.dp))
                        OperationButton(operation = Operation.RECEIVE, modifier = Modifier.weight(1f).padding(horizontal = 4.dp))
                    }
                }
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
                    Text(
                        text = "Your friend's Jetzy is running on",
                        modifier = Modifier.fillMaxWidth().padding(8.sdp),
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                        color = lerp(scheme.onSurface, scheme.outlineVariant, 0.65f)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth().padding(4.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        PeerPlatformButton(peerPlatform = Platform.Android, modifier = Modifier.weight(1f))
                        PeerPlatformButton(peerPlatform = Platform.IOS, modifier = Modifier.weight(1f))
                        PeerPlatformButton(peerPlatform = Platform.PC, modifier = Modifier.weight(1f))
                        PeerPlatformButton(peerPlatform = Platform.Web, modifier = Modifier.weight(1f))

                    }
                }
            }

            Spacer(Modifier.height(70.sdp))
        }
    }
}

enum class Operation { SEND, RECEIVE }

@Composable
fun OperationButton(
    modifier: Modifier = Modifier,
    operation: Operation
) {
    val haptic = LocalHapticFeedback.current
    val viewmodel = LocalViewmodel.current
    val currentOperation by viewmodel.currentOperation.collectAsState()
    val isSelected by derivedStateOf { currentOperation == operation }

    OutlinedToggleButton(
        modifier = modifier.height(96.dp).padding(vertical = 12.dp, horizontal = 4.dp),
        checked = isSelected,
        contentPadding = PaddingValues.Zero,
        onCheckedChange = {
            viewmodel.currentOperation.value = operation
            haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
        }
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Center, verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = when (operation) {
                    Operation.SEND -> Icons.Filled.PresentToAll
                    Operation.RECEIVE -> Icons.Filled.Downloading
                },
                contentDescription = null,
                modifier = Modifier.padding(horizontal = 4.dp),
                //tint = if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant
            )


            Text(
                text = when (operation) {
                    Operation.SEND -> "Send"
                    Operation.RECEIVE -> "Receive"
                },
                fontSize = 13.ssp,
                fontFamily = FontFamily(Font(Res.font.genos)),
                //color = if (isSelected) Color.White else MaterialTheme.colorScheme.outlineVariant,
                fontWeight = FontWeight.W800,
                maxLines = 1
            )
        }
    }
}

@Composable
fun PeerPlatformButton(
    modifier: Modifier = Modifier,
    peerPlatform: Platform
) {
    val haptic = LocalHapticFeedback.current
    val viewmodel = LocalViewmodel.current
    val currentPeerPlatform by viewmodel.currentPeerPlatform.collectAsState()
    val isSelected by derivedStateOf { currentPeerPlatform == peerPlatform }

    OutlinedCard(
        modifier = modifier.height(132.dp).padding(vertical = 4.dp, horizontal = 2.dp),
        border = CardDefaults.outlinedCardBorder(enabled = isSelected),
        shape = RoundedCornerShape(if (isSelected) 16.dp else 8.dp),
        onClick = {
            viewmodel.currentPeerPlatform.value = peerPlatform
            haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
        }
    ) {
        BadgedBox(
            modifier = modifier.height(132.sdp),
            badge = {
                if (isSelected) {
                    Badge {}
                }
            }
        ) {
            Column (
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center, horizontalAlignment = CenterHorizontally
            ) {
                Icon(
                    imageVector = peerPlatform.icon,
                    contentDescription = null,
                    modifier = Modifier.fillMaxWidth(0.9f).aspectRatio(1f).padding(4.sdp),
                    tint = if (isSelected) peerPlatform.brandColor else scheme.outlineVariant
                )

                Text(
                    text = peerPlatform.label,
                    fontSize = 10.ssp,
                    fontFamily = FontFamily(Font(Res.font.genos)),
                    color = if (isSelected) scheme.onSurface else scheme.outlineVariant,
                    fontWeight = FontWeight.W800,
                    maxLines = 1
                )
            }
        }
    }
}