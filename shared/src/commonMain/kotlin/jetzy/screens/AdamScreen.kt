package jetzy.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType.Companion.Reject
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import jetzy.commonModule
import jetzy.p2p.P2pHandler
import jetzy.platformModule
import jetzy.shared.generated.resources.Res
import jetzy.shared.generated.resources.jetzy_vector
import jetzy.theme.JetzyTheme
import jetzy.theme.NightMode
import jetzy.theme.sdp
import jetzy.theme.ssp
import jetzy.utils.InitializeCoilSupportForFileKit
import jetzy.viewmodel.JetzyViewmodel
import org.jetbrains.compose.resources.vectorResource
import org.koin.compose.KoinApplication
import org.koin.compose.viewmodel.koinViewModel

val LocalViewmodel = compositionLocalOf<JetzyViewmodel> { error("No Viewmodel provided") }
val LocalP2pHandler = compositionLocalOf<P2pHandler> { error("No P2p Handler provided yet") }

@Composable
fun AdamScreen() {
    KoinApplication(
        application = {
            modules(commonModule, platformModule)
        }
    ) {
        InitializeCoilSupportForFileKit() //Allows us to display composable images from FileKit's PlatformFile

        val viewmodel = koinViewModel<JetzyViewmodel>()
        val haptic = LocalHapticFeedback.current

        val backstack = remember { mutableStateListOf<Screen>(Screen.MainScreen) }
        val currentScreen by viewmodel.currentScreen.collectAsState()

        CompositionLocalProvider(
            LocalViewmodel provides viewmodel,
        ) {
            val nightMode by viewmodel.nightMode.collectAsState()
            val isSystemInDarkMode = isSystemInDarkTheme()

            JetzyTheme {
                Scaffold(
                    snackbarHost = {
                        SnackbarHost(viewmodel.snack)
                    },
                    topBar = {
                        Column {
                            //CenterAligned
                            CenterAlignedTopAppBar(
                                navigationIcon = {
                                    if (currentScreen is Screen.MainScreen) {
                                        IconButton(
                                            onClick = {
                                                viewmodel.clearOperation()
                                                viewmodel.navigateTo(Screen.MainScreen)
                                            }
                                        ) {
                                            Icon(
                                                imageVector = Icons.Filled.Home,
                                                contentDescription = null
                                            )
                                        }
                                    } else {
                                        Image(
                                            imageVector = vectorResource(Res.drawable.jetzy_vector),
                                            contentDescription = null,
                                            modifier = Modifier.height(64.dp)
                                        )
                                    }
                                },
                                title = {
                                    if (currentScreen !is Screen.MainScreen) {
                                        val op by viewmodel.currentOperation.collectAsState()
                                        val prp by viewmodel.currentPeerPlatform.collectAsState()
                                        if (op != null && prp != null) {
                                            val s1 = when (op) {
                                                Operation.SEND -> "Sending to"
                                                Operation.RECEIVE -> "Receiving from"
                                                else -> {}
                                            }

                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text("$s1 ", modifier = Modifier.padding(horizontal = 4.sdp), fontSize = 10.ssp)

                                                Icon(imageVector = prp!!.icon, tint = prp!!.brandColor, contentDescription = null, modifier = Modifier.size(16.sdp))
                                            }
                                        }

                                    }
                                },
                                actions = {
                                    if (currentScreen is Screen.MainScreen) {
                                        IconButton(
                                            onClick = {
                                                viewmodel.nightMode.value = when (nightMode) {
                                                    NightMode.SYSTEM -> if (isSystemInDarkMode) NightMode.LIGHT else NightMode.DARK
                                                    NightMode.DARK -> NightMode.LIGHT
                                                    NightMode.LIGHT -> NightMode.DARK
                                                }
                                            }
                                        ) {
                                            Icon(
                                                if (nightMode.isDark()) Icons.Filled.LightMode else Icons.Filled.DarkMode,
                                                null
                                            )
                                        }
                                    }

                                    if (currentScreen == Screen.SendScreen) {
                                        TextButton(
                                            onClick = c@ {
                                                //Continue sending
                                                val nothingToSend = with(viewmodel) {
                                                    files.isEmpty() && folders.isEmpty() && photos.isEmpty() && videos.isEmpty() && texts.isEmpty()
                                                }
                                                if (nothingToSend) {
                                                    viewmodel.snacky("Sending Error: Nothing to send...")
                                                    haptic.performHapticFeedback(Reject)
                                                    return@c
                                                }

                                                viewmodel.navigateTo(Screen.InitiateSendingScreen)
                                            }
                                        ) {
                                            Text("Continue")
                                            Icon(
                                                imageVector = Icons.AutoMirrored.Default.ArrowForwardIos,
                                                contentDescription = null
                                            )
                                        }
                                    }
                                }
                            )
                            HorizontalDivider()
                        }
                    },
                    content = { pv ->
                        JetzyBackground()
                        NavDisplay(
                            modifier = Modifier.padding(pv),
                            backStack = backstack,
                            entryDecorators = listOf(
                                rememberSaveableStateHolderNavEntryDecorator(),
                                rememberViewModelStoreNavEntryDecorator()
                            ),
                            entryProvider = entryProvider {
                                entry<Screen.MainScreen> { home ->
                                    home.UI()
                                }
                                entry<Screen.SendScreen> { home ->
                                    home.UI()
                                }
                                entry<Screen.InitiateSendingScreen> { home ->
                                    home.UI()
                                }
                            }
                        )
                    }
                )
            }
        }
    }
}

@Composable
fun JetzyBackground() {
    Box(
        modifier = Modifier.fillMaxSize().background(
            brush = Brush.linearGradient(
                listOf(MaterialTheme.colorScheme.primary.copy(0.1f), Color.White, MaterialTheme.colorScheme.primary.copy(0.1f))
            )
        )
    )
}
