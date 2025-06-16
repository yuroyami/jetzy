package jetzy.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Arrangement.Absolute.Center
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.ElevatedButton
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
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Alignment.Companion.CenterHorizontally
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import compose.icons.FontAwesomeIcons
import compose.icons.fontawesomeicons.Brands
import compose.icons.fontawesomeicons.Solid
import compose.icons.fontawesomeicons.brands.Android
import compose.icons.fontawesomeicons.brands.Apple
import compose.icons.fontawesomeicons.brands.Chrome
import compose.icons.fontawesomeicons.solid.Desktop
import io.github.vinceglb.filekit.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.core.PickerMode
import io.github.vinceglb.filekit.core.PickerType
import jetzy.p2p.ComposeUtils.JetzyText
import jetzy.p2p.ComposeUtils.scheme
import jetzy.shared.generated.resources.Res
import jetzy.shared.generated.resources.genos
import jetzy.utils.Platform
import org.jetbrains.compose.resources.Font

@Composable
fun MainScreenUI() {
    val haptic = LocalHapticFeedback.current
    val viewmodel = LocalViewmodel.current

    val filePicker = rememberFilePickerLauncher(
        type = PickerType.File(), mode = PickerMode.Multiple(),
    ) { files ->
        files?.forEach {
            viewmodel.files.add(it)
        }
    }

    Scaffold(
        bottomBar = {
            Column {
                HorizontalDivider()
                BottomAppBar {
                    ElevatedButton(
                        modifier = Modifier.fillMaxWidth().height(64.dp),
                        onClick = {
                            if (viewmodel.currentOperation.value == null) {
                                viewmodel.snacky("Select an operation!")
                                haptic.performHapticFeedback(HapticFeedbackType.Reject)
                                return@ElevatedButton
                            }
                            if (viewmodel.currentPeerPlatform.value == null) {
                                viewmodel.snacky("Select which platform your peer has!")
                                haptic.performHapticFeedback(HapticFeedbackType.Reject)
                                return@ElevatedButton
                            }

                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)

                        }
                    ) {
                        Text("Proceed", style = MaterialTheme.typography.titleLarge)
                    }
                }
                /*NavigationBar {
                    screens.forEach { screen ->
                        NavigationBarItem(
                            selected = true,
                            icon = {
                                Icon(screen.icon, null)

                            },
                            label = { Text(screen.label) },
                            onClick = {
                                navigator.navigate(screen.label)
                            }
                        )
                    }
                }*/
            }
        }
    ) { pv ->
        Column(
            modifier = Modifier.fillMaxSize().padding(pv),
            horizontalAlignment = CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            key(1) {
                JetzyText(
                    text = "Welcome to Jetzy!",
                    size = 36.sp,
                    strokeThickness = 8f,
                )

                Text(
                    text = "Send and receive across different platforms with ease.",
                        style = MaterialTheme.typography.bodyLargeEmphasized,
                )
            }

            Spacer(Modifier.height(8.dp))

            Surface(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                tonalElevation = 0.dp,
                shadowElevation = 8.dp
            ) {
                Column(
                    horizontalAlignment = CenterHorizontally,
                    modifier = Modifier.fillMaxWidth().padding(8.dp)
                ) {
                    Text(
                        text = "Select Jetzy operation",
                        modifier = Modifier.fillMaxWidth().padding(8.dp),
                        style = MaterialTheme.typography.titleLarge,
                        textAlign = TextAlign.Center,
                        color = lerp(Color.White, scheme.outlineVariant, 0.65f)
                    )

                    Row(modifier = Modifier.fillMaxWidth()){
                        OperationButton(operation = Operation.SEND, modifier = Modifier.weight(1f).padding(horizontal = 4.dp))
                        OperationButton(operation = Operation.RECEIVE, modifier = Modifier.weight(1f).padding(horizontal = 4.dp))
                    }
                }
            }

            Surface(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                tonalElevation = 0.dp,
                shadowElevation = 8.dp
            ) {
                Column(
                    horizontalAlignment = CenterHorizontally,
                    modifier = Modifier.fillMaxWidth().padding(8.dp)
                ) {
                    Text(
                        text = "Your friend's Jetzy is running on",
                        modifier = Modifier.fillMaxWidth().padding(8.dp),
                        style = MaterialTheme.typography.titleLarge,
                        textAlign = TextAlign.Center,
                        color = lerp(Color.White, scheme.outlineVariant, 0.65f)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth().padding(8.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        PeerPlatformButton(peerPlatform = Platform.Android, modifier = Modifier.weight(1f))
                        PeerPlatformButton(peerPlatform = Platform.IOS, modifier = Modifier.weight(1f))
                        PeerPlatformButton(peerPlatform = Platform.PC, modifier = Modifier.weight(1f))
                        PeerPlatformButton(peerPlatform = Platform.Web, modifier = Modifier.weight(1f))

                    }
                }
            }
        }
    }


    /*P2pInitialPopup(visibilityState = remember { viewmodel.p2pInitialPopup })
    P2pQR(visibilityState = remember { viewmodel.p2pQRpopup })
    P2pChoosePeerPopup(visibilityState = remember { viewmodel.p2pChoosePeerPopup })
    P2pTransfer(visibilityState = remember { viewmodel.p2pTransferPopup })*/
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
        modifier = modifier.height(96.dp).padding(12.dp),
        checked = isSelected,
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
                fontSize = 24.sp,
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
        modifier = modifier.height(132.dp).padding(4.dp),
        border = CardDefaults.outlinedCardBorder(enabled = isSelected),
        shape = RoundedCornerShape(if (isSelected) 16.dp else 8.dp),
        onClick = {
            viewmodel.currentPeerPlatform.value = peerPlatform
            haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
        }
    ) {
        BadgedBox(
            modifier = modifier.height(132.dp),
            badge = {
                if (isSelected) {
                    Badge {

                    }
                }
            }
        ) {
            Column (
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = when (peerPlatform) {
                        Platform.Android -> FontAwesomeIcons.Brands.Android
                        Platform.IOS -> FontAwesomeIcons.Brands.Apple
                        Platform.Web -> FontAwesomeIcons.Brands.Chrome
                        Platform.PC -> FontAwesomeIcons.Solid.Desktop
                    },
                    contentDescription = null,
                    modifier = Modifier.fillMaxWidth(0.9f).aspectRatio(1f).padding(4.dp),
                    tint = if (isSelected) peerPlatform.brandColor else MaterialTheme.colorScheme.outlineVariant
                )

                Text(
                    text = peerPlatform.label,
                    fontSize = 16.sp,
                    fontFamily = FontFamily(Font(Res.font.genos)),
                    color = if (isSelected) Color.White else MaterialTheme.colorScheme.outlineVariant,
                    fontWeight = FontWeight.W800,
                    maxLines = 1
                )
            }
        }
    }
}