package jetzy.ui.discovery

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.alexzhirkevich.qrose.options.QrBrush
import io.github.alexzhirkevich.qrose.options.QrColors
import io.github.alexzhirkevich.qrose.options.QrPixelShape
import io.github.alexzhirkevich.qrose.options.QrShapes
import io.github.alexzhirkevich.qrose.options.roundCorners
import io.github.alexzhirkevich.qrose.options.solid
import io.github.alexzhirkevich.qrose.rememberQrCodePainter
import jetzy.managers.LanHostP2PM
import jetzy.managers.LanP2PM
import jetzy.managers.P2PManager
import jetzy.shared.generated.resources.Res
import jetzy.shared.generated.resources.address_label
import jetzy.shared.generated.resources.cancel
import jetzy.shared.generated.resources.client_device
import jetzy.shared.generated.resources.connect
import jetzy.shared.generated.resources.desktop_client_hint
import jetzy.shared.generated.resources.desktop_client_title
import jetzy.shared.generated.resources.desktop_host_hint
import jetzy.shared.generated.resources.desktop_host_share_code
import jetzy.shared.generated.resources.device_label
import jetzy.shared.generated.resources.host_device
import jetzy.shared.generated.resources.invalid_jetzy_qr
import jetzy.shared.generated.resources.load_qr_image
import jetzy.shared.generated.resources.paste_from_clipboard
import jetzy.shared.generated.resources.qr_code_desc
import jetzy.shared.generated.resources.qr_contents
import jetzy.shared.generated.resources.qr_image_unreadable
import jetzy.shared.generated.resources.reading_image
import jetzy.shared.generated.resources.starting_server
import jetzy.models.QRData
import jetzy.models.QRData.Companion.toQRData
import jetzy.ui.LocalViewmodel
import jetzy.utils.loggy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource

@Composable
actual fun P2pQrContent(modifier: Modifier, manager: P2PManager) {
    when (manager) {
        is LanHostP2PM -> HostQrPanel(modifier, manager)
        is LanP2PM     -> ClientQrPanel(modifier, manager)
        else           -> UnsupportedManagerPanel(modifier, manager)
    }
}

// ─── Host (PC shows QR for peer to scan) ───────────────────────────────────────

@Composable
private fun HostQrPanel(modifier: Modifier, manager: LanHostP2PM) {
    val viewmodel = LocalViewmodel.current
    val colorScheme = MaterialTheme.colorScheme

    var qrData by remember { mutableStateOf<QRData?>(null) }

    LaunchedEffect(Unit) {
        qrData = manager.establishTcpServer().await()
    }

    Box(
        modifier = modifier.fillMaxSize().background(colorScheme.surface),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 32.dp, vertical = 24.dp)
        ) {
            Text(
                text = stringResource(Res.string.host_device),
                fontSize = 11.sp,
                fontWeight = FontWeight.W500,
                color = colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(Res.string.desktop_host_share_code),
                fontSize = 20.sp,
                fontWeight = FontWeight.W500,
                color = colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(Res.string.desktop_host_hint),
                fontSize = 13.sp,
                color = colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(colorScheme.surfaceContainer)
                    .border(0.5.dp, colorScheme.outlineVariant, RoundedCornerShape(20.dp))
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                val data = qrData
                if (data != null) {
                    val qrString = remember(data) { data.toString() }
                    val address = remember(data) { "${data.ipAddress}:${data.port}" }
                    val qrPainter = rememberQrCodePainter(
                        data = qrString,
                        shapes = QrShapes(darkPixel = QrPixelShape.roundCorners()),
                        colors = QrColors(dark = QrBrush.solid(colorScheme.primary)),
                    )
                    Box(
                        modifier = Modifier
                            .size(240.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.White)
                            .padding(12.dp),
                    ) {
                        Image(painter = qrPainter, contentDescription = stringResource(Res.string.qr_code_desc))
                    }
                    QrTextRow(label = stringResource(Res.string.address_label), value = address)
                    QrTextRow(label = stringResource(Res.string.device_label), value = data.deviceName)
                } else {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.size(240.dp),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(Res.string.starting_server), fontSize = 12.sp, color = colorScheme.onSurfaceVariant)
                    }
                }
            }

            TextButton(onClick = { viewmodel.cancelDiscovery() }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(Res.string.cancel), fontSize = 13.sp, color = colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun QrTextRow(label: String, value: String) {
    val colorScheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(colorScheme.surfaceContainerHigh)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(label, fontSize = 11.sp, color = colorScheme.onSurfaceVariant)
        Text(
            value,
            fontSize = 13.sp,
            fontWeight = FontWeight.W500,
            color = colorScheme.onSurface,
            fontFamily = FontFamily.Monospace,
        )
    }
}

