package jetzy.ui

import Jetzy.shared.BuildConfig
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType.Companion.Reject
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import jetzy.shared.generated.resources.app_name
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import jetzy.commonModule
import jetzy.p2p.P2pOperation
import jetzy.platformModule
import jetzy.shared.generated.resources.Res
import jetzy.shared.generated.resources.jetzy_vector
import jetzy.theme.JetzyTheme
import jetzy.theme.NightMode
import jetzy.theme.sdp
import jetzy.theme.ssp
import jetzy.ui.Screen.Companion.nav3Entry
import jetzy.ui.main.PermissionGateDialog
import jetzy.utils.InitializeCoilSupportForFileKit
import jetzy.shared.generated.resources.continue_label
import jetzy.shared.generated.resources.developed_by
import jetzy.shared.generated.resources.receiving
import jetzy.shared.generated.resources.sending
import jetzy.shared.generated.resources.version_label
import jetzy.shared.generated.resources.sending_error_nothing
import jetzy.viewmodel.JetzyViewmodel
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.vectorResource
import org.koin.compose.KoinApplication
import org.koin.compose.viewmodel.koinViewModel
import org.koin.dsl.koinConfiguration

val LocalViewmodel = compositionLocalOf<JetzyViewmodel> { error("No Viewmodel provided") }

