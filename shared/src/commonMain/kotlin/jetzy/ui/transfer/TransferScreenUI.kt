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
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewModelScope
import compose.icons.FontAwesomeIcons
import compose.icons.fontawesomeicons.Solid
import compose.icons.fontawesomeicons.solid.Copy
import io.github.vinceglb.filekit.dialogs.compose.rememberDirectoryPickerLauncher
import jetzy.ui.LocalViewmodel
import jetzy.utils.Platform
import jetzy.utils.getDeviceName
import jetzy.utils.platform
import kotlinx.coroutines.launch

@Composable
fun TransferScreenUI() {
    val viewmodel = LocalViewmodel.current
    val manager = viewmodel.p2pManager ?: return

    val manifest by manager.manifest.collectAsState()
    val fileEntries by manager.fileEntries.collectAsState()
    val progress by manager.transferProgress.collectAsState()
    val speed by manager.transferSpeed.collectAsState()
    val remote by manager.remotePeerInfo.collectAsState()

    val transferComplete by manager.transferComplete.collectAsState()
    val saveComplete by manager.saveComplete.collectAsState()

    val colorScheme = MaterialTheme.colorScheme

    if (manifest == null || remote == null) return

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colorScheme.surface),
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

                    // ── Peer row ──────────────────────────────────────────
                    val isSender = viewmodel.isSender
                    val senderName = manifest!!.senderName
                    val senderPlatform = manifest!!.senderPlatform
                    val receiverName = if (isSender) remote!!.name else getDeviceName()
                    val receiverPlatform = if (isSender) remote!!.platform else platform

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 24.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PeerAvatar(
                            name = senderName,
                            label = if (isSender) "sender (You)" else "sender",
                            bgColor = Color.Black,
                            floatDelay = 0,
                            platform = senderPlatform,
                        )
                        PacketAnimation(modifier = Modifier.weight(1f).height(56.dp))
                        PeerAvatar(
                            name = receiverName,
                            label = if (!isSender) "receiver (You)" else "receiver",
                            bgColor = Color.Black,
                            floatDelay = 750,
                            platform = receiverPlatform,
                        )
                    }

                    // ── Overall progress ──────────────────────────────────
                    val completedFiles = fileEntries.count { it.status == FileTransferStatus.Done }
                    ProgressSection(
                        progress = progress,
                        completedCount = completedFiles,
                        totalCount = manifest?.totalFiles ?: 0,
                        totalBytes = manifest?.totalBytes,
                        speedLabel = if (speed > 0) speed.toHumanSize() + "/s" else "—",
                        remainingLabel = remainingLabel(
                            totalBytes = manifest?.totalBytes ?: 0L,
                            progressFrac = progress,
                            speedBytesPerS = speed
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(16.dp))

                    // ── File list — driven entirely by live fileEntries ────
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        fileEntries.forEachIndexed { i, entry ->
                            if (entry.isText) {
                                TextRow(entry = entry, animDelay = i * 60)
                            } else {
                                FileRow(entry = entry, animDelay = i * 60)
                            }
                        }
                    }
                }
            }

            item {
                Spacer(Modifier.height(16.dp))

                // Show "Save files to folder" only when there are actual files (not just texts)
                val hasFiles = manifest?.hasFiles == true

                if (transferComplete && !viewmodel.isSender && !saveComplete && hasFiles) {
                    var savingStarted by remember { mutableStateOf(false) }

                    val destDir = rememberDirectoryPickerLauncher { dir ->
                        dir?.let { destinationDir ->
                            savingStarted = true
                            manager.finalizeReceivedFilesAt(destinationDir)
                        }
                    }

                    Button(
                        onClick = {
                            destDir.launch()
                        },
                        enabled = !savingStarted,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colorScheme.primary,
                            contentColor = colorScheme.onPrimary
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Save files to folder…", fontSize = 13.sp, fontWeight = FontWeight.W500)
                    }
                }

                // Always show Done/Cancel button
                Button(
                    onClick = {
                        viewmodel.viewModelScope.launch {
                            manager.cleanup()
                            viewmodel.resetEverything()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.Transparent,
                        contentColor = colorScheme.onSurfaceVariant,
                    ),
                    border = androidx.compose.foundation.BorderStroke(0.5.dp, colorScheme.outlineVariant),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (transferComplete) "Done" else "Cancel transfer", fontSize = 13.sp, fontWeight = FontWeight.W400)
                }
            }
        }
    }
}

// ─── ETA helper ──────────────────────────────────────────────────────────────

private fun remainingLabel(totalBytes: Long, progressFrac: Float, speedBytesPerS: Long): String {
    if (speedBytesPerS <= 0L || progressFrac <= 0f || progressFrac >= 1f) return "Calculating…"
    val bytesLeft = (totalBytes * (1f - progressFrac)).toLong()
    val secsLeft = bytesLeft / speedBytesPerS
    return when {
        secsLeft < 60 -> "${secsLeft}s remaining"
        secsLeft < 3600 -> "${secsLeft / 60}m ${secsLeft % 60}s remaining"
        else -> "${secsLeft / 3600}h ${(secsLeft % 3600) / 60}m remaining"
    }
}

