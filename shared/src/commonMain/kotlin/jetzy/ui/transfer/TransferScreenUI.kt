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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewModelScope
import jetzy.theme.sdp
import jetzy.theme.ssp
import compose.icons.FontAwesomeIcons
import compose.icons.fontawesomeicons.Solid
import compose.icons.fontawesomeicons.solid.Copy
import io.github.vinceglb.filekit.dialogs.compose.rememberDirectoryPickerLauncher
import jetzy.shared.generated.resources.Res
import jetzy.shared.generated.resources.cancel_transfer
import jetzy.shared.generated.resources.connecting
import jetzy.shared.generated.resources.copy_text
import jetzy.shared.generated.resources.done
import jetzy.shared.generated.resources.receiver
import jetzy.shared.generated.resources.receiver_you
import jetzy.shared.generated.resources.reconnect_and_resume
import jetzy.shared.generated.resources.save_files_to_folder
import jetzy.shared.generated.resources.sender
import jetzy.shared.generated.resources.sender_you
import jetzy.shared.generated.resources.text_snippet
import jetzy.p2p.TransportMatch
import jetzy.ui.LocalViewmodel
import jetzy.utils.Platform
import jetzy.utils.getDeviceName
import jetzy.utils.platform
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

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
    val canResume by manager.canResume.collectAsState()
    val isSaving by manager.isSaving.collectAsState()
    // Non-null once received files have been auto-saved to a default, user-visible folder.
    val savedLabel by manager.savedLocationLabel.collectAsState()
    // The live result of the on-handshake transport negotiation: a mutually-supported link
    // faster than the one we bootstrapped on, if any. Non-null ⇒ we can show the gear-shift hint.
    val upgrade by manager.recommendedUpgrade.collectAsState()

    val colorScheme = MaterialTheme.colorScheme

    // On the receiver, the manifest/peer only arrive after the handshake. Show a connecting
    // state with a way out instead of a blank screen that traps the user if it hangs.
    // Captured into locals so everything below smart-casts non-null — the old `manifest!!`
    // pattern was one refactor away from an NPE (remote was only coincidentally non-null
    // whenever manifest was).
    val mf = manifest
    val peer = remote
    if (mf == null || peer == null) {
        ConnectingState(
            // Cancel ≠ discard: route through cancelDiscovery (which back-press already uses)
            // so a cancelled connect keeps the staged tray for an immediate retry.
            onCancel = { viewmodel.cancelDiscovery() }
        )
        return
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colorScheme.surface),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = PaddingValues(horizontal = 14.sdp, vertical = 20.sdp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            item {
                GlassCard(modifier = Modifier.fillMaxWidth()) {

                    // ── Peer row ──────────────────────────────────────────
                    val isSender = viewmodel.isSender
                    val senderName = mf.senderName
                    val senderPlatform = mf.senderPlatform
                    val receiverName = if (isSender) peer.name else getDeviceName()
                    val receiverPlatform = if (isSender) peer.platform else platform

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.sdp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PeerAvatar(
                            name = senderName,
                            label = if (isSender) stringResource(Res.string.sender_you) else stringResource(Res.string.sender),
                            bgColor = Color.Black,
                            floatDelay = 0,
                            platform = senderPlatform,
                        )
                        PacketAnimation(modifier = Modifier.weight(1f).height(40.sdp))
                        PeerAvatar(
                            name = receiverName,
                            label = if (!isSender) stringResource(Res.string.receiver_you) else stringResource(Res.string.receiver),
                            bgColor = Color.Black,
                            floatDelay = 750,
                            platform = receiverPlatform,
                        )
                    }

                    // ── Overall progress ──────────────────────────────────
                    // Derived so the O(n) scan only re-runs when the count actually changes,
                    // not on every recomposition of this card.
                    val completedFiles by remember {
                        derivedStateOf { fileEntries.count { it.status == FileTransferStatus.Done } }
                    }
                    val speedLabel = remember(speed) {
                        if (speed > 0) speed.toHumanSize() + "/s" else "—"
                    }
                    val remainingStr = remember(progress, speed, mf.totalBytes) {
                        remainingLabel(
                            totalBytes = mf.totalBytes,
                            progressFrac = progress,
                            speedBytesPerS = speed
                        )
                    }
                    ProgressSection(
                        progress = progress,
                        completedCount = completedFiles,
                        totalCount = mf.totalFiles,
                        totalBytes = mf.totalBytes,
                        speedLabel = speedLabel,
                        remainingLabel = remainingStr,
                        modifier = Modifier.fillMaxWidth()
                    )

                    // The negotiation brain ran live on the handshake (see P2PManager
                    // .negotiatePeerCapabilities). When a faster mutual transport than the one we
                    // bootstrapped on exists, hint it here. Acting on it (the in-band UPGRADE that
                    // gear-shifts the live stream onto this link) is the next milestone.
                    upgrade?.let {
                        Spacer(Modifier.height(10.sdp))
                        UpgradeAvailableBadge(it, Modifier.align(Alignment.CenterHorizontally))
                    }

                    Spacer(Modifier.height(12.sdp))

                    // ── File list — driven entirely by live fileEntries ────
                    Column(
                        verticalArrangement = Arrangement.spacedBy(6.sdp),
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
                Spacer(Modifier.height(12.sdp))

                // Received files are auto-saved to a default, user-visible folder the instant the
                // transfer verifies (P2PManager.autoSaveReceivedFiles). Show a confirmation; only
                // fall back to a manual folder picker if that auto-save didn't happen. Keyed on
                // actually-received items (not isSender): in a bidirectional session the side that
                // received in phase 1 is the *sender* by the end, and a failed batch can still
                // hold verified items worth saving.
                val hasReceivedItems = manager.itemsRECEIVED.isNotEmpty()

                if (transferComplete && hasReceivedItems) {
                    val savedTo = savedLabel
                    if (saveComplete && savedTo != null) {
                        Text(
                            text = "✓ Saved to $savedTo",
                            color = colorScheme.primary,
                            fontSize = 10.ssp,
                            fontWeight = FontWeight.W500,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 6.sdp),
                            textAlign = TextAlign.Center,
                        )
                    } else if (!saveComplete) {
                        // Fallback: auto-save couldn't persist (rare) — let the user pick a folder.
                        // isSaving is hoisted to TransferScreenUI so this branch never violates
                        // Compose's no-conditional-composable-call rule.
                        val destDir = rememberDirectoryPickerLauncher { dir ->
                            dir?.let { destinationDir ->
                                manager.finalizeReceivedFilesAt(destinationDir)
                            }
                        }

                        Button(
                            onClick = {
                                destDir.launch()
                            },
                            enabled = !isSaving,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = colorScheme.primary,
                                contentColor = colorScheme.onPrimary
                            ),
                            shape = RoundedCornerShape(8.sdp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(Res.string.save_files_to_folder), fontSize = 10.ssp, fontWeight = FontWeight.W500)
                        }
                    }
                }

                // Resume affordance — visible when the transfer broke mid-way and we
                // have partial data worth keeping. Re-opens the QR/discovery flow with
                // the same session id so the protocol replies RESUME on next handshake.
                // No !saveComplete here: the verified subset of a broken batch is auto-saved
                // (saveComplete can be true) while the remainder is still owed and resumable.
                if (transferComplete && canResume) {
                    Button(
                        onClick = { viewmodel.resumeDiscovery() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colorScheme.secondaryContainer,
                            contentColor = colorScheme.onSecondaryContainer,
                        ),
                        shape = RoundedCornerShape(8.sdp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = stringResource(Res.string.reconnect_and_resume),
                            fontSize = 10.ssp,
                            fontWeight = FontWeight.W500,
                        )
                    }
                    Spacer(Modifier.height(6.sdp))
                }

                // Always show Done/Cancel button — but never let it fire while a save pass is
                // moving files: its cleanup() purges temps and cancels children, which used to
                // kill the in-flight auto-save and delete the very files it was persisting.
                Button(
                    onClick = {
                        if (transferComplete) {
                            viewmodel.viewModelScope.launch {
                                manager.cleanup()
                                viewmodel.resetEverything()
                            }
                        } else {
                            // Cancel ≠ discard: keep the staged tray (same path as back-press)
                            // so cancelling a wrong-peer connect doesn't force a full re-pick.
                            viewmodel.cancelDiscovery()
                        }
                    },
                    enabled = !isSaving,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.Transparent,
                        contentColor = colorScheme.onSurfaceVariant,
                    ),
                    border = androidx.compose.foundation.BorderStroke(0.5.dp, colorScheme.outlineVariant),
                    shape = RoundedCornerShape(8.sdp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (transferComplete) stringResource(Res.string.done) else stringResource(Res.string.cancel_transfer), fontSize = 10.ssp, fontWeight = FontWeight.W400)
                }
            }
        }
    }
}

