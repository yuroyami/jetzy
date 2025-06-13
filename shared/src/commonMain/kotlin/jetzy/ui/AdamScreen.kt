package jetzy.ui

import Jetzy.shared.BuildConfig
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
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
import jetzy.utils.ScreenSizeInfo
import jetzy.utils.getScreenSizeInfo
import jetzy.viewmodel.JetzyViewmodel
import org.jetbrains.compose.resources.vectorResource

lateinit var viewmodel: JetzyViewmodel

lateinit var p2pCallback: P2pCallback
var p2pHandler: P2pHandler? = null

val LocalViewmodel = compositionLocalOf<JetzyViewmodel> { error("No Viewmodel provided") }
val LocalScreenSize = compositionLocalOf<ScreenSizeInfo> { error("No Screen Size Info provided") }
val LocalNavigator = compositionLocalOf<NavController> { error("No Navigator provided yet") }

@Composable
fun AdamScreen() {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val navigator = rememberNavController()
    val navEntry by navigator.currentBackStackEntryAsState()

    JetzyTheme {
        viewmodel = viewModel<JetzyViewmodel>(key = "jetzy")

        LaunchedEffect(null) {
            viewmodel.nav = navigator
        }

        CompositionLocalProvider(
            LocalScreenSize provides getScreenSizeInfo(),
            LocalViewmodel provides viewmodel,
            LocalNavigator provides navigator
        ) {

            Scaffold(
                snackbarHost = {
                    viewmodel.snack = snackbarHostState
                    SnackbarHost(snackbarHostState)
                },
                topBar = {
                    TopAppBar(
                        title = {
                            Image(
                                imageVector = vectorResource(Res.drawable.jetzy_vector),
                                contentDescription = null,
                                modifier = Modifier.height(48.dp)
                            )
                        },
                        actions = {
                        }
                    )
                },
                content = { pv ->
                    NavHost(
                        navController = navigator,
                        startDestination = if (BuildConfig.DEBUG) Screen.MainScreen.label else Screen.MainScreen.label
                    ) {
                        addScreen(Screen.MainScreen)
                    }
                }
            )
        }
    }
}

fun NavGraphBuilder.addScreen(screen: Screen) {
    with(screen) {
        composable(label) {
            JetzyBackground()
            UI()
        }
    }
}

@Composable
fun JetzyBackground() {
    Box(
        modifier = Modifier.fillMaxSize().background(
            brush = Brush.linearGradient(
                listOf(MaterialTheme.colorScheme.primary.copy(0.25f), Color.White, MaterialTheme.colorScheme.primary.copy(0.25f))
            )
        )
    )
}
