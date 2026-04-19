package jetzy

import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import jetzy.managers.HotspotP2PM
import jetzy.managers.P2PManager
import jetzy.managers.WiFiDirectP2PM
import jetzy.p2p.P2pPlatformCallback
import jetzy.theme.NightMode
import jetzy.ui.AdamScreen
import jetzy.utils.Platform
import jetzy.utils.toasty
import jetzy.viewmodel.JetzyViewmodel

class MainActivity: ComponentActivity(), P2pPlatformCallback {

    lateinit var viewmodel: JetzyViewmodel

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
            var trackDayNight by remember { mutableStateOf(false) }

            AdamScreen(
                onViewmodel = {
                    viewmodel = it
                    viewmodel.platformCallback = this
                    trackDayNight = true
                }
            )

            if (trackDayNight) {
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

    override fun getSuitableP2pManager(peerPlatform: Platform): P2PManager? {
        return when (peerPlatform) {
            Platform.Android -> WiFiDirectP2PM(this)
            Platform.IOS -> HotspotP2PM(this)
            else -> null
        }
    }

    private val p2pPermissioner = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        if (!allGranted) toasty("Some P2P permissions were denied")
    }

    override fun ensurePermissions(perms: List<String>) {
        val notYetGranted = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (notYetGranted.isNotEmpty()) p2pPermissioner.launch(notYetGranted.toTypedArray())
    }

    companion object {
        lateinit var contextGetter: () -> Context
    }

    init {
        contextGetter = { this }
    }
}