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
import jetzy.ui.adam.AdamScreen
import jetzy.theme.NightMode
import jetzy.viewmodel.JetzyViewmodel
import org.koin.android.ext.android.inject

class MainActivity: ComponentActivity() {

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

        setContent {
            //CompositionLocalProvider(LocalP2pHandler provides p2pHandler) {
                AdamScreen()
            //}

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
}