// ─── Connecting / handshake state ─────────────────────────────────────────────

@Composable
private fun ConnectingState(onCancel: () -> Unit) {
    val colorScheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier.fillMaxSize().background(colorScheme.surface),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.sdp)
        ) {
            CircularProgressIndicator(color = colorScheme.primary)
            Text(
                text = stringResource(Res.string.connecting),
                fontSize = 11.ssp,
                color = colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = onCancel,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Transparent,
                    contentColor = colorScheme.onSurfaceVariant,
                ),
                border = androidx.compose.foundation.BorderStroke(0.5.dp, colorScheme.outlineVariant),
                shape = RoundedCornerShape(8.sdp),
            ) {
                Text(stringResource(Res.string.cancel_transfer), fontSize = 10.ssp)
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

// ─── Upgrade badge ───────────────────────────────────────────────────────────

/**
 * "⚡ Faster link available" pill. Surfaces the result of the live transport negotiation — proof
 * that the once-dead negotiator now runs on the real handshake — and the visible anchor the in-band
 * UPGRADE (gear-shift) will hang off of next.
 */
@Composable
private fun UpgradeAvailableBadge(match: TransportMatch, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(cs.primary.copy(alpha = 0.12f))
            .padding(horizontal = 12.sdp, vertical = 6.sdp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.sdp),
    ) {
        Text("⚡", fontSize = 12.ssp)
        Text(
            text = "Faster link available: ${match.technology.displayName}",
            color = cs.primary,
            fontWeight = FontWeight.SemiBold,
            fontSize = 12.ssp,
        )
    }
}

