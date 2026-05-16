package jetzy.desktop

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import jetzy.managers.BluetoothSppP2PM
import jetzy.managers.LanHostP2PM
import jetzy.managers.LanMdnsP2PM
import jetzy.managers.LanP2PM
import jetzy.managers.P2PManager
import jetzy.managers.WiFiDirectP2PM
import jetzy.p2p.P2pOperation
import jetzy.p2p.P2pPlatformCallback
import jetzy.ui.AdamScreen
import jetzy.utils.Platform
import jetzy.viewmodel.JetzyViewmodel
import java.awt.Dimension

private lateinit var viewmodel: JetzyViewmodel

fun main() = application {
    Window(
        title = "Jetzy",
        state = rememberWindowState(width = 480.dp, height = 760.dp),
        onCloseRequest = ::exitApplication,
    ) {
        window.minimumSize = Dimension(380, 600)
        AdamScreen(
            onViewmodel = { vm ->
                viewmodel = vm
                viewmodel.platformCallback = DesktopP2pCallback(viewmodel)
            }
        )
    }
}

/**
 * Bridges peerPlatform → manager. mDNS is the new default for every pair —
 * zero-config peer discovery on the same Wi-Fi/Ethernet matches what the
 * Android NsdManager and iOS Bonjour sides advertise.
 *
 * Explicit fallback ladders for when no peers appear (e.g. wrong network,
 * multicast blocked, etc.) are exposed via [fallbackManagersFor]; the current
 * UI doesn't yet surface "retry with different transport" but the wiring is
 * in place.
 */
private class DesktopP2pCallback(private val vm: JetzyViewmodel) : P2pPlatformCallback {
    override fun getSuitableP2pManager(peerPlatform: Platform): P2PManager? = when (peerPlatform) {
        // PC↔Android: try Wi-Fi Direct first (where supported — Linux only for now);
        // mDNS otherwise. Wi-Fi Direct currently no-ops on Win/macOS, falling silently
        // through to the next attempt the user makes.
        Platform.Android -> if (canUseWiFiDirect()) WiFiDirectP2PM() else LanMdnsP2PM()
        // PC↔iOS: mDNS is the cleanest path. The legacy LanHostP2PM with empty SSID
        // is also valid via the fallback ladder.
        Platform.IOS -> LanMdnsP2PM()
        // PC↔PC: mDNS handles both sides symmetrically. The PC↔PC QR flow
        // (LanHostP2PM/LanP2PM) remains as fallback in [fallbackManagersFor].
        Platform.PC -> LanMdnsP2PM()
        else -> null
    }

    private fun canUseWiFiDirect(): Boolean {
        val os = System.getProperty("os.name").orEmpty().lowercase()
        // Linux via wpa_supplicant, Windows via PowerShell+WinRT. macOS deferred —
        // Apple doesn't expose Wi-Fi Direct publicly.
        return "linux" in os || "nix" in os || "nux" in os || "win" in os
    }

    override fun getFallbackP2pManagers(peerPlatform: Platform): List<() -> P2PManager?> = when (peerPlatform) {
        Platform.Android -> listOf(
            { if (canUseWiFiDirect()) WiFiDirectP2PM() else LanMdnsP2PM() },
            { LanMdnsP2PM() },
            { LanP2PM() },           // legacy hotspot-join QR-paste flow
            { BluetoothSppP2PM() },  // Linux-only for now; gracefully no-ops on other OSes
        )
        Platform.IOS -> listOf(
            { LanMdnsP2PM() },
            { LanHostP2PM() },       // PC hosts same-LAN TCP server (legacy QR path)
        )
        Platform.PC -> listOf(
            { LanMdnsP2PM() },
            // Operation-aware: receiver hosts, sender dials.
            { if (vm.currentOperation.value == P2pOperation.RECEIVE) LanHostP2PM() else LanP2PM() },
        )
        else -> emptyList()
    }
}