// ─── Glass card ──────────────────────────────────────────────────────────────

@Composable
private fun GlassCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(colorScheme.surfaceContainer)
            .border(0.5.dp, colorScheme.outlineVariant, RoundedCornerShape(20.dp))
            .padding(20.dp),
        content = content
    )
}

// ─── Peer avatar ──────────────────────────────────────────────────────────────

@Composable
private fun PeerAvatar(
    name: String,
    label: String,
    bgColor: Color,
    platform: Platform,
    floatDelay: Int,
) {
    val colorScheme = MaterialTheme.colorScheme
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
                .border(0.5.dp, colorScheme.outlineVariant, CircleShape)
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
            color = colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 90.dp)
        )
        Text(text = label, fontSize = 11.sp, color = colorScheme.onSurfaceVariant)
    }
}

// ─── Packet animation ─────────────────────────────────────────────────────────

@Composable
private fun PacketAnimation(modifier: Modifier = Modifier) {
    val colorScheme = MaterialTheme.colorScheme
    val infiniteTransition = rememberInfiniteTransition(label = "packets")
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
        val p0 = Offset(0f, cy)
        val cp1 = Offset(w * 0.3f, cy - h * 0.5f)
        val cp2 = Offset(w * 0.7f, cy + h * 0.5f)
        val p3 = Offset(w, cy)

        val pathDash = Path().apply { moveTo(p0.x, p0.y); cubicTo(cp1.x, cp1.y, cp2.x, cp2.y, p3.x, p3.y) }
        drawPath(path = pathDash, color = colorScheme.outlineVariant, style = Stroke(width = 1.dp.toPx(), cap = StrokeCap.Round))

        offsets.forEach { offset ->
            val t = offset.value
            val alpha = when {
                t < 0.08f -> t / 0.08f
                t > 0.92f -> (1f - t) / 0.08f
                else -> 1f
            }
            if (alpha <= 0f) return@forEach
            val x = cubicBezier(p0.x, cp1.x, cp2.x, p3.x, t)
            val y = cubicBezier(p0.y, cp1.y, cp2.y, p3.y, t)
            drawRoundRect(
                color = colorScheme.primary.copy(alpha = alpha),
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
    totalBytes: Long?,
    speedLabel: String,
    remainingLabel: String,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
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
                text = buildString {
                    append("$completedCount of $totalCount files")
                    if (totalBytes != null) append("  ·  ${totalBytes.toHumanSize()} total")
                },
                fontSize = 12.sp,
                color = colorScheme.onSurfaceVariant,
            )
            Text(
                text = "${(animatedProgress * 100).toInt()}%",
                fontSize = 12.sp,
                fontWeight = FontWeight.W500,
                color = colorScheme.onSurface,
            )
        }
        Spacer(Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(99.dp))
                .background(colorScheme.outlineVariant)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(animatedProgress)
                    .clip(RoundedCornerShape(99.dp))
                    .background(colorScheme.primary)
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = remainingLabel, fontSize = 11.sp, color = colorScheme.onSurfaceVariant)
            SpeedBadge(speedLabel = speedLabel)
        }
    }
}

@Composable
private fun SpeedBadge(speedLabel: String) {
    val colorScheme = MaterialTheme.colorScheme
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
            .background(colorScheme.surfaceContainerHigh)
            .border(0.5.dp, colorScheme.outlineVariant, RoundedCornerShape(99.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(colorScheme.tertiary.copy(alpha = dotAlpha))
        )
        Text(text = speedLabel, fontSize = 11.sp, color = colorScheme.onSurfaceVariant)
    }
}

// ─── Text row — for received text entries ─────────────────────────────────────

@Composable
private fun TextRow(
    entry: FileTransferEntry,
    animDelay: Int,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    val clipboardManager = LocalClipboardManager.current
    val alpha by produceState(initialValue = 0f, entry.name, animDelay) {
        kotlinx.coroutines.delay(animDelay.toLong())
        value = 1f
    }

    val rowAlpha = if (entry.status == FileTransferStatus.Pending) 0.45f else 1f

    Column(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer { this.alpha = alpha * rowAlpha }
            .clip(RoundedCornerShape(10.dp))
            .background(colorScheme.surfaceContainer)
            .border(0.5.dp, colorScheme.outlineVariant, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            FileTypeIcon(typeLabel = "TXT")

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Text snippet",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.W500,
                    color = colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(text = entry.sizeLabel, fontSize = 11.sp, color = colorScheme.onSurfaceVariant)
            }

            if (entry.status == FileTransferStatus.Done && entry.textContent != null) {
                IconButton(onClick = {
                    clipboardManager.setText(AnnotatedString(entry.textContent))
                }) {
                    Icon(
                        imageVector = FontAwesomeIcons.Solid.Copy,
                        contentDescription = "Copy text",
                        modifier = Modifier.size(16.dp),
                        tint = colorScheme.primary,
                    )
                }
            } else {
                FileStatusIndicator(status = entry.status)
            }
        }

        // Show a preview of the text content
        if (entry.status == FileTransferStatus.Done && entry.textContent != null) {
            Text(
                text = entry.textContent,
                fontSize = 12.sp,
                color = colorScheme.onSurfaceVariant,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(colorScheme.surfaceContainerLowest)
                    .padding(8.dp)
            )
        }
    }
}

