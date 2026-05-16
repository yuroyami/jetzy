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
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewModelScope
import jetzy.theme.sdp
import jetzy.theme.ssp
import jetzy.managers.PeerDiscoveryP2PM
import jetzy.p2p.P2pPeer
import jetzy.shared.generated.resources.Res
import jetzy.shared.generated.resources.cancel
import jetzy.shared.generated.resources.connect_to_peer
import jetzy.shared.generated.resources.ensure_jetzy_open
import jetzy.shared.generated.resources.find_nearby_devices
import jetzy.shared.generated.resources.nearby_devices
import jetzy.shared.generated.resources.no_devices_found
import jetzy.shared.generated.resources.peer_discovery
import jetzy.shared.generated.resources.scanning
import jetzy.shared.generated.resources.select_device_hint
import jetzy.shared.generated.resources.select_device_to_connect
import jetzy.shared.generated.resources.wifi_direct
import jetzy.ui.LocalViewmodel
import jetzy.utils.getDeviceName
import org.jetbrains.compose.resources.stringResource
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// ── Peer color assignment ──────────────────────────────────────────────────────
private data class PeerColors(val bg: Color, val fg: Color, val accent: Color)

@Composable
private fun peerColors(index: Int): PeerColors {
    val colorScheme = MaterialTheme.colorScheme
    return when (index % 3) {
        0    -> PeerColors(colorScheme.primaryContainer, colorScheme.onPrimaryContainer, colorScheme.primary)
        1    -> PeerColors(colorScheme.tertiaryContainer, colorScheme.onTertiaryContainer, colorScheme.tertiary)
        else -> PeerColors(colorScheme.errorContainer, colorScheme.onErrorContainer, colorScheme.error)
    }
}

