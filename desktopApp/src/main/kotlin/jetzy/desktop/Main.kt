package jetzy.desktop

import androidx.compose.runtime.SideEffect
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
        SideEffect { window.minimumSize = Dimension(380, 600) }
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
    // mDNS is the platform-agnostic bootstrap — zero-config discovery of any Jetzy peer on the
    // shared Wi-Fi/Ethernet, matching what the Android NsdManager and iOS Bonjour sides advertise.
    // (Desktop Wi-Fi Direct can form a group but has no implemented data path yet, so it stays out
    // of the ladder until that lands.)
    override fun getDefaultP2pManager(): P2PManager? = LanMdnsP2PM()

    /**
     * Per-host fallback ladder (peer-platform-agnostic). Best→worst: same-LAN mDNS, then the
     * explicit-IP/QR LAN path (receiver hosts the TCP server, sender dials/pastes — derived from
     * whether we staged files), then Bluetooth SPP (Linux-only for now; no-ops elsewhere).
     */
    override fun getDefaultFallbackManagers(): List<() -> P2PManager?> = listOf(
        { LanMdnsP2PM() },
        // No files staged ⇒ we're the receiver ⇒ host the server; otherwise dial/paste the QR.
        { if (vm.elementsToSend.isEmpty()) LanHostP2PM() else LanP2PM() },
        { BluetoothSppP2PM() },  // Linux-only for now; gracefully no-ops on other OSes
    )

    override fun getManagerForTechnology(technology: jetzy.p2p.P2pTechnology, role: jetzy.p2p.Role): P2PManager? =
        when (technology) {
            jetzy.p2p.P2pTechnology.LocalNetworkMdns -> LanMdnsP2PM()
            // Explicit-IP LAN: HOST binds the server, CLIENT dials/pastes the QR.
            jetzy.p2p.P2pTechnology.LocalNetwork -> if (role == jetzy.p2p.Role.HOST) LanHostP2PM() else LanP2PM()
            jetzy.p2p.P2pTechnology.HotspotLAN -> LanP2PM() // a PC can only join an AP, never host one
            jetzy.p2p.P2pTechnology.BluetoothSpp -> BluetoothSppP2PM()
            // WiFiDirect is deliberately omitted — no working desktop data path yet.
            else -> null
        }
}
