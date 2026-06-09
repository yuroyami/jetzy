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

        // Capture the Application context (a process-lifetime singleton) for the static getter
        // instead of leaking this Activity for the life of the process.
        val appCtx = applicationContext
        contextGetter = { appCtx }

        /* Tweaking some window UI elements */
        window.attributes = window.attributes.apply {
            flags = flags and WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS.inv()
        }
        window.statusBarColor = Color.Transparent.toArgb()
        window.navigationBarColor = Color.Transparent.toArgb()

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

    // mDNS is the platform-agnostic bootstrap: it auto-discovers any Jetzy peer on the shared LAN
    // regardless of the peer's OS, so the app no longer asks the user to guess it.
    override fun getDefaultP2pManager(): P2PManager? = LanMdnsP2PM(this)

    /**
     * Per-host fallback ladder, peer-platform-agnostic (the platform picker is gone). Drives the
     * "Try a different transport" affordance. Ordered best→worst: same-LAN mDNS first, then the
     * no-infrastructure radios this Android can stand up (Wi-Fi Aware / Direct), then the hotspot
     * it can host for a cross-platform peer to join via QR, then Bluetooth SPP as the last resort.
     * Collectively this covers every path the old per-peer-platform ladders did.
     */
    override fun getDefaultFallbackManagers(): List<() -> P2PManager?> = listOf(
        { LanMdnsP2PM(this) },                                                        // same Wi-Fi, any OS
        { if (isWifiAwareSupported()) WifiAwareP2PM(this) else WiFiDirectP2PM(this) }, // Android↔Android, no AP
        { WiFiDirectP2PM(this) },
        { HotspotP2PM(this) },                                                        // Android hosts AP; peer joins via QR
        { BluetoothSppP2PM(this) },                                                   // last resort
    )

    override fun getManagerForTechnology(technology: jetzy.p2p.P2pTechnology, role: jetzy.p2p.Role): P2PManager? =
        when (technology) {
            jetzy.p2p.P2pTechnology.WiFiAware -> if (isWifiAwareSupported()) WifiAwareP2PM(this) else null
            jetzy.p2p.P2pTechnology.WiFiDirect -> WiFiDirectP2PM(this)
            jetzy.p2p.P2pTechnology.LocalNetworkMdns -> LanMdnsP2PM(this)
            jetzy.p2p.P2pTechnology.HotspotLAN -> HotspotP2PM(this) // Android always hosts the AP
            jetzy.p2p.P2pTechnology.BluetoothSpp -> BluetoothSppP2PM(this)
            else -> null
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
        // Keep the screen on only while a transfer is actively running.
        // The foreground service's PARTIAL_WAKE_LOCK keeps the CPU alive when backgrounded.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun stopBackgroundService() {
        JetzyForegroundService.stop(applicationContext)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    companion object {
        /** Returns the Application context. Set in [onCreate]; see note there about not leaking the Activity. */
        lateinit var contextGetter: () -> Context
    }
}