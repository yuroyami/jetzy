package jetzy.ui.discovery

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.alexzhirkevich.qrose.options.QrBrush
import io.github.alexzhirkevich.qrose.options.QrColors
import io.github.alexzhirkevich.qrose.options.QrPixelShape
import io.github.alexzhirkevich.qrose.options.QrShapes
import io.github.alexzhirkevich.qrose.options.roundCorners
import io.github.alexzhirkevich.qrose.options.solid
import io.github.alexzhirkevich.qrose.rememberQrCodePainter
import jetzy.managers.HotspotP2PM
import jetzy.managers.P2PManager
import jetzy.models.QRData
import jetzy.ui.LocalViewmodel

@Composable
actual fun P2pQrContent(modifier: Modifier, manager: P2PManager) {
    val viewmodel = LocalViewmodel.current
    val colorScheme = MaterialTheme.colorScheme

    var qrData by remember { mutableStateOf<QRData?>(null) }
    var refreshor by remember { mutableIntStateOf(0) }

    LaunchedEffect(refreshor) {
        qrData = null
        qrData = (manager as? HotspotP2PM)?.establishTcpServer()?.await()
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
                    text = "HOST DEVICE",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.W500,
                    letterSpacing = 0.08.sp,
                    color = colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "Share to nearby device",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.W500,
                    color = colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = "Let the other device scan this code to connect instantly",
                    fontSize = 13.sp,
                    color = colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    lineHeight = 19.sp,
                )
            }

            // QR card
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(colorScheme.surfaceContainer)
                    .border(0.5.dp, colorScheme.outlineVariant, RoundedCornerShape(20.dp))
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (qrData != null) {
                    QrCodeBlock(qrData = qrData!!)
                    LivePill(ssid = qrData!!.hotspotSSID)
                    CredentialsRow(qrData = qrData!!)
                } else {
                    QrLoadingBlock()
                }
            }

            // cancel
            TextButton(
                onClick = {
                    viewmodel.resetEverything()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Cancel",
                    fontSize = 13.sp,
                    color = colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun QrCodeBlock(qrData: QRData) {
    val colorScheme = MaterialTheme.colorScheme
    val infiniteTransition = rememberInfiniteTransition(label = "scan")
    val scanY by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "scan_y"
    )
    val cornerAlpha by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "corner_alpha"
    )

    val qrPainter = rememberQrCodePainter(
        data = qrData.toString(),
        shapes = QrShapes(darkPixel = QrPixelShape.roundCorners()),
        colors = QrColors(
            dark = QrBrush.solid(colorScheme.primary),
        )
    )

    Box(
        modifier = Modifier
            .size(200.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White)
            .drawBehind {
                val w = size.width
                val h = size.height
                val scanPx = scanY * h * 0.7f + h * 0.12f

                // scan line
                drawLine(
                    color = colorScheme.primary.copy(alpha = .7f),
                    start = Offset(w * 0.07f, scanPx),
                    end = Offset(w * 0.93f, scanPx),
                    strokeWidth = 1.5.dp.toPx(),
                    cap = StrokeCap.Round,
                )

                // corner brackets
                val cSize = 18.dp.toPx()
                val cStroke = 2.5.dp.toPx()
                val margin = 4.dp.toPx()
                val color = colorScheme.primary.copy(alpha = cornerAlpha)

                // top-left
                drawLine(color, Offset(margin, margin + cSize), Offset(margin, margin), cStroke.dp.toPx().let { margin })
                drawLine(color, start = Offset(margin, margin), end = Offset(margin + cSize, margin), strokeWidth = cStroke)
                drawLine(color, start = Offset(margin, margin), end = Offset(margin, margin + cSize), strokeWidth = cStroke)
                // top-right
                drawLine(color, start = Offset(w - margin, margin), end = Offset(w - margin - cSize, margin), strokeWidth = cStroke)
                drawLine(color, start = Offset(w - margin, margin), end = Offset(w - margin, margin + cSize), strokeWidth = cStroke)
                // bottom-left
                drawLine(color, start = Offset(margin, h - margin), end = Offset(margin + cSize, h - margin), strokeWidth = cStroke)
                drawLine(color, start = Offset(margin, h - margin), end = Offset(margin, h - margin - cSize), strokeWidth = cStroke)
                // bottom-right
                drawLine(color, start = Offset(w - margin, h - margin), end = Offset(w - margin - cSize, h - margin), strokeWidth = cStroke)
                drawLine(color, start = Offset(w - margin, h - margin), end = Offset(w - margin, h - margin - cSize), strokeWidth = cStroke)
            }
    ) {
        Image(
            painter = qrPainter,
            contentDescription = "QR code to connect",
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp)
        )
    }
}

@Composable
private fun QrLoadingBlock() {
    val colorScheme = MaterialTheme.colorScheme
    val infiniteTransition = rememberInfiniteTransition(label = "loading")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "shimmer"
    )
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(200.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(colorScheme.primaryContainer.copy(alpha = alpha))
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SpinnerCircle()
            Text(
                text = "Starting hotspot...",
                fontSize = 12.sp,
                color = colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SpinnerCircle() {
    val colorScheme = MaterialTheme.colorScheme
    val infiniteTransition = rememberInfiniteTransition(label = "spin")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(700, easing = LinearEasing)),
        label = "rotation"
    )
    Box(
        modifier = Modifier
            .size(24.dp)
            .drawBehind {
                val stroke = 2.dp.toPx()
                val r = (size.minDimension - stroke) / 2f
                drawCircle(colorScheme.primaryContainer, radius = r, style = Stroke(stroke))
                drawArc(
                    color = colorScheme.primary,
                    startAngle = rotation,
                    sweepAngle = 270f,
                    useCenter = false,
                    style = Stroke(stroke, cap = StrokeCap.Round)
                )
            }
    )
}

@Composable
private fun LivePill(ssid: String) {
    val colorScheme = MaterialTheme.colorScheme
    val infiniteTransition = rememberInfiniteTransition(label = "dot")
    val dotAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "dot_alpha"
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(99.dp))
            .background(colorScheme.surfaceContainerHigh)
            .border(0.5.dp, colorScheme.outlineVariant, RoundedCornerShape(99.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(colorScheme.tertiary.copy(alpha = dotAlpha))
        )
        Text(
            text = "Hotspot active — $ssid",
            fontSize = 12.sp,
            color = colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun CredentialsRow(qrData: QRData) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        CredCard(
            label = "Network",
            value = qrData.hotspotSSID,
            modifier = Modifier.weight(1f)
        )
        CredCard(
            label = "Password",
            value = qrData.hotspotPassword.take(8) + "••••",
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun CredCard(label: String, value: String, modifier: Modifier = Modifier) {
    val colorScheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(colorScheme.surfaceContainerHigh)
            .border(0.5.dp, colorScheme.outlineVariant, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            color = colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            fontSize = 13.sp,
            fontWeight = FontWeight.W500,
            color = colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
