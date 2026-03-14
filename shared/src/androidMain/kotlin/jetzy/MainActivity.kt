package jetzy

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import jetzy.managers.HotspotP2PM
import jetzy.managers.P2PManager
import jetzy.managers.P2PManager.Companion.platformCallback
import jetzy.managers.WiFiDirectP2PM
import jetzy.p2p.P2pPlatformCallback
import jetzy.theme.NightMode
import jetzy.ui.AdamScreen
import jetzy.utils.Platform
import jetzy.viewmodel.JetzyViewmodel
import org.koin.android.ext.android.inject

class MainActivity: ComponentActivity(), P2pPlatformCallback {

    val viewmodel: JetzyViewmodel by inject()

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        /* Tweaking some window UI elements */
        window.attributes = window.attributes.apply {
            flags = flags and WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS.inv()
        }
        window.statusBarColor = Color.Transparent.toArgb()
        window.navigationBarColor = Color.Transparent.toArgb()

        /** Telling Android that it should keep the screen on */
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        platformCallback = this

        setContent {
            AdamScreen()

            val nightMode by viewmodel.nightMode.collectAsState()
            val isSystemInDarkMode = isSystemInDarkTheme()

            LaunchedEffect(nightMode) {
                WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = when (nightMode) {
                    NightMode.LIGHT -> true
                    NightMode.DARK -> false
                    NightMode.SYSTEM -> !isSystemInDarkMode
                }
            }
        }
    }

    override fun getSuitableP2pManager(peerPlatform: Platform): P2PManager? {
        return when (peerPlatform) {
            Platform.Android -> WiFiDirectP2PM(this, viewmodel) as P2PManager
            Platform.IOS -> HotspotP2PM(this, viewmodel) as P2PManager
            else -> null
        }
    }
}