@Composable
private fun GlassCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.sdp))
            .background(colorScheme.surfaceContainer)
            .border(0.5.dp, colorScheme.outlineVariant, RoundedCornerShape(14.sdp))
            .padding(14.sdp),
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
    val transLabel = remember(name) { "float_$name" }
    val animLabel  = remember(name) { "float_y_$name" }
    val infiniteTransition = rememberInfiniteTransition(label = transLabel)
    val offsetY by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = -6f,
        animationSpec = infiniteRepeatable(
            animation = tween(1600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
            initialStartOffset = StartOffset(floatDelay)
        ),
        label = animLabel
    )
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .offset(y = offsetY.dp)
                .size(40.sdp)
                .clip(CircleShape)
                .background(bgColor)
                .border(0.5.dp, colorScheme.outlineVariant, CircleShape)
        ) {
            Icon(
                modifier = Modifier.size(36.sdp).padding(3.sdp),
                imageVector = platform.peerIcon,
                contentDescription = null,
                tint = platform.peerBrandColor
            )
        }
        Spacer(Modifier.height(4.sdp))
        Text(
            text = name,
            fontSize = 10.ssp,
            fontWeight = FontWeight.W500,
            color = colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 70.sdp)
        )
        Text(text = label, fontSize = 9.ssp, color = colorScheme.onSurfaceVariant)
    }
}

// ─── Packet animation ─────────────────────────────────────────────────────────

