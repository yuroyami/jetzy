package jetzy.ui.discovery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import jetzy.managers.QRDiscoveryP2PM
import jetzy.ui.LocalViewmodel

@Composable
fun QRDiscoveryScreenUI() {
    val viewmodel = LocalViewmodel.current
    val manager = viewmodel.p2pManager as? QRDiscoveryP2PM ?: return

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

}

@Composable
expect fun P2pQrContent(modifier: Modifier = Modifier, manager: QRDiscoveryP2PM)