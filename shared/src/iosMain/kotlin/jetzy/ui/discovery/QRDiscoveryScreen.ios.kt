package jetzy.ui.discovery

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitViewController
import jetzy.managers.LanWifiP2PM
import jetzy.managers.P2PManager
import jetzy.shared.generated.resources.Res
import jetzy.shared.generated.resources.cancel
import jetzy.shared.generated.resources.client_device
import jetzy.shared.generated.resources.connect_failed_title
import jetzy.shared.generated.resources.connect_to_device
import jetzy.shared.generated.resources.ios_hotspot_race_body
import jetzy.shared.generated.resources.ios_hotspot_race_title
import jetzy.shared.generated.resources.point_camera_hint
import jetzy.shared.generated.resources.try_again
import jetzy.ui.LocalViewmodel
import org.jetbrains.compose.resources.stringResource
import jetzy.uiviewcontroller.QRScannerController
import kotlinx.cinterop.ExperimentalForeignApi

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun P2pQrContent(modifier: Modifier, manager: P2PManager) {
    val viewmodel = LocalViewmodel.current
    val colorScheme = MaterialTheme.colorScheme

    val qrController = remember {
        QRScannerController(
            onQrDetected = { qrData ->
                manager.isHandshaking.value = true
                (manager as? LanWifiP2PM)?.establishTcpClient(qrData)
            }
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colorScheme.surface),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
        ) {
            // header
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = stringResource(Res.string.client_device),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.W500,
                    color = colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(Res.string.connect_to_device),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.W500,
                    color = colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = stringResource(Res.string.point_camera_hint),
                    fontSize = 13.sp,
                    color = colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }

            // (No always-on Auto-Join tip here — it's irrelevant for cellular-only
            // users and noise for everyone who never hits the race. The
            // race-recovery dialog below surfaces guidance only when iOS actually
            // associates with the wrong SSID, see [LanWifiP2PM.joinRaceDetected].)

            // main card
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(colorScheme.surfaceContainer)
                    .border(0.5.dp, colorScheme.outlineVariant, RoundedCornerShape(20.dp))
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // viewfinder — fixed size, not full screen
                Box(
                    modifier = Modifier
                        .size(220.dp)
                        .clip(RoundedCornerShape(16.dp))
                ) {
                    UIKitViewController(
                        modifier = Modifier.fillMaxSize(),
                        factory = { qrController },
                        update = {},
                        properties = UIKitInteropProperties(
                            isInteractive = true,
                            isNativeAccessibilityEnabled = true
                        )
                    )
                    ViewfinderOverlay()
                }
            }

            TextButton(
                onClick = {
                    viewmodel.cancelDiscovery()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(Res.string.cancel), fontSize = 13.sp, color = colorScheme.onSurfaceVariant)
            }
        }
    }

    // Failure dialogs. The race dialog fires only when iOS associated with a different
    // network than the one in the scanned QR (auto-join policy beat our join) — see
    // [LanWifiP2PM.joinRaceDetected]. Every other retryable failure (join refused, TCP
    // unreachable, prompt dismissed) goes through the generic Try-again dialog via
    // [LanWifiP2PM.connectFailed] — the snackbars used to advertise a "Retry" this screen
    // didn't have (B6). Dismissing either restarts the camera so the user can re-scan.
    val lanManager = manager as? LanWifiP2PM
    if (lanManager != null) {
        val raceSsid by lanManager.joinRaceDetected.collectAsState()
        val failure by lanManager.connectFailed.collectAsState()
        raceSsid?.let { ssid ->
            RetryDialog(
                title = stringResource(Res.string.ios_hotspot_race_title),
                body = stringResource(Res.string.ios_hotspot_race_body, ssid),
                onRetry = { lanManager.retryConnect() },
                onDismiss = {
                    lanManager.joinRaceDetected.value = null
                    qrController.resumeScanning()
                },
            )
        }
        if (raceSsid == null) failure?.let { msg ->
            RetryDialog(
                title = stringResource(Res.string.connect_failed_title),
                body = msg,
                onRetry = { lanManager.retryConnect() },
                onDismiss = {
                    lanManager.connectFailed.value = null
                    qrController.resumeScanning()
                },
            )
        }
    }
}