// ── Root screen ────────────────────────────────────────────────────────────────
@Composable
fun PeerDiscoveryScreenUI() {
    val viewmodel = LocalViewmodel.current
    val manager = viewmodel.p2pManager as? PeerDiscoveryP2PM ?: return
    val colorScheme = MaterialTheme.colorScheme

    val availablePeers by manager.availablePeers.collectAsState()
    val isDiscovering  by manager.isDiscovering.collectAsState()
    val fallbackCount  by viewmodel.fallbackCount.collectAsState()

    // The fallback affordance only makes sense after the user has given the
    // current transport a real chance to find something — 6s with no peers.
    var emptyLongEnough by remember { mutableStateOf(false) }
    LaunchedEffect(availablePeers.isEmpty(), isDiscovering) {
        emptyLongEnough = false
        if (availablePeers.isEmpty() && isDiscovering) {
            kotlinx.coroutines.delay(6000)
            emptyLongEnough = availablePeers.isEmpty()
        }
    }

    var selectedPeer by remember { mutableStateOf<P2pPeer?>(null) }

    LaunchedEffect(null) {
        viewmodel.viewModelScope.launch {
            manager.startDiscoveryAndAdvertising(getDeviceName())
        }
    }

    // Cleanup when leaving the screen
    DisposableEffect(Unit) {
        onDispose {
            viewmodel.viewModelScope.launch {
                manager.stopDiscoveryAndAdvertising()
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colorScheme.surface),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.sdp, vertical = 20.sdp),
            verticalArrangement = Arrangement.spacedBy(10.sdp)
        ) {

            // ── Header ──────────────────────────────────────────────────────
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.sdp)
            ) {
                Text(
                    text = stringResource(Res.string.peer_discovery),
                    fontSize = 9.ssp,
                    fontWeight = FontWeight.W500,
                    color = colorScheme.onSurfaceVariant,
                    letterSpacing = 0.08.sp,
                )
                Text(
                    text = stringResource(Res.string.find_nearby_devices),
                    fontSize = 14.ssp,
                    fontWeight = FontWeight.W500,
                    color = colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = stringResource(Res.string.select_device_hint),
                    fontSize = 10.ssp,
                    color = colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }

            // ── Radar ────────────────────────────────────────────────────────
            RadarView(
                peers = availablePeers,
                selectedPeer = selectedPeer,
                onPeerSelected = { selectedPeer = it },
                modifier = Modifier.size(140.sdp)
            )

            // ── Peer list card ───────────────────────────────────────────────
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.sdp))
                    .background(colorScheme.surfaceContainer)
                    .border(0.5.dp, colorScheme.outlineVariant, RoundedCornerShape(14.sdp))
                    .padding(14.sdp),
                verticalArrangement = Arrangement.spacedBy(8.sdp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(Res.string.nearby_devices),
                        fontSize = 10.ssp,
                        fontWeight = FontWeight.W500,
                        color = colorScheme.onSurface,
                    )
                    if (isDiscovering) SearchingIndicator()
                }

                if (availablePeers.isEmpty()) {
                    EmptyPeersState()
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(6.sdp)) {
                        availablePeers.forEachIndexed { index, peer ->
                            PeerRow(
                                peer = peer,
                                colors = peerColors(index),
                                isSelected = peer == selectedPeer,
                                animDelay = index * 60,
                                onClick = { selectedPeer = if (selectedPeer == peer) null else peer }
                            )
                        }
                    }
                }
            }

            // ── Connect button ───────────────────────────────────────────────
            ConnectButton(
                peer = selectedPeer,
                onClick = {
                    selectedPeer?.let { peer ->
                        manager.isHandshaking.value = true

                        viewmodel.viewModelScope.launch {
                            manager.connectToPeer(peer)
                        }
                    }
                }
            )

            // ── Try-different-transport affordance ───────────────────────────
            // Only shown when the user has given the current transport a chance
            // (6s with no peers found) AND the platform callback offers a ladder.
            if (emptyLongEnough && fallbackCount > 0) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.sdp))
                        .background(colorScheme.surfaceContainerHigh)
                        .border(0.5.dp, colorScheme.outlineVariant, RoundedCornerShape(10.sdp))
                        .clickable {
                            viewmodel.switchToNextFallbackTransport()
                        }
                        .padding(vertical = 8.sdp, horizontal = 12.sdp)
                ) {
                    Text(
                        text = "Try a different transport",
                        fontSize = 10.ssp,
                        fontWeight = FontWeight.W500,
                        color = colorScheme.onSurface,
                    )
                }
            }

            // ── Cancel ───────────────────────────────────────────────────────
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.sdp))
                    .border(0.5.dp, colorScheme.outlineVariant, RoundedCornerShape(10.sdp))
                    .clickable {
                        viewmodel.cancelDiscovery()
                    }
                    .padding(vertical = 8.sdp)
            ) {
                Text(stringResource(Res.string.cancel), fontSize = 10.ssp, color = colorScheme.onSurfaceVariant)
            }
        }
    }


    val isHandshaking by manager.isHandshaking.collectAsState()
    if (isHandshaking) {
        val loadingOverlayColor = Color.Black.copy(alpha = 0.8f)
        Box(
            modifier = Modifier.fillMaxSize().background(loadingOverlayColor).clickable(onClick={}),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(
                color = contentColorFor(loadingOverlayColor)
            )
        }
    }
}

// ── Radar ──────────────────────────────────────────────────────────────────────

