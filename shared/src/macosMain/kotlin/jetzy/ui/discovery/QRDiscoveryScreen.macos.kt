package jetzy.ui.discovery

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import jetzy.managers.P2PManager

/**
 * macOS native build doesn't ship a QR-based transport: every reachable peer
 * platform is covered by peer-discovery managers (MPC, LAN mDNS). If we ever
 * fall through to a manager with QR-mode discovery, surface a clear message.
 * Practically: this composable shouldn't render in normal flow.
 */
@Composable
actual fun P2pQrContent(modifier: Modifier, manager: P2PManager) {
    Box(
        modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "QR-mode transport isn't available on the macOS build. " +
                    "Cancel and choose a peer-discovery transport.",
            color = MaterialTheme.colorScheme.error,
        )
    }
}
