package jetzy.ui.transfer

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jetzy.ui.LocalViewmodel
import jetzy.utils.Platform

@Composable
fun TransferScreenUI() {
    val viewmodel = LocalViewmodel.current
    val manager = viewmodel.p2pManager ?: return

    val transferState by viewmodel.transferState.collectAsState()

    transferState?.let { state ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(SurfaceBg),
            contentAlignment = Alignment.TopCenter
        ) {
            LazyColumn(
                horizontalAlignment = Alignment.CenterHorizontally,
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 32.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                item {
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        // Peer row
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 24.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            PeerAvatar(
                                name = state.senderName,
                                label = "sender",
                                bgColor = Purple600,
                                floatDelay = 0,
                                platform = Platform.Android, //TODO
                            )

                            PacketAnimation(modifier = Modifier.weight(1f).height(56.dp))

                            PeerAvatar(
                                name = state.receiverName,
                                label = "receiver",
                                bgColor = Teal600,
                                floatDelay = 750,
                                platform = Platform.IOS //TODO
                            )
                        }

                        // Progress
                        val progress by manager.transferProgress.collectAsState()
                        val speed by manager.transferSpeed.collectAsState()
                        ProgressSection(
                            progress = progress,
                            completedCount = state.completedCount,
                            totalCount = state.totalCount,
                            speedLabel = "${speed.div(1_000_000L)} MB/s", //TODO
                            remainingLabel = "ETA: 3 minutes remaining", //TODO
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(Modifier.height(16.dp))

                        // File list
                        Column(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (state.isSender) {
                                viewmodel.elementsToSend.forEachIndexed { i, file ->
//                                    FileRow(
//                                        file = file,
//                                        animDelay = i * 60,
//                                    )
                                }
                            } else {
                                viewmodel.elementsReceived.forEachIndexed { index, element ->

                                }
                            }
                        }
                    }
                }

                item {
                    Spacer(Modifier.height(16.dp))

                    Button(
                        onClick = {
                            //TODO
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.Transparent,
                            contentColor = TextSecondary,
                        ),
                        border = androidx.compose.foundation.BorderStroke(0.5.dp, BorderMid),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Cancel transfer",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.W400,
                        )
                    }
                }
            }
        }
    }
}

// ─── Glass card ──────────────────────────────────────────────────────────────

@Composable
private fun GlassCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(CardBg)
            .border(0.5.dp, BorderWeak, RoundedCornerShape(20.dp))
            .padding(20.dp),
        content = content
    )
}

@Composable
private fun PeerAvatar(
    name: String,
    label: String,
    bgColor: Color,
    platform: Platform,
    floatDelay: Int,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "float_$name")
    val offsetY by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = -6f,
        animationSpec = infiniteRepeatable(
            animation = tween(1600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
            initialStartOffset = StartOffset(floatDelay)
        ),
        label = "float_y_$name"
    )

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .offset(y = offsetY.dp)
                .size(52.dp)
                .clip(CircleShape)
                .background(bgColor)
                .border(0.5.dp, BorderWeak, CircleShape)
        ) {
            Icon(
                modifier = Modifier.size(48.dp).padding(4.dp),
                imageVector = platform.icon,
                contentDescription = null,
                tint = platform.brandColor
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = name,
            fontSize = 13.sp,
            fontWeight = FontWeight.W500,
            color = TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 90.dp)
        )
        Text(
            text = label,
            fontSize = 11.sp,
            color = TextTertiary,
        )
    }
}

// ─── Packet animation (curved path with traveling dots) ─────────────────────
@Composable
private fun PacketAnimation(modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "packets")

    // 3 packets staggered by 0.33 of the cycle
    val offsets = listOf(0, 400, 800).map { delay ->
        infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(1200, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
                initialStartOffset = StartOffset(delay)
            ),
            label = "packet_$delay"
        )
    }

    Box(modifier = modifier.drawBehind {
        val w = size.width
        val h = size.height
        val cy = h / 2f

        // Control points for the cubic bezier wave
        val p0 = Offset(0f, cy)
        val cp1 = Offset(w * 0.3f, cy - h * 0.5f)
        val cp2 = Offset(w * 0.7f, cy + h * 0.5f)
        val p3 = Offset(w, cy)

        // Draw dashed guide path
        val pathDash = Path().apply {
            moveTo(p0.x, p0.y)
            cubicTo(cp1.x, cp1.y, cp2.x, cp2.y, p3.x, p3.y)
        }
        drawPath(
            path = pathDash,
            color = BorderWeak,
            style = Stroke(width = 1.dp.toPx(), cap = StrokeCap.Round)
        )

        // Draw each traveling packet
        offsets.forEach { offset ->
            val t = offset.value

            // Fade: 0→0.08 fade in, 0.92→1 fade out
            val alpha = when {
                t < 0.08f -> t / 0.08f
                t > 0.92f -> (1f - t) / 0.08f
                else -> 1f
            }
            if (alpha <= 0f) return@forEach

            // Evaluate cubic bezier at t
            val x = cubicBezier(p0.x, cp1.x, cp2.x, p3.x, t)
            val y = cubicBezier(p0.y, cp1.y, cp2.y, p3.y, t)

            drawRoundRect(
                color = Purple400.copy(alpha = alpha),
                topLeft = Offset(x - 4.dp.toPx(), y - 4.dp.toPx()),
                size = androidx.compose.ui.geometry.Size(8.dp.toPx(), 8.dp.toPx()),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()),
            )
        }
    })
}