@Composable
private fun RadarView(
    peers: List<P2pPeer>,
    selectedPeer: P2pPeer?,
    onPeerSelected: (P2pPeer) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    val infiniteTransition = rememberInfiniteTransition(label = "radar")
    val sweepAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(2500, easing = LinearEasing)),
        label = "sweep"
    )
    val centerPulse by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(1500, easing = FastOutSlowInEasing),
            RepeatMode.Reverse
        ),
        label = "center"
    )

    // stable angular positions for up to 5 peers
    val peerAngles = remember(peers.size) {
        List(peers.size) { i -> (i * 137.5f) % 360f } // golden angle distribution
    }
    val peerRadii = remember(peers.size) {
        List(peers.size) { i -> 0.35f + (i % 3) * 0.18f }
    }

    Box(
        modifier = modifier.drawBehind {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val maxR = size.minDimension / 2f

            // rings
            listOf(0.35f, 0.65f, 1f).forEach { f ->
                drawCircle(
                    color = colorScheme.primary.copy(alpha = .15f),
                    radius = maxR * f,
                    center = Offset(cx, cy),
                    style = Stroke(0.5.dp.toPx())
                )
            }

            // sweep
            drawArc(
                color = colorScheme.primary.copy(alpha = .2f),
                startAngle = sweepAngle - 60f,
                sweepAngle = 60f,
                useCenter = true,
                topLeft = Offset(cx - maxR, cy - maxR),
                size = androidx.compose.ui.geometry.Size(maxR * 2, maxR * 2),
            )

            // sweep leading edge line
            val sweepRad = sweepAngle.toDouble() * (PI / 180.0)
            drawLine(
                color = colorScheme.primary.copy(alpha = .5f),
                start = Offset(cx, cy),
                end = Offset(
                    cx + (maxR * cos(sweepRad)).toFloat(),
                    cy + (maxR * sin(sweepRad)).toFloat(),
                ),
                strokeWidth = 1.dp.toPx(),
                cap = StrokeCap.Round,
            )

            // center dot
            drawCircle(
                color = colorScheme.primary.copy(alpha = centerPulse),
                radius = 5.dp.toPx(),
                center = Offset(cx, cy),
            )
        },
        contentAlignment = Alignment.Center
    ) {
        // peer dots
        Box(modifier = Modifier.fillMaxSize()) {
            peers.forEachIndexed { index, peer ->
                val angle = peerAngles.getOrElse(index) { 0f }
                val radius = peerRadii.getOrElse(index) { 0.5f }
                val colors = peerColors(index)

                val infiniteRipple = rememberInfiniteTransition(label = "ripple_$index")
                val rippleScale by infiniteRipple.animateFloat(
                    initialValue = 1f,
                    targetValue = 2.4f,
                    animationSpec = infiniteRepeatable(
                        tween(2000, delayMillis = index * 600, easing = LinearEasing),
                        RepeatMode.Restart
                    ),
                    label = "scale_$index"
                )
                val rippleAlpha by infiniteRipple.animateFloat(
                    initialValue = 0.4f,
                    targetValue = 0f,
                    animationSpec = infiniteRepeatable(
                        tween(2000, delayMillis = index * 600),
                        RepeatMode.Restart
                    ),
                    label = "alpha_$index"
                )

                val angleRad = angle.toDouble() * (PI / 180.0)
                val dotSize = 10.dp

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .drawBehind {
                            val cx = size.width / 2f
                            val cy = size.height / 2f
                            val maxR = size.minDimension / 2f
                            val dx = (maxR * radius * cos(angleRad)).toFloat()
                            val dy = (maxR * radius * sin(angleRad)).toFloat()
                            val dotR = dotSize.toPx() / 2f

                            // ripple
                            drawCircle(
                                color = colors.accent.copy(alpha = rippleAlpha),
                                radius = dotR * rippleScale,
                                center = Offset(cx + dx, cy + dy),
                            )
                            // dot
                            drawCircle(
                                color = if (peer == peers.firstOrNull { it.id == peer.id }) colors.accent else colors.accent.copy(.7f),
                                radius = dotR,
                                center = Offset(cx + dx, cy + dy),
                            )
                        }
                        .clickable { onPeerSelected(peer) }
                )
            }
        }
    }
}

// ── Peer row ───────────────────────────────────────────────────────────────────

