package jetzy.ui.discovery

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import jetzy.models.QRData
import jetzy.ui.LocalViewmodel

@Composable
actual fun P2pQrContent(modifier: Modifier, manager: QRDiscoveryP2PM) {
    val viewmodel = LocalViewmodel.current

    Column(
        modifier = modifier,
        horizontalAlignment = CenterHorizontally,
        verticalArrangement = Arrangement.SpaceEvenly
    ) {
        var qrData by remember { mutableStateOf<QRData?>(null) }
        var qrRefreshor by remember { mutableIntStateOf(0) }

        LaunchedEffect(qrRefreshor) {
            qrData = (manager as? HotspotP2PM)?.establishTcpServer()?.await() //TODO Throw error

        }
        qrData?.let {
            val qrcodePainter = rememberQrCodePainter(
                data = qrData?.toString() ?: "",
                shapes = QrShapes(darkPixel = QrPixelShape.roundCorners())
            )

            Spacer(Modifier)

            Image(
                painter = qrcodePainter, "",
                modifier = Modifier.size(164.dp)
            )

            Text("Your IP address is: ${qrData?.ipAddress}:${qrData?.port}", textAlign = TextAlign.Center)

        }

        TextButton(onClick = {
            //viewmodel.p2pQRpopup.value = false
        }) {
            Text("Cancel")
        }

        Spacer(Modifier)
    }
}