// ─── Client (PC scans/pastes QR from peer) ────────────────────────────────────

@Composable
private fun ClientQrPanel(modifier: Modifier, manager: LanP2PM) {
    val viewmodel = LocalViewmodel.current
    val clipboard = LocalClipboardManager.current
    val colorScheme = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()

    var pasted by remember { mutableStateOf("") }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var isScanningImage by remember { mutableStateOf(false) }
    // Resolved up front: these are set from click lambdas, which aren't composable contexts.
    val unreadableMsg = stringResource(Res.string.qr_image_unreadable)
    val invalidMsg = stringResource(Res.string.invalid_jetzy_qr)

    Box(
        modifier = modifier.fillMaxSize().background(colorScheme.surface),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 32.dp, vertical = 24.dp),
        ) {
            Text(
                stringResource(Res.string.client_device),
                fontSize = 11.sp,
                fontWeight = FontWeight.W500,
                color = colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(Res.string.desktop_client_title),
                fontSize = 20.sp,
                fontWeight = FontWeight.W500,
                color = colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Text(
                stringResource(Res.string.desktop_client_hint),
                fontSize = 13.sp,
                color = colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(colorScheme.surfaceContainer)
                    .border(0.5.dp, colorScheme.outlineVariant, RoundedCornerShape(20.dp))
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = pasted,
                    onValueChange = { pasted = it },
                    label = { Text(stringResource(Res.string.qr_contents)) },
                    placeholder = { Text("SSID:PASSWORD:IP:PORT:NAME[:SESSION]") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = false,
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    FilledTonalButton(
                        onClick = {
                            clipboard.getText()?.text?.let { pasted = it.trim() }
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(Res.string.paste_from_clipboard)) }

                    FilledTonalButton(
                        onClick = {
                            isScanningImage = true
                            scope.launch {
                                val decoded = pickAndDecodeQrImage()
                                isScanningImage = false
                                if (decoded != null) pasted = decoded
                                else statusMessage = unreadableMsg
                            }
                        },
                        enabled = !isScanningImage,
                        modifier = Modifier.weight(1f),
                    ) { Text(if (isScanningImage) stringResource(Res.string.reading_image) else stringResource(Res.string.load_qr_image)) }
                }

                FilledTonalButton(
                    onClick = {
                        val parsed = runCatching { pasted.trim().toQRData() }.getOrNull()
                        if (parsed == null || parsed.ipAddress.isBlank() || parsed.port <= 0) {
                            statusMessage = invalidMsg
                            return@FilledTonalButton
                        }
                        statusMessage = null
                        if (parsed.hotspotSSID.isNotBlank()) {
                            viewmodel.snacky(
                                "Join Wi-Fi '${parsed.hotspotSSID}' from your OS Wi-Fi menu, " +
                                        "then this connection attempt will succeed."
                            )
                        }
                        manager.isHandshaking.value = true
                        manager.establishTcpClient(parsed)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = pasted.isNotBlank(),
                ) { Text(stringResource(Res.string.connect)) }

                statusMessage?.let {
                    Text(it, fontSize = 12.sp, color = colorScheme.error)
                }
            }

            TextButton(onClick = { viewmodel.cancelDiscovery() }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(Res.string.cancel), fontSize = 13.sp, color = colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun UnsupportedManagerPanel(modifier: Modifier, manager: P2PManager) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            "Unsupported P2P manager on this platform: ${manager::class.simpleName}",
            color = MaterialTheme.colorScheme.error,
        )
    }
}

/**
 * Show an OS file dialog for an image, then run ZXing across it. Returns the
 * decoded text, or null if the user cancelled / nothing was readable. The
 * actual AWT dialog + decoder live in [jetzy.utils.QrImageDecoder] so this
 * composable doesn't drag java.awt imports.
 */
private suspend fun pickAndDecodeQrImage(): String? = withContext(Dispatchers.IO) {
    runCatching { jetzy.utils.QrImageDecoder.pickAndDecode() }
        .onFailure { loggy("QR image decode failed: ${it.message}") }
        .getOrNull()
}
