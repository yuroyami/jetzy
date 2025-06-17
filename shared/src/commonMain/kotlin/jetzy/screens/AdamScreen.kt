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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType.Companion.Reject
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import jetzy.p2p.P2pHandler
import jetzy.screens.Screen.Companion.matches
import jetzy.screens.Screen.Companion.navigateTo
import jetzy.shared.generated.resources.Res
import jetzy.shared.generated.resources.jetzy_vector
import jetzy.theme.JetzyTheme
import jetzy.theme.NightMode
import jetzy.theme.sdp
import jetzy.theme.ssp
import jetzy.utils.InitializeCoilSupportForFileKit
import jetzy.viewmodel.JetzyViewmodel
import jetzy.viewmodel.jetzyModule
import org.jetbrains.compose.resources.vectorResource
import org.koin.compose.KoinApplication
import org.koin.compose.viewmodel.koinViewModel

val LocalViewmodel = compositionLocalOf<JetzyViewmodel> { error("No Viewmodel provided") }
val LocalNavigator = compositionLocalOf<NavController> { error("No Navigator provided yet") }
val LocalP2pHandler = compositionLocalOf<P2pHandler> { error("No P2p Handler provided yet") }
val topLevelScreens = listOf(Screen.MainScreen, Screen.SendScreen, Screen.InitiateSendingScreen)

@Composable
fun AdamScreen() {
    KoinApplication(
        application = {
            modules(jetzyModule)
        }
    ) {
        InitializeCoilSupportForFileKit() //Allows us to display composable images from FileKit's PlatformFile

        val viewmodel = koinViewModel<JetzyViewmodel>()
        val haptic = LocalHapticFeedback.current
        val navigator = rememberNavController()
        val navEntry by navigator.currentBackStackEntryAsState()

        CompositionLocalProvider(
            LocalViewmodel provides viewmodel,
            LocalNavigator provides navigator
        ) {
            val nightMode by viewmodel.nightMode.collectAsState()
            val isSystemInDarkMode = isSystemInDarkTheme()

            JetzyTheme {
                LaunchedEffect(null) {
                    viewmodel.nav = navigator
                }
                Scaffold(
                    snackbarHost = {
                        SnackbarHost(viewmodel.snack)
                    },
                    topBar = {
                        Column {
                            //CenterAligned
                            CenterAlignedTopAppBar(
                                navigationIcon = {
                                    if (!navEntry.matches(Screen.MainScreen)) {
                                        IconButton(
                                            onClick = {
                                                viewmodel.clearOperation()
                                                navigator.navigateTo(Screen.MainScreen, noReturn = true)
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
                                    if (!navEntry.matches(Screen.MainScreen)) {
                                        val op by viewmodel.currentOperation.collectAsState()
                                        val prp by viewmodel.currentPeerPlatform.collectAsState()
                                        if (op != null && prp != null) {
                                            val s1 = when (op) {
                                                Operation.SEND -> "Sending to"
                                                Operation.RECEIVE -> "Receiving from"
                                                null -> ""
                                            }

                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text("$s1 ", modifier = Modifier.padding(horizontal = 4.sdp), fontSize = 10.ssp)

                                                Icon(imageVector = prp!!.icon, tint = prp!!.brandColor, contentDescription = null, modifier = Modifier.size(16.sdp))
                                            }
                                        }

                                    }
                                },
                                actions = {
                                    if (navEntry.matches(Screen.MainScreen)) {
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

                                    if (navEntry.matches(Screen.SendScreen)) {
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

                                                navigator.navigateTo(Screen.InitiateSendingScreen)
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
                        NavHost(
                            modifier = Modifier.padding(top = pv.calculateTopPadding()),
                            navController = navigator,
                            startDestination = Screen.MainScreen.label
                        ) {
                            topLevelScreens.forEach {
                                addScreen(it)
                            }
                        }
                    }
                )
            }
        }
    }
}

fun NavGraphBuilder.addScreen(screen: Screen) {
    with(screen) {
        composable(label) {
            //JetzyBackground()
            UI()
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
