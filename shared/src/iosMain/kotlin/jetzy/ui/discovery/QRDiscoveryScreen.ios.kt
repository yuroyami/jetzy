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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import jetzy.ui.transfer.BorderWeak
import jetzy.ui.transfer.CardBg
import jetzy.ui.transfer.CardBg2
import jetzy.ui.transfer.Purple400
import jetzy.ui.transfer.Purple600
import jetzy.ui.transfer.SurfaceBg
import jetzy.ui.transfer.TextPrimary
import jetzy.ui.transfer.TextSecondary
import jetzy.ui.transfer.TextTertiary
import jetzy.uiviewcontroller.QRScannerController
import kotlinx.cinterop.ExperimentalForeignApi

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun P2pQrContent(modifier: Modifier, manager: P2PManager) {

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
            .background(SurfaceBg),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
        ) {
            // header
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "CLIENT DEVICE",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.W500,
                    color = TextTertiary,
                )
                Text(
                    text = "Connect to a device",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.W500,
                    color = TextPrimary,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = "Point camera at the host's QR code",
                    fontSize = 13.sp,
                    color = TextSecondary,
                    textAlign = TextAlign.Center,
                )
            }

            // main card
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(CardBg)
                    .border(0.5.dp, BorderWeak, RoundedCornerShape(20.dp))
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

                //OrDivider()

                //NearbyDevicesList()
            }

            TextButton(
                onClick = { /* cancel */ },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Cancel", fontSize = 13.sp, color = TextSecondary)
            }
        }
    }
}

@Composable
private fun ViewfinderOverlay() {
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
                val color = Purple400.copy(alpha = cornerAlpha)

                // scan line
                drawLine(
                    color = Purple400.copy(alpha = .8f),
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
//
//@Composable
//private fun OrDivider() {
//    Row(
//        verticalAlignment = Alignment.CenterVertically,
//        horizontalArrangement = Arrangement.spacedBy(10.dp),
//        modifier = Modifier.fillMaxWidth()
//    ) {
//        Box(Modifier.weight(1f).height(0.5.dp).background(BorderWeak))
//        Text("or connect from nearby", fontSize = 11.sp, color = TextTertiary)
//        Box(Modifier.weight(1f).height(0.5.dp).background(BorderWeak))
//    }
//}
//
//@Composable
//private fun NearbyDevicesList() {
//    // placeholder rows — wire these up to your actual WiFi Direct / NSD discovery results
//    Column(
//        verticalArrangement = Arrangement.spacedBy(8.dp),
//        modifier = Modifier.fillMaxWidth()
//    ) {
//        NearbyDeviceRow(name = "ASUSAI2501B", meta = "Android · hotspot", signalStrength = 3, color = Purple600, bgColor = Purple50)
//        NearbyDeviceRow(name = "MacBook Pro", meta = "macOS · Wi-Fi Direct", signalStrength = 4, color = Teal600, bgColor = Teal50)
//    }
//
//    Row(
//        verticalAlignment = Alignment.CenterVertically,
//        horizontalArrangement = Arrangement.spacedBy(6.dp)
//    ) {
//        SearchingSpinner()
//        Text("Searching for nearby devices", fontSize = 12.sp, color = TextTertiary)
//    }
//}

@Composable
private fun NearbyDeviceRow(
    name: String,
    meta: String,
    signalStrength: Int, // 1..4
    color: Color,
    bgColor: Color,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(CardBg2)
            .border(0.5.dp, BorderWeak, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(bgColor)
        ) {
            // simple phone icon via canvas
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
            Text(name, fontSize = 13.sp, fontWeight = FontWeight.W500, color = TextPrimary)
            Text(meta, fontSize = 11.sp, color = TextTertiary)
        }

        SignalBars(strength = signalStrength, color = color)
    }
}

@Composable
private fun SignalBars(strength: Int, color: Color) {
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
                    .background(if (index < strength) color else BorderWeak)
            )
        }
    }
}

@Composable
private fun SearchingSpinner() {
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
            drawCircle(BorderWeak, radius = r, style = Stroke(stroke))
            drawArc(Purple600, startAngle = rotation, sweepAngle = 270f,
                useCenter = false, style = Stroke(stroke, cap = StrokeCap.Round)
            )
        }
    )
}