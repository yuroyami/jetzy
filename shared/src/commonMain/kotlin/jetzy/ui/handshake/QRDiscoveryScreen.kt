package jetzy.ui.handshake

import androidx.compose.runtime.Composable
import jetzy.managers.QRDiscoveryP2PM
import jetzy.ui.LocalViewmodel

@Composable
fun QRDiscoveryScreenUI(manager: QRDiscoveryP2PM) {
    val viewmodel = LocalViewmodel.current

}