private fun cubicBezier(p0: Float, p1: Float, p2: Float, p3: Float, t: Float): Float {
    val mt = 1f - t
    return mt * mt * mt * p0 + 3f * mt * mt * t * p1 + 3f * mt * t * t * p2 + t * t * t * p3
}

// ─── Progress section ─────────────────────────────────────────────────────────

@Composable
private fun ProgressSection(
    progress: Float,
    completedCount: Int,
    totalCount: Int,
    speedLabel: String,
    remainingLabel: String,
    modifier: Modifier = Modifier,
) {
    val animatedProgress by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(400, easing = FastOutSlowInEasing),
        label = "progress"
    )

    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "$completedCount of $totalCount files",
                fontSize = 12.sp,
                color = TextSecondary,
            )
            Text(
                text = "${(animatedProgress * 100).toInt()}%",
                fontSize = 12.sp,
                fontWeight = FontWeight.W500,
                color = TextPrimary,
            )
        }

        Spacer(Modifier.height(8.dp))

        // Track
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(99.dp))
                .background(BorderWeak)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(animatedProgress)
                    .clip(RoundedCornerShape(99.dp))
                    .background(Purple600)
            )
        }

        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = remainingLabel,
                fontSize = 11.sp,
                color = TextTertiary,
            )
            SpeedBadge(speedLabel = speedLabel)
        }
    }
}

@Composable
private fun SpeedBadge(speedLabel: String) {
    val infiniteTransition = rememberInfiniteTransition(label = "live_dot")
    val dotAlpha by infiniteTransition.animateFloat(
        initialValue = 0.18f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "dot_alpha"
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(99.dp))
            .background(CardBg2)
            .border(0.5.dp, BorderWeak, RoundedCornerShape(99.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(Teal600.copy(alpha = dotAlpha))
        )
        Text(
            text = speedLabel,
            fontSize = 11.sp,
            color = TextSecondary,
        )
    }
}

// ─── File row ─────────────────────────────────────────────────────────────────

@Composable
private fun FileRow(
    file: TransferFile,
    animDelay: Int,
    modifier: Modifier = Modifier,
) {
    val alpha by produceState(initialValue = 0f, file, animDelay) {
        kotlinx.coroutines.delay(animDelay.toLong())
        value = 1f
    }

    val rowAlpha = if (file.status == FileTransferStatus.Pending) 0.45f else 1f

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(CardBg)
            .border(0.5.dp, BorderWeak, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp)
            // fade-in on entry
            .graphicsLayer { this.alpha = rowAlpha }
    ) {
        FileTypeIcon(typeLabel = file.typeLabel)

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = file.name,
                fontSize = 13.sp,
                fontWeight = FontWeight.W500,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = file.sizeLabel,
                fontSize = 11.sp,
                color = TextTertiary,
            )
        }

        FileStatusIndicator(status = file.status)
    }
}

@Composable
private fun FileTypeIcon(typeLabel: String) {
    val (bg, fg) = when (typeLabel.uppercase()) {
        "VID" -> Coral50 to Coral800
        "DOC" -> Teal50 to Teal800
        else -> Purple50 to Purple800   // IMG and anything else
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(28.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
    ) {
        Text(
            text = typeLabel.take(3).uppercase(),
            fontSize = 9.sp,
            fontWeight = FontWeight.W500,
            color = fg,
        )
    }
}

@Composable
private fun FileStatusIndicator(status: FileTransferStatus) {
    when (status) {
        FileTransferStatus.Done -> {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(Teal50)
            ) {
                Text("✓", fontSize = 8.sp, color = Teal800)
            }
        }

        FileTransferStatus.Active -> {
            val infiniteTransition = rememberInfiniteTransition(label = "spinner")
            val rotation by infiniteTransition.animateFloat(
                initialValue = 0f,
                targetValue = 360f,
                animationSpec = infiniteRepeatable(
                    animation = tween(700, easing = LinearEasing)
                ),
                label = "spin"
            )
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .graphicsLayer { rotationZ = rotation }
                    .drawBehind {
                        val stroke = 1.5.dp.toPx()
                        val r = (size.minDimension - stroke) / 2f
                        // track
                        drawCircle(
                            color = Purple100,
                            radius = r,
                            style = Stroke(width = stroke)
                        )
                        // active arc (~270°)
                        drawArc(
                            color = Purple600,
                            startAngle = 0f,
                            sweepAngle = 270f,
                            useCenter = false,
                            style = Stroke(width = stroke, cap = StrokeCap.Round)
                        )
                    }
            )
        }

        FileTransferStatus.Pending -> {
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(BorderWeak)
            )
        }
    }
}