@Composable
private fun PeerRow(
    peer: P2pPeer,
    colors: PeerColors,
    isSelected: Boolean,
    animDelay: Int,
    onClick: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    val animAlpha by produceState(0f, peer) {
        kotlinx.coroutines.delay(animDelay.toLong())
        value = 1f
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.sdp),
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { alpha = animAlpha }
            .clip(RoundedCornerShape(10.sdp))
            .background(if (isSelected) colors.bg else colorScheme.surfaceContainerHigh)
            .border(
                width = if (isSelected) 1.5.dp else 0.5.dp,
                color = if (isSelected) colors.accent else colorScheme.outlineVariant,
                shape = RoundedCornerShape(10.sdp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.sdp, vertical = 8.sdp)
    ) {
        // avatar
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(28.sdp)
                .clip(RoundedCornerShape(8.sdp))
                .background(colors.bg)
        ) {
            Text(
                text = peer.name.take(2).uppercase(),
                fontSize = 9.ssp,
                fontWeight = FontWeight.W500,
                color = colors.fg,
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = peer.name,
                fontSize = 10.ssp,
                fontWeight = FontWeight.W500,
                color = if (isSelected) colors.fg else colorScheme.onSurface,
            )
            Text(
                text = stringResource(Res.string.wifi_direct),
                fontSize = 9.ssp,
                color = colorScheme.onSurfaceVariant,
            )
        }

        SignalBars(strength = peer.signalStrength, color = colors.accent)
    }
}

// ── Signal bars ────────────────────────────────────────────────────────────────

@Composable
private fun SignalBars(strength: Int, color: Color) {
    val colorScheme = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(2.sdp)
    ) {
        listOf(4.sdp, 7.sdp, 10.sdp, 13.sdp).forEachIndexed { index, h ->
            Box(
                modifier = Modifier
                    .width(3.sdp)
                    .height(h)
                    .clip(RoundedCornerShape(2.sdp))
                    .background(if (index < strength) color else colorScheme.outlineVariant)
            )
        }
    }
}

// ── Searching indicator ────────────────────────────────────────────────────────

@Composable
private fun SearchingIndicator() {
    val colorScheme = MaterialTheme.colorScheme
    val infiniteTransition = rememberInfiniteTransition(label = "search")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(700, easing = LinearEasing)),
        label = "spin"
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.sdp)
    ) {
        Box(modifier = Modifier.size(10.sdp).drawBehind {
            val stroke = 1.5.dp.toPx()
            val r = (size.minDimension - stroke) / 2f
            drawCircle(colorScheme.outlineVariant, radius = r, style = Stroke(stroke))
            drawArc(colorScheme.primary, startAngle = rotation, sweepAngle = 270f,
                useCenter = false, style = Stroke(stroke, cap = StrokeCap.Round))
        })
        Text(stringResource(Res.string.scanning), fontSize = 9.ssp, color = colorScheme.onSurfaceVariant)
    }
}

// ── Empty state ────────────────────────────────────────────────────────────────

@Composable
private fun EmptyPeersState() {
    val colorScheme = MaterialTheme.colorScheme
    val infiniteTransition = rememberInfiniteTransition(label = "empty")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.4f, targetValue = 0.9f,
        animationSpec = infiniteRepeatable(
            tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse
        ),
        label = "empty_alpha"
    )
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.sdp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.sdp)
    ) {
        Box(
            modifier = Modifier
                .size(28.sdp)
                .clip(CircleShape)
                .background(colorScheme.primaryContainer.copy(alpha = alpha))
                .drawBehind {
                    val stroke = 1.5.dp.toPx()
                    val r = (size.minDimension - stroke) / 2f
                    drawCircle(colorScheme.primary.copy(alpha = alpha), radius = r, style = Stroke(stroke))
                }
        )
        Text(stringResource(Res.string.no_devices_found), fontSize = 10.ssp, color = colorScheme.onSurfaceVariant)
        Text(stringResource(Res.string.ensure_jetzy_open), fontSize = 9.ssp, color = colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

// ── Connect button ─────────────────────────────────────────────────────────────

@Composable
private fun ConnectButton(peer: P2pPeer?, onClick: () -> Unit) {
    val colorScheme = MaterialTheme.colorScheme
    val enabled = peer != null
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.sdp))
            .background(if (enabled) colorScheme.primary else colorScheme.primary.copy(alpha = 0.35f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 10.sdp)
    ) {
        Text(
            text = if (peer != null) stringResource(Res.string.connect_to_peer, peer.name) else stringResource(Res.string.select_device_to_connect),
            fontSize = 11.ssp,
            fontWeight = FontWeight.W500,
            color = colorScheme.onPrimary,
        )
    }
}