@Composable
private fun RetryDialog(title: String, body: String, onRetry: () -> Unit, onDismiss: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            // Modal on outside-clicks so a misplaced tap on the QR viewfinder
            // behind it doesn't silently dismiss the only thing telling the
            // user why their pairing failed.
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
        ),
    ) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = scheme.surface,
            tonalElevation = 6.dp,
            shadowElevation = 12.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = title,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.W600,
                    color = scheme.onSurface,
                )
                Text(
                    text = body,
                    fontSize = 13.sp,
                    color = scheme.onSurfaceVariant,
                    lineHeight = 19.sp,
                    modifier = Modifier.padding(top = 12.dp),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 18.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(Res.string.cancel), fontSize = 14.sp)
                    }
                    FilledTonalButton(
                        onClick = onRetry,
                        modifier = Modifier.weight(1.4f),
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Text(
                            stringResource(Res.string.try_again),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.W600,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ViewfinderOverlay() {
    val colorScheme = MaterialTheme.colorScheme
    val infiniteTransition = rememberInfiniteTransition(label = "scan")
    val scanY by infiniteTransition.animateFloat(
        initialValue = 0.12f,
        targetValue = 0.82f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "scan_y"
    )
    val cornerAlpha by infiniteTransition.animateFloat(
        initialValue = 0.5f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "corner_alpha"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .drawBehind {
                val w = size.width
                val h = size.height
                val scanPx = scanY * h
                val cSize = 22.dp.toPx()
                val cStroke = 2.5.dp.toPx()
                val margin = 10.dp.toPx()
                val color = colorScheme.primary.copy(alpha = cornerAlpha)

                // scan line
                drawLine(
                    color = colorScheme.primary.copy(alpha = .8f),
                    start = Offset(margin, scanPx),
                    end = Offset(w - margin, scanPx),
                    strokeWidth = 1.5.dp.toPx(),
                    cap = StrokeCap.Round,
                )

                // top-left
                drawLine(color, Offset(margin, margin), Offset(margin + cSize, margin), cStroke)
                drawLine(color, Offset(margin, margin), Offset(margin, margin + cSize), cStroke)
                // top-right
                drawLine(color, Offset(w - margin, margin), Offset(w - margin - cSize, margin), cStroke)
                drawLine(color, Offset(w - margin, margin), Offset(w - margin, margin + cSize), cStroke)
                // bottom-left
                drawLine(color, Offset(margin, h - margin), Offset(margin + cSize, h - margin), cStroke)
                drawLine(color, Offset(margin, h - margin), Offset(margin, h - margin - cSize), cStroke)
                // bottom-right
                drawLine(color, Offset(w - margin, h - margin), Offset(w - margin - cSize, h - margin), cStroke)
                drawLine(color, Offset(w - margin, h - margin), Offset(w - margin, h - margin - cSize), cStroke)

                // dim overlay outside reticle center
                drawRect(color = Color(0x33000000))
            }
    )
}

@Composable
private fun NearbyDeviceRow(
    name: String,
    meta: String,
    signalStrength: Int,
    color: Color,
    bgColor: Color,
) {
    val colorScheme = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colorScheme.surfaceContainerHigh)
            .border(0.5.dp, colorScheme.outlineVariant, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(bgColor)
        ) {
            Box(modifier = Modifier
                .size(14.dp, 18.dp)
                .drawBehind {
                    drawRoundRect(
                        color = color,
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()),
                        style = Stroke(width = 1.5.dp.toPx())
                    )
                    drawLine(
                        color = color,
                        start = Offset(size.width * 0.3f, size.height * 0.82f),
                        end = Offset(size.width * 0.7f, size.height * 0.82f),
                        strokeWidth = 1.5.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                }
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(name, fontSize = 13.sp, fontWeight = FontWeight.W500, color = colorScheme.onSurface)
            Text(meta, fontSize = 11.sp, color = colorScheme.onSurfaceVariant)
        }

        SignalBars(strength = signalStrength, color = color)
    }
}

@Composable
private fun SignalBars(strength: Int, color: Color) {
    val colorScheme = MaterialTheme.colorScheme
    val heights = listOf(5.dp, 9.dp, 13.dp, 17.dp)
    Row(
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        heights.forEachIndexed { index, h ->
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(h)
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (index < strength) color else colorScheme.outlineVariant)
            )
        }
    }
}

@Composable
private fun SearchingSpinner() {
    val colorScheme = MaterialTheme.colorScheme
    val infiniteTransition = rememberInfiniteTransition(label = "search_spin")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(700, easing = LinearEasing)),
        label = "spin"
    )
    Box(
        modifier = Modifier.size(12.dp).drawBehind {
            val stroke = 1.5.dp.toPx()
            val r = (size.minDimension - stroke) / 2f
            drawCircle(colorScheme.outlineVariant, radius = r, style = Stroke(stroke))
            drawArc(colorScheme.primary, startAngle = rotation, sweepAngle = 270f,
                useCenter = false, style = Stroke(stroke, cap = StrokeCap.Round)
            )
        }
    )
}
