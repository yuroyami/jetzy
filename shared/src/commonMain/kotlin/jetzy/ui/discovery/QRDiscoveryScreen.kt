package jetzy.ui.discovery

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import jetzy.managers.P2PManager
import jetzy.ui.LocalViewmodel

private val OVERLAY_COLOR = Color.Black.copy(alpha = 0.8f)

@Composable
fun QRDiscoveryScreenUI() {
    val viewmodel = LocalViewmodel.current
    val manager = viewmodel.p2pManager ?: return

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        P2pQrContent(
            modifier = Modifier.fillMaxSize(),
            manager = manager
        )
    }

    val isHandshaking by manager.isHandshaking.collectAsState()

    if (isHandshaking) {
        Box(
            modifier = Modifier.fillMaxSize().background(OVERLAY_COLOR).clickable(onClick={}),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(
                color = contentColorFor(OVERLAY_COLOR)
            )
        }
    }
}

@Composable
expect fun P2pQrContent(modifier: Modifier = Modifier, manager: P2PManager)
