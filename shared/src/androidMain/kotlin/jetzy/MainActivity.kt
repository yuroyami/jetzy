package jetzy

import android.content.Context
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
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import jetzy.managers.BluetoothSppP2PM
import jetzy.managers.HotspotP2PM
import jetzy.managers.LanMdnsP2PM
import jetzy.managers.P2PManager
import jetzy.managers.WiFiDirectP2PM
import jetzy.managers.WifiAwareP2PM
import jetzy.utils.isWifiAwareSupported
import jetzy.p2p.P2pPlatformCallback
import jetzy.services.JetzyForegroundService
import jetzy.theme.NightMode
import jetzy.ui.AdamScreen
import jetzy.utils.Platform
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
            Platform.Android -> {
                // Android↔Android priority chain:
                //   1. Wi-Fi Aware (Android 8+, chip-gated) — best discovery, no group dance
                //   2. Wi-Fi Direct — universal Android fallback
                //   3. mDNS (same Wi-Fi) — only reached via UI fall-through, see below
                //   4. Bluetooth SPP — last-resort, slow but reliable
                // The auto-fallback chain isn't fully wired (would require the UI to
                // surface "no peers found, try X?"); for now we pick the best supported
                // and the user can manually retry through cancelDiscovery() + reopen.
                if (isWifiAwareSupported()) WifiAwareP2PM(this) else WiFiDirectP2PM(this)
            }
            Platform.IOS -> {
                if (isWifiAwareSupported()) WifiAwareP2PM(this) else HotspotP2PM(this)
            }
            // Android↔PC priority:
            //   1. Wi-Fi Direct (Linux/Windows side speaks the standard)
            //   2. mDNS (same Wi-Fi)
            //   3. HotspotP2PM (Android hosts hotspot, PC joins manually)
            //   4. Bluetooth SPP fallback
            // We don't have a way to know the peer's OS from here — we pick the
            // Wi-Fi-cheapest option (LanMdnsP2PM if on a Wi-Fi network) and let
            // user cancel/retry if no peer shows up.
            Platform.PC -> LanMdnsP2PM(this)
            else -> null
        }
    }

    /**
     * Explicit fallback ladder for the same peer platform, in priority order.
     * Drives the "Try a different transport" affordance on the peer discovery
     * screen. The first rung is what [getSuitableP2pManager] would return; later
     * rungs are progressively less preferred but more likely to work in awkward
     * network conditions.
     */
    override fun getFallbackP2pManagers(peerPlatform: Platform): List<() -> P2PManager?> = when (peerPlatform) {
        Platform.Android -> listOf(
            { if (isWifiAwareSupported()) WifiAwareP2PM(this) else WiFiDirectP2PM(this) },
            { LanMdnsP2PM(this) },
            { WiFiDirectP2PM(this) },
            { BluetoothSppP2PM(this) },
        )
        Platform.IOS -> listOf(
            { if (isWifiAwareSupported()) WifiAwareP2PM(this) else HotspotP2PM(this) },
            { LanMdnsP2PM(this) },
            { HotspotP2PM(this) },
        )
        Platform.PC -> listOf(
            { LanMdnsP2PM(this) },
            { HotspotP2PM(this) },
            { BluetoothSppP2PM(this) },
        )
        else -> emptyList()
    }

    private val p2pPermissioner = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        // Result deliberately ignored: the permission gate dialog re-polls each
        // requirement's `isGrantedNow` while open, so denial/grant flips show up
        // in the UI without us having to thread the result back here.
    }

    /**
     * Launch the OS prompt for [perms]. Called by [PermissionRequirement.request]
     * lambdas built in [jetzy.permissions.AndroidPermissionRequirements]. The
     * result is observed via the gate dialog's polling rather than a callback.
     */
    fun requestRuntimePermissions(perms: Array<String>) {
        if (perms.isEmpty()) return
        runCatching { p2pPermissioner.launch(perms) }
    }

    override fun startBackgroundService() {
        JetzyForegroundService.start(applicationContext)
    }

    override fun stopBackgroundService() {
        JetzyForegroundService.stop(applicationContext)
    }

    companion object {
        lateinit var contextGetter: () -> Context
    }

    init {
        contextGetter = { this }
    }
}