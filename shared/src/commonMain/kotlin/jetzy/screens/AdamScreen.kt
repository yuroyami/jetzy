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
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import jetzy.p2p.P2pCallback
import jetzy.p2p.P2pHandler
import jetzy.shared.generated.resources.Res
import jetzy.shared.generated.resources.jetzy_vector
import jetzy.theme.JetzyTheme
import jetzy.theme.NightMode
import jetzy.viewmodel.JetzyViewmodel
import jetzy.viewmodel.jetzyModule
import org.jetbrains.compose.resources.vectorResource
import org.koin.compose.KoinApplication
import org.koin.compose.viewmodel.koinViewModel

lateinit var p2pCallback: P2pCallback
var p2pHandler: P2pHandler? = null

val LocalViewmodel = compositionLocalOf<JetzyViewmodel> { error("No Viewmodel provided") }
val LocalNavigator = compositionLocalOf<NavController> { error("No Navigator provided yet") }

val topLevelScreens = listOf(Screen.MainScreen, Screen.SendScreen)

@Composable
fun AdamScreen() {
    KoinApplication(
        application = {
            modules(jetzyModule)
        }
    ) {
        val viewmodel = koinViewModel<JetzyViewmodel>()
        val scope = rememberCoroutineScope()
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
                                    Image(
                                        imageVector = vectorResource(Res.drawable.jetzy_vector),
                                        contentDescription = null,
                                        modifier = Modifier.height(64.dp)
                                    )
                                },
                                title = {
                                    if (navEntry?.destination?.route != Screen.MainScreen.label) {
                                        val op by viewmodel.currentOperation.collectAsState()
                                        val prp by viewmodel.currentPeerPlatform.collectAsState()
                                        if (op != null && prp != null) {
                                            val s1 = when (op) {
                                                Operation.SEND -> "Sending to"
                                                Operation.RECEIVE -> "Receive from"
                                                null -> ""
                                            }

                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text("$s1 ${prp?.label}", modifier = Modifier.padding(horizontal = 4.dp), fontSize = 18.sp)

                                                Icon(imageVector = prp!!.icon, tint = prp!!.brandColor, contentDescription = null, modifier = Modifier.size(24.dp))
                                            }
                                        }

                                    }
                                },
                                actions = {
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