@Composable
fun AdamScreen(onViewmodel: (JetzyViewmodel) -> Unit) {
    KoinApplication(
        configuration = koinConfiguration(
            declaration = { modules(commonModule, platformModule) }
        ),
        content = {
            InitializeCoilSupportForFileKit() //Allows us to display composable images from FileKit's PlatformFile

            val viewmodel = koinViewModel<JetzyViewmodel>()
            val haptic = LocalHapticFeedback.current

            LaunchedEffect(viewmodel) {
                onViewmodel(viewmodel)
            }

            val currentScreen by viewmodel.currentScreen.collectAsState()
            val op by viewmodel.currentOperation.collectAsState()

            CompositionLocalProvider(
                LocalViewmodel provides viewmodel,
            ) {
                val nightMode by viewmodel.nightMode.collectAsState()
                val isSystemInDarkMode = isSystemInDarkTheme()

                JetzyTheme {
                    var showAbout by remember { mutableStateOf(false) }

                    Scaffold(
                        snackbarHost = {
                            SnackbarHost(viewmodel.snack)
                        },
                        topBar = {
                            Column {
                                //CenterAligned
                                CenterAlignedTopAppBar(
                                    navigationIcon = {
                                        if (currentScreen !is Screen.MainScreen) {
                                            // Explicit back affordance on every leaf screen. Desktop
                                            // has no hardware back, and screens owning a live
                                            // P2PManager must tear it down (onSystemBack closes
                                            // sockets / stops discovery / purges temp), not strand.
                                            IconButton(onClick = { viewmodel.onSystemBack() }) {
                                                Icon(
                                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                                    contentDescription = "Back",
                                                )
                                            }
                                        } else {
                                            Image(
                                                imageVector = vectorResource(Res.drawable.jetzy_vector),
                                                contentDescription = "About Jetzy",
                                                modifier = Modifier
                                                    .height(42.sdp)
                                                    .clip(RoundedCornerShape(8.sdp))
                                                    .clickable { showAbout = true }
                                            )
                                        }
                                    },
                                    title = {
                                        // Direction is derived in-band now (no peer-platform pick),
                                        // so the bar shows the resolved operation, not a guessed
                                        // peer icon. `op` is set on proceed() and re-derived from
                                        // the wire by DirectionResolver.
                                        if (currentScreen !is Screen.MainScreen && op != null) {
                                            Text(
                                                text = if (op == P2pOperation.SEND) stringResource(Res.string.sending) else stringResource(Res.string.receiving),
                                                modifier = Modifier.padding(horizontal = 4.sdp),
                                                fontSize = 10.ssp,
                                            )
                                        }
                                    },
                                    actions = {
                                        if (currentScreen != Screen.FilePickingScreen) {
                                            IconButton(
                                                onClick = {
                                                    viewmodel.setNightMode(
                                                        when (nightMode) {
                                                            NightMode.SYSTEM -> if (isSystemInDarkMode) NightMode.LIGHT else NightMode.DARK
                                                            NightMode.DARK -> NightMode.LIGHT
                                                            NightMode.LIGHT -> NightMode.DARK
                                                        }
                                                    )
                                                }
                                            ) {
                                                Icon(
                                                    if (nightMode.isDark()) Icons.Filled.LightMode else Icons.Filled.DarkMode,
                                                    null
                                                )
                                            }
                                        }

                                        if (currentScreen == Screen.FilePickingScreen) {
                                            val sendingErrorStr = stringResource(Res.string.sending_error_nothing)
                                            TextButton(
                                                onClick = c@{
                                                    //Continue sending
                                                    if (viewmodel.elementsToSend.isEmpty()) {
                                                        viewmodel.snacky(sendingErrorStr)
                                                        haptic.performHapticFeedback(Reject)
                                                        return@c
                                                    }

                                                    // Pop back to a clean Main stack (don't push a
                                                    // duplicate MainScreen on top of FilePicking).
                                                    viewmodel.navigateTo(Screen.MainScreen, noWayToReturn = true)
                                                }
                                            ) {
                                                Text(stringResource(Res.string.continue_label))
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
                            //JetzyBackground()

                            NavDisplay(
                                modifier = Modifier.padding(top = pv.calculateTopPadding()),
                                backStack = viewmodel.backstack,
                                // Route system back through the VM so leaf screens tear down their
                                // live P2PManager instead of leaking sockets/discovery/temp files.
                                onBack = { viewmodel.onSystemBack() },
                                entryDecorators = listOf(
                                    rememberSaveableStateHolderNavEntryDecorator(),
                                    rememberViewModelStoreNavEntryDecorator()
                                ),
                                entryProvider = entryProvider {
                                    nav3Entry<Screen.MainScreen>()
                                    nav3Entry<Screen.FilePickingScreen>()
                                    nav3Entry<Screen.PeerDiscoveryScreen>()
                                    nav3Entry<Screen.QRDiscoveryScreen>()
                                    nav3Entry<Screen.TransferScreen>()
                                    // (The four picker subscreens render through
                                    // ElementPickingScreen's pager, never the backstack —
                                    // registering them here was dead weight.)
                                }
                            )
                        }
                    )

                    if (showAbout) {
                        AboutDialog(onDismiss = { showAbout = false })
                    }

                    val pending by viewmodel.pendingProceed.collectAsState()
                    pending?.let { p ->
                        PermissionGateDialog(
                            requirements = p.manager.permissionRequirements,
                            onConfirm = { viewmodel.confirmPendingProceed() },
                            onDismiss = { viewmodel.cancelPendingProceed() },
                        )
                    }
                }
            }
        })
}

@Composable
fun AboutDialog(onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = true)
    ) {
        Surface(
            shape = RoundedCornerShape(16.sdp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            shadowElevation = 12.dp
        ) {
            Column(
                modifier = Modifier.padding(20.sdp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.sdp)
            ) {
                Image(
                    imageVector = vectorResource(Res.drawable.jetzy_vector),
                    contentDescription = null,
                    modifier = Modifier.height(56.sdp)
                )
                Text(
                    text = stringResource(Res.string.app_name),
                    fontSize = 18.ssp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = stringResource(Res.string.version_label, BuildConfig.APP_VERSION),
                    fontSize = 11.ssp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.sdp))
                Text(
                    text = stringResource(Res.string.developed_by),
                    fontSize = 10.ssp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "yuroyami",
                    fontSize = 13.ssp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
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
