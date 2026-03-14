package jetzy.ui.discovery

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment.Companion.CenterHorizontally
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.alexzhirkevich.qrose.options.QrPixelShape
import io.github.alexzhirkevich.qrose.options.QrShapes
import io.github.alexzhirkevich.qrose.options.roundCorners
import io.github.alexzhirkevich.qrose.rememberQrCodePainter
import jetzy.managers.HotspotP2PM
import jetzy.managers.QRDiscoveryP2PM
import jetzy.ui.LocalViewmodel

data class QRData(
    val hotspotSSID: String,
    val hotspotPassword: String,
    val ipAddress: String,
    val port: Int,
    val deviceName: String
) {
    override fun toString(): String = "${hotspotSSID}:${hotspotPassword}:${ipAddress}:${port}:${deviceName}"
}

@Composable
actual fun P2pQrContent(modifier: Modifier, manager: QRDiscoveryP2PM) {
    val viewmodel = LocalViewmodel.current

    Column(
        modifier = modifier,
        horizontalAlignment = CenterHorizontally,
        verticalArrangement = Arrangement.SpaceEvenly
    ) {
        var qrData by remember { mutableStateOf("") }
        var qrRefreshor by remember { mutableIntStateOf(0) }

        LaunchedEffect(qrRefreshor) {
            val data = (manager as? HotspotP2PM)?.establishTcpServer()?.await() ?: return@LaunchedEffect //TODO Throw error

            qrData = data.toString()
        }

        val qrcodePainter = rememberQrCodePainter(
            data = qrData,
            shapes = QrShapes(darkPixel = QrPixelShape.roundCorners())
        )

        Spacer(Modifier)

        Image(
            painter = qrcodePainter, "",
            modifier = Modifier.size(164.dp)
        )

        Text("Your IP address is: " + qrData.ifBlank { "Not connected to any network" }, textAlign = TextAlign.Center)

        Row {
            TextButton(onClick = { qrRefreshor += 1 }) {
                Text("Refresh")
            }

            TextButton(onClick = { viewmodel.p2pQRpopup.value = false }) {
                Text("Cancel")
            }
        }

        Spacer(Modifier)
    }
}