// ─── File row — now driven by FileTransferEntry directly ─────────────────────

@Composable
private fun FileRow(
    entry: FileTransferEntry,
    animDelay: Int,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    val alpha by produceState(initialValue = 0f, entry.name, animDelay) {
        kotlinx.coroutines.delay(animDelay.toLong())
        value = 1f
    }

    val rowAlpha = if (entry.status == FileTransferStatus.Pending) 0.45f else 1f

    // animate per-file secondary progress bar
    val animatedFileProgress by animateFloatAsState(
        targetValue = entry.progress.coerceIn(0f, 1f),
        animationSpec = tween(300, easing = FastOutSlowInEasing),
        label = "file_progress_${entry.name}"
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer { this.alpha = alpha * rowAlpha }
            .clip(RoundedCornerShape(10.dp))
            .background(colorScheme.surfaceContainer)
            .border(0.5.dp, colorScheme.outlineVariant, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            FileTypeIcon(typeLabel = entry.typeLabel)

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.displayName,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.W500,
                    color = colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // show "sent / total" when active, otherwise just total
                val sizeText = when (entry.status) {
                    FileTransferStatus.Active ->
                        "${entry.bytesTransferred.toHumanSize()} / ${entry.sizeLabel}"

                    else -> entry.sizeLabel
                }
                Text(text = sizeText, fontSize = 11.sp, color = colorScheme.onSurfaceVariant)
            }

            FileStatusIndicator(status = entry.status)
        }

        // secondary per-file progress bar — only visible while active
        if (entry.status == FileTransferStatus.Active) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .clip(RoundedCornerShape(99.dp))
                    .background(colorScheme.outlineVariant)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(animatedFileProgress)
                        .clip(RoundedCornerShape(99.dp))
                        .background(colorScheme.primary)
                )
            }
        }
    }
}

// ─── File type icon ───────────────────────────────────────────────────────────

@Composable
private fun FileTypeIcon(typeLabel: String) {
    val colorScheme = MaterialTheme.colorScheme
    val (bg, fg) = when (typeLabel.uppercase()) {
        "VID" -> colorScheme.errorContainer to colorScheme.onErrorContainer
        "DOC" -> colorScheme.tertiaryContainer to colorScheme.onTertiaryContainer
        "PDF" -> colorScheme.errorContainer to colorScheme.onErrorContainer
        "TXT" -> colorScheme.secondaryContainer to colorScheme.onSecondaryContainer
        "ZIP" -> colorScheme.primaryContainer to colorScheme.onPrimaryContainer
        "AUD" -> colorScheme.tertiaryContainer to colorScheme.onTertiaryContainer
        else  -> colorScheme.primaryContainer to colorScheme.onPrimaryContainer
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

// ─── File status indicator ────────────────────────────────────────────────────
@Composable
private fun FileStatusIndicator(status: FileTransferStatus) {
    val colorScheme = MaterialTheme.colorScheme
    when (status) {
        FileTransferStatus.Done -> {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(colorScheme.tertiaryContainer)
            ) {
                Text("✓", fontSize = 12.sp, fontWeight = FontWeight.W600, color = colorScheme.onTertiaryContainer)
            }
        }

        FileTransferStatus.Active -> {
            val infiniteTransition = rememberInfiniteTransition(label = "spinner")
            val rotation by infiniteTransition.animateFloat(
                initialValue = 0f,
                targetValue = 360f,
                animationSpec = infiniteRepeatable(animation = tween(700, easing = LinearEasing)),
                label = "spin"
            )
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .graphicsLayer { rotationZ = rotation }
                    .drawBehind {
                        val stroke = 1.5.dp.toPx()
                        val r = (size.minDimension - stroke) / 2f
                        drawCircle(color = colorScheme.primaryContainer, radius = r, style = Stroke(width = stroke))
                        drawArc(
                            color = colorScheme.primary,
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
                    .background(colorScheme.outlineVariant)
            )
        }

        FileTransferStatus.Failed -> {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(colorScheme.errorContainer)
            ) {
                Text("✕", fontSize = 8.sp, color = colorScheme.onErrorContainer)
            }
        }
    }
}
