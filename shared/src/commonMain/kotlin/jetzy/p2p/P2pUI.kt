package jetzy.p2p

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Webhook
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jetzy.p2p.ComposeUtils.AppPopup
import jetzy.ui.LocalViewmodel
import jetzy.ui.p2pCallback
import jetzy.ui.p2pHandler
import jetzy.utils.Platform
import jetzy.utils.loggy
import jetzy.utils.platform

object P2pUI {

    @Composable
    fun P2pInitialPopup(visibilityState: MutableState<Boolean>) {
        val viewmodel = LocalViewmodel.current

        AppPopup(
            dialogOpen = visibilityState.value,
            widthPercent = 0.95f,
            heightPercent = 0.5f,
            dismissable = true,
            onDismiss = {
                visibilityState.value = false
            }
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceEvenly
            ) {

                Text(
                    text = "Gmix P2P playlist transfer",
                    color = ComposeUtils.scheme.primary,
                    textAlign = TextAlign.Center,
                    fontSize = 16.sp
                )

                Spacer(Modifier.height(10.dp))

                Text(
                    text = "The peer user has:",
                    color = ComposeUtils.scheme.primary,
                    textAlign = TextAlign.Center,
                    fontSize = 14.sp
                )

                Row(
                    modifier = Modifier.fillMaxSize(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    Surface(
                        onClick = {
                            visibilityState.value = false

                            viewmodel.p2pPeers.clear()
                            when (platform) {
                                Platform.Android -> p2pCallback.p2pStartNativePlatform()
                                Platform.IOS -> p2pCallback.p2pStartCrossPlatform()
                                Platform.Web -> TODO()
                            }
                        },
                        modifier = Modifier.weight(1f).padding(12.dp),
                        border = BorderStroke(2.dp, ComposeUtils.scheme.primary),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column {
//                            Image(
//                                painter = painterResource(Res.drawable.p2p_android), "",
//                                modifier = Modifier.fillMaxWidth().padding(6.dp).aspectRatio(1f),
//                            )

                            Text(
                                text = "Android",
                                modifier = Modifier.fillMaxWidth(),
                                color = Color(156, 185, 63, 255),
                                textAlign = TextAlign.Center,
                                fontSize = 14.sp
                            )
                        }
                    }

                    Surface(
                        onClick = {
                            visibilityState.value = false

                            viewmodel.p2pPeers.clear()
                            when (platform) {
                                Platform.IOS -> p2pCallback.p2pStartNativePlatform()
                                Platform.Android -> p2pCallback.p2pStartCrossPlatform()
                                Platform.Web -> TODO()
                            }

                        },
                        modifier = Modifier.weight(1f).padding(12.dp),
                        border = BorderStroke(2.dp, ComposeUtils.scheme.primary),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column {
//                            Image(
//                                painter = painterResource(Res.drawable.p2p_apple), "",
//                                modifier = Modifier.fillMaxWidth().padding(6.dp).aspectRatio(1f),
//                            )

                            Text(
                                text = "iOS",
                                modifier = Modifier.fillMaxWidth(),
                                color = Color(152, 152, 152, 255),
                                textAlign = TextAlign.Center,
                                fontSize = 14.sp
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun P2pQR(visibilityState: MutableState<Boolean>) {
        AppPopup(
            dialogOpen = visibilityState.value,
            widthPercent = 0.9f,
            heightPercent = 0.7f,
            dismissable = true,
            onDismiss = {
                visibilityState.value = false
            }
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceEvenly
            ) {

                Text(
                    text = "Gmix P2P playlist transfer\n- Cross Platform mode - ",
                    color = ComposeUtils.scheme.primary,
                    textAlign = TextAlign.Center,
                    fontSize = 15.sp
                )

                Spacer(Modifier.height(10.dp))

                Text(
                    text = "To transfer playlists between Android & iOS, both peers need to join the same network (WiFi or Personal Hotspot)",
                    color = ComposeUtils.scheme.primary,
                    textAlign = TextAlign.Center,
                    fontSize = 10.sp
                )

                Spacer(Modifier)

                P2pQRcontent(modifier = Modifier.fillMaxSize())
            }
        }
    }

    @Composable
    fun P2pChoosePeerPopup(visibilityState: MutableState<Boolean>) {
        val viewmodel = LocalViewmodel.current

        AppPopup(
            dialogOpen = visibilityState.value,
            widthPercent = 0.9f,
            heightPercent = 0.8f,
            dismissable = true,
            onDismiss = {
                visibilityState.value = false
            }
        ) {

            val peers = remember { viewmodel.p2pPeers }

            Column(
                modifier = Modifier.fillMaxSize().padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceEvenly
            ) {
                Text(
                    text = "Gmix P2P playlist transfer",
                    color = ComposeUtils.scheme.primary,
                    textAlign = TextAlign.Center,
                    fontSize = 14.sp
                )

                Spacer(Modifier.height(10.dp))

                val text = when (platform) {
                    Platform.Android -> {
                        "Gmix requires WiFi, GPS (Location) and Bluetooth to all be enabled in order to transfer files." +
                                " Please select your playlist-provider Android peer after granting the necessary permissions."
                    }
                    Platform.IOS -> "Please select your iOS (iPhone/iPad) peer."
                    Platform.Web -> TODO()
                }
                Text(
                    text = text,
                    color = ComposeUtils.scheme.primary,
                    textAlign = TextAlign.Center,
                    fontSize = 9.sp,
                    lineHeight = 11.sp
                )

                Spacer(Modifier.height(10.dp))

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    items(peers) {peer ->
                        ListItem(
                            leadingContent = {
//                                Image(
//                                    painter = painterResource(Res.drawable.p2p_android), "",
//                                    modifier = Modifier.size(36.dp)
//                                )
                            },
                            headlineContent = {
                                Text(remember { peer.peerName() })
                            },
                            modifier = Modifier.clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = ripple(),
                                onClick = {
                                    visibilityState.value = false

                                    p2pHandler?.connectNativePeer(peer)
                                }
                            )
                        )
                    }
                }
            }
        }
    }

    @Composable
    fun P2pTransfer(visibilityState: MutableState<Boolean>) {
        val viewmodel = LocalViewmodel.current

        AppPopup(
            dialogOpen = visibilityState.value,
            widthPercent = 0.8f,
            heightPercent = 0.6f,
            dismissable = false,
            onDismiss = {
                p2pHandler?.stopP2pOperations()
            }
        ) {

            /* UI variables */
            val textPeer1 by remember { viewmodel.textPeer1 }
            val textPeer2 by remember { viewmodel.textPeer2 }
            val transferProgressPrimary by remember { viewmodel.transferProgressPrimary }
            val transferProgressSecondary by remember { viewmodel.transferProgressSecondary }
            val transferStatusText by remember { viewmodel.transferStatusText }

            var transferButtonShouldSave by remember { mutableStateOf(false) }
            val transferButtonText by remember { mutableStateOf("OK") }
            val transferButtonIcon by remember { mutableStateOf(Icons.Filled.Webhook) }

            LaunchedEffect(transferStatusText) {
                loggy("UI $transferStatusText")
            }

            Column(
                modifier = Modifier.fillMaxSize().padding(12.dp).align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceAround
            ) {

                Text(
                    text = "Gmix P2P playlist transfer",
                    color = ComposeUtils.scheme.primary,
                    textAlign = TextAlign.Center,
                    fontSize = 14.sp
                )


                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.SpaceEvenly
                    ) {
                        Surface(
                            modifier = Modifier.size(64.dp).aspectRatio(1f),
                            color = ComposeUtils.scheme.background,
                            shape = CircleShape,
                            border = BorderStroke(2.dp, color = MaterialTheme.colorScheme.surfaceTint)
                        ) {
//                            Image(
//                                painter = painterResource(Res.drawable.logo_day), "",
//                                modifier = Modifier
//                                    .size(48.dp)
//                                    .aspectRatio(1f)
//                                    .padding(18.dp)
//                            )
                        }

                        Text(
                            text = textPeer1 ?: "Unknown",
                            color = ComposeUtils.scheme.surfaceTint,
                            textAlign = TextAlign.Center,
                            fontSize = 9.sp,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    Box(
                        modifier = Modifier.aspectRatio(1f).padding(4.dp).weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            progress = { transferProgressPrimary },
                            modifier = Modifier.fillMaxSize().aspectRatio(1f).padding(4.dp),
                        )

                        CircularProgressIndicator(
                            progress = { transferProgressSecondary },
                            modifier = Modifier.fillMaxSize(0.75f).aspectRatio(1f).padding(4.dp)
                        )
                    }

                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.SpaceEvenly
                    ) {
                        Surface(
                            modifier = Modifier.size(64.dp).aspectRatio(1f),
                            color = MaterialTheme.colorScheme.background,
                            shape = CircleShape,
                            border = BorderStroke(2.dp, color = MaterialTheme.colorScheme.surfaceTint)
                        ) {
//                            Image(
//                                painter = painterResource(Res.drawable.logo_day), "",
//                                modifier = Modifier
//                                    .size(48.dp)
//                                    .aspectRatio(1f)
//                                    .padding(18.dp)
//                            )
                        }

                        Text(
                            text = textPeer2 ?: "Unknown",
                            color = ComposeUtils.scheme.surfaceTint,
                            textAlign = TextAlign.Center,
                            fontSize = 9.sp,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                Text(
                    text = transferStatusText,
                    color = ComposeUtils.scheme.primary,
                    textAlign = TextAlign.Center,
                    fontSize = 10.sp
                )

                Button(
                    onClick = {
                        visibilityState.value = false


                        if (transferButtonShouldSave && viewmodel.userMode.value == true) {
                            p2pHandler?.promptSavePlaylist()
                        } else {
                            p2pHandler?.stopP2pOperations()
                        }

                        transferButtonShouldSave = false
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (!transferButtonShouldSave) ComposeUtils.scheme.primary else ComposeUtils.scheme.secondary
                    )
                ) {
                    Icon(imageVector = transferButtonIcon, "", tint = Color.White)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = transferButtonText, textAlign = TextAlign.Center, fontSize = 13.sp, color = Color.White)
                }
            }
        }
    }
}