@Composable
private fun PacketAnimation(modifier: Modifier = Modifier) {
    val colorScheme = MaterialTheme.colorScheme
    val infiniteTransition = rememberInfiniteTransition(label = "packets")
    // Use individual vals so each animateFloat call has a stable composition slot (no list allocation).
    val offset0 by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
            initialStartOffset = StartOffset(0)
        ), label = "packet_0"
    )
    val offset1 by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
            initialStartOffset = StartOffset(400)
        ), label = "packet_400"
    )
    val offset2 by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
            initialStartOffset = StartOffset(800)
        ), label = "packet_800"
    )
    // Hoist Path allocation out of the per-frame draw lambda; reset+rebuild each frame.
    val cachedPath = remember { Path() }
    Box(modifier = modifier.drawBehind {
        val w = size.width
        val h = size.height
        val cy = h / 2f
        val p0x = 0f;        val p0y = cy
        val cp1x = w * 0.3f; val cp1y = cy - h * 0.5f
        val cp2x = w * 0.7f; val cp2y = cy + h * 0.5f
        val p3x = w;         val p3y = cy

        cachedPath.reset()
        cachedPath.moveTo(p0x, p0y)
        cachedPath.cubicTo(cp1x, cp1y, cp2x, cp2y, p3x, p3y)
        drawPath(path = cachedPath, color = colorScheme.outlineVariant, style = Stroke(width = 1.dp.toPx(), cap = StrokeCap.Round))

        // Pre-compute constant pixel sizes once per frame (density-stable).
        val halfDot = 4.dp.toPx()
        val dotSizePx = 8.dp.toPx()
        val dotRadiusPx = 2.dp.toPx()

        listOf(offset0, offset1, offset2).forEach { t ->
            val alpha = when {
                t < 0.08f -> t / 0.08f
                t > 0.92f -> (1f - t) / 0.08f
                else -> 1f
            }
            if (alpha <= 0f) return@forEach
            val x = cubicBezier(p0x, cp1x, cp2x, p3x, t)
            val y = cubicBezier(p0y, cp1y, cp2y, p3y, t)
            drawRoundRect(
                color = colorScheme.primary.copy(alpha = alpha),
                topLeft = Offset(x - halfDot, y - halfDot),
                size = androidx.compose.ui.geometry.Size(dotSizePx, dotSizePx),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(dotRadiusPx),
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
            val fileCountLabel = remember(completedCount, totalCount, totalBytes) {
                buildString {
                    append("$completedCount of $totalCount files")
                    if (totalBytes != null) append("  ·  ${totalBytes.toHumanSize()} total")
                }
            }
            Text(
                text = fileCountLabel,
                fontSize = 10.ssp,
                color = colorScheme.onSurfaceVariant,
            )
            Text(
                text = "${(animatedProgress * 100).toInt()}%",
                fontSize = 10.ssp,
                fontWeight = FontWeight.W500,
                color = colorScheme.onSurface,
            )
        }
        Spacer(Modifier.height(6.sdp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(3.sdp)
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
        Spacer(Modifier.height(6.sdp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = remainingLabel, fontSize = 9.ssp, color = colorScheme.onSurfaceVariant)
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
        horizontalArrangement = Arrangement.spacedBy(3.sdp),
        modifier = Modifier
            .clip(RoundedCornerShape(99.dp))
            .background(colorScheme.surfaceContainerHigh)
            .border(0.5.dp, colorScheme.outlineVariant, RoundedCornerShape(99.dp))
            .padding(horizontal = 6.sdp, vertical = 2.sdp)
    ) {
        Box(
            modifier = Modifier
                .size(5.sdp)
                .graphicsLayer { alpha = dotAlpha }
                .clip(CircleShape)
                .background(colorScheme.tertiary)
        )
        Text(text = speedLabel, fontSize = 9.ssp, color = colorScheme.onSurfaceVariant)
    }
}

// ─── Text row — for received text entries ─────────────────────────────────────

// TODO: migrate from deprecated LocalClipboardManager to LocalClipboard (suspend API).
@Suppress("DEPRECATION")
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
            .clip(RoundedCornerShape(8.sdp))
            .background(colorScheme.surfaceContainer)
            .border(0.5.dp, colorScheme.outlineVariant, RoundedCornerShape(8.sdp))
            .padding(horizontal = 10.sdp, vertical = 6.sdp),
        verticalArrangement = Arrangement.spacedBy(4.sdp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.sdp),
            modifier = Modifier.fillMaxWidth()
        ) {
            FileTypeIcon(typeLabel = "TXT")

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(Res.string.text_snippet),
                    fontSize = 10.ssp,
                    fontWeight = FontWeight.W500,
                    color = colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(text = entry.sizeLabel, fontSize = 9.ssp, color = colorScheme.onSurfaceVariant)
            }

            if (entry.status == FileTransferStatus.Done && entry.textContent != null) {
                IconButton(onClick = {
                    clipboardManager.setText(AnnotatedString(entry.textContent))
                }) {
                    Icon(
                        imageVector = FontAwesomeIcons.Solid.Copy,
                        contentDescription = stringResource(Res.string.copy_text),
                        modifier = Modifier.size(14.sdp),
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
                fontSize = 10.ssp,
                color = colorScheme.onSurfaceVariant,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(5.sdp))
                    .background(colorScheme.surfaceContainerLowest)
                    .padding(6.sdp)
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
    val fileProgressLabel = remember(entry.name) { "file_progress_${entry.name}" }
    val animatedFileProgress by animateFloatAsState(
        targetValue = entry.progress.coerceIn(0f, 1f),
        animationSpec = tween(300, easing = FastOutSlowInEasing),
        label = fileProgressLabel
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer { this.alpha = alpha * rowAlpha }
            .clip(RoundedCornerShape(8.sdp))
            .background(colorScheme.surfaceContainer)
            .border(0.5.dp, colorScheme.outlineVariant, RoundedCornerShape(8.sdp))
            .padding(horizontal = 10.sdp, vertical = 6.sdp),
        verticalArrangement = Arrangement.spacedBy(4.sdp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.sdp),
            modifier = Modifier.fillMaxWidth()
        ) {
            FileTypeIcon(typeLabel = entry.typeLabel)

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.displayName,
                    fontSize = 10.ssp,
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
                Text(text = sizeText, fontSize = 9.ssp, color = colorScheme.onSurfaceVariant)
            }

            FileStatusIndicator(status = entry.status)
        }

        // secondary per-file progress bar — only visible while active
        if (entry.status == FileTransferStatus.Active) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.sdp)
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
    // Cache the uppercased label and the color pair — typeLabel is stable for any given file.
    val upper = remember(typeLabel) { typeLabel.take(3).uppercase() }
    val (bg, fg) = remember(typeLabel, colorScheme) {
        when (typeLabel.uppercase()) {
            "VID" -> colorScheme.errorContainer to colorScheme.onErrorContainer
            "DOC" -> colorScheme.tertiaryContainer to colorScheme.onTertiaryContainer
            "PDF" -> colorScheme.errorContainer to colorScheme.onErrorContainer
            "TXT" -> colorScheme.secondaryContainer to colorScheme.onSecondaryContainer
            "ZIP" -> colorScheme.primaryContainer to colorScheme.onPrimaryContainer
            "AUD" -> colorScheme.tertiaryContainer to colorScheme.onTertiaryContainer
            else  -> colorScheme.primaryContainer to colorScheme.onPrimaryContainer
        }
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(22.sdp)
            .clip(RoundedCornerShape(5.sdp))
            .background(bg)
    ) {
        Text(
            text = upper,
            fontSize = 7.ssp,
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
                    .size(16.sdp)
                    .clip(CircleShape)
                    .background(colorScheme.tertiaryContainer)
            ) {
                Text("✓", fontSize = 9.ssp, fontWeight = FontWeight.W600, color = colorScheme.onTertiaryContainer)
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
            // Hoist Stroke allocations out of the per-frame draw lambda.
            // density is stable for the lifetime of the composable on a fixed-size screen.
            val density = LocalDensity.current
            val spinnerStrokeRing = remember(density) { Stroke(width = with(density) { 1.5.dp.toPx() }) }
            val spinnerStrokeArc  = remember(density) { Stroke(width = with(density) { 1.5.dp.toPx() }, cap = StrokeCap.Round) }
            Box(
                modifier = Modifier
                    .size(13.sdp)
                    .graphicsLayer { rotationZ = rotation }
                    .drawBehind {
                        val s = 1.5.dp.toPx()
                        val r = (size.minDimension - s) / 2f
                        drawCircle(color = colorScheme.primaryContainer, radius = r, style = spinnerStrokeRing)
                        drawArc(
                            color = colorScheme.primary,
                            startAngle = 0f,
                            sweepAngle = 270f,
                            useCenter = false,
                            style = spinnerStrokeArc
                        )
                    }
            )
        }

        FileTransferStatus.Pending -> {
            Box(
                modifier = Modifier
                    .size(13.sdp)
                    .clip(CircleShape)
                    .background(colorScheme.outlineVariant)
            )
        }

        FileTransferStatus.Failed -> {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(13.sdp)
                    .clip(CircleShape)
                    .background(colorScheme.errorContainer)
            ) {
                Text("✕", fontSize = 6.ssp, color = colorScheme.onErrorContainer)
            }
        }
    }
}
