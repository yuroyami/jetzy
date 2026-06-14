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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
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
import jetzy.shared.generated.resources.connect_directly
import jetzy.shared.generated.resources.connect_to_peer
import jetzy.shared.generated.resources.device_name_hint
import jetzy.shared.generated.resources.edit_name_title
import jetzy.shared.generated.resources.name_saved_hint
import jetzy.shared.generated.resources.save
import jetzy.shared.generated.resources.ensure_jetzy_open
import jetzy.shared.generated.resources.find_nearby_devices
import jetzy.shared.generated.resources.nearby
import jetzy.shared.generated.resources.nearby_devices
import jetzy.shared.generated.resources.no_devices_found
import jetzy.shared.generated.resources.peer_discovery
import jetzy.shared.generated.resources.scanning
import jetzy.shared.generated.resources.select_device_hint
import jetzy.shared.generated.resources.select_device_to_connect
import jetzy.shared.generated.resources.try_different_transport
import jetzy.shared.generated.resources.visible_as
import jetzy.ui.LocalViewmodel
import jetzy.utils.JetzyPrefs
import jetzy.utils.deviceName
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
    return remember(index, colorScheme) {
        when (index % 3) {
            0    -> PeerColors(colorScheme.primaryContainer, colorScheme.onPrimaryContainer, colorScheme.primary)
            1    -> PeerColors(colorScheme.tertiaryContainer, colorScheme.onTertiaryContainer, colorScheme.tertiary)
            else -> PeerColors(colorScheme.errorContainer, colorScheme.onErrorContainer, colorScheme.error)
        }
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
            manager.startDiscoveryAndAdvertising(deviceName)
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
                // Two same-model phones advertise the same name (B17) — let each user answer
                // "which one is you?" for the person scanning for them, and tap to change it
                // (persisted; advertised from the next discovery session).
                var displayName by remember { mutableStateOf(deviceName) }
                var showNameEditor by remember { mutableStateOf(false) }
                Text(
                    text = stringResource(Res.string.visible_as, displayName),
                    fontSize = 9.ssp,
                    color = colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.sdp))
                        .clickable(role = Role.Button) { showNameEditor = true }
                        .padding(horizontal = 6.sdp, vertical = 2.sdp),
                )
                if (showNameEditor) {
                    DeviceNameDialog(
                        current = displayName,
                        onSave = { newName ->
                            JetzyPrefs.deviceNameOverride = newName
                            displayName = deviceName
                            showNameEditor = false
                            viewmodel.snackyRes(Res.string.name_saved_hint)
                        },
                        onDismiss = { showNameEditor = false },
                    )
                }
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
                        text = stringResource(Res.string.try_different_transport),
                        fontSize = 10.ssp,
                        fontWeight = FontWeight.W500,
                        color = colorScheme.onSurface,
                    )
                }
            }

            // ── Connect-directly affordance (cross-network, e.g. Android↔iPhone with no shared
            //    Wi-Fi) ──────────────────────────────────────────────────────────
            // The deterministic universal path: jump straight to a QR-paired hotspot instead of
            // walking the ladder onto transports the peer may not support (an iPhone can't do
            // Wi-Fi Direct). Shown once the radar's been empty long enough.
            if (emptyLongEnough) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.sdp))
                        .background(colorScheme.primaryContainer)
                        .clickable(role = Role.Button) {
                            viewmodel.connectDirectly()
                        }
                        .padding(vertical = 8.sdp, horizontal = 12.sdp)
                ) {
                    Text(
                        text = stringResource(Res.string.connect_directly),
                        fontSize = 10.ssp,
                        fontWeight = FontWeight.W600,
                        color = colorScheme.onPrimaryContainer,
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

    // Pre-compute static Color.copy() values once per primary color change — avoids
    // repeated copy() calls inside the drawBehind lambda that runs at ~60 fps.
    val ringColor     = remember(colorScheme.primary) { colorScheme.primary.copy(alpha = .15f) }
    val sweepColor    = remember(colorScheme.primary) { colorScheme.primary.copy(alpha = .2f) }
    val sweepLineColor = remember(colorScheme.primary) { colorScheme.primary.copy(alpha = .5f) }

    // Hoisted constant ring-fraction list — never reallocate inside drawBehind.
    val ringFractions = remember { listOf(0.35f, 0.65f, 1f) }

    Box(
        modifier = modifier.drawBehind {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val maxR = size.minDimension / 2f

            // rings
            ringFractions.forEach { f ->
                drawCircle(
                    color = ringColor,
                    radius = maxR * f,
                    center = Offset(cx, cy),
                    style = Stroke(0.5.dp.toPx())
                )
            }

            // sweep
            drawArc(
                color = sweepColor,
                startAngle = sweepAngle - 60f,
                sweepAngle = 60f,
                useCenter = true,
                topLeft = Offset(cx - maxR, cy - maxR),
                size = androidx.compose.ui.geometry.Size(maxR * 2, maxR * 2),
            )

            // sweep leading edge line
            val sweepRad = sweepAngle.toDouble() * (PI / 180.0)
            drawLine(
                color = sweepLineColor,
                start = Offset(cx, cy),
                end = Offset(
                    cx + (maxR * cos(sweepRad)).toFloat(),
                    cy + (maxR * sin(sweepRad)).toFloat(),
                ),
                strokeWidth = 1.dp.toPx(),
                cap = StrokeCap.Round,
            )

            // center dot — alpha is animated so copy() must stay here
            drawCircle(
                color = colorScheme.primary.copy(alpha = centerPulse),
                radius = 5.dp.toPx(),
                center = Offset(cx, cy),
            )
        },
        contentAlignment = Alignment.Center
    ) {
        // peer dots — each keyed by stable peer.id so animation state survives list mutations
        Box(modifier = Modifier.fillMaxSize()) {
            peers.forEachIndexed { index, peer ->
                key(peer.id) {
                    val angle = peerAngles.getOrElse(index) { 0f }
                    val radius = peerRadii.getOrElse(index) { 0.5f }
                    val colors = peerColors(index)

                    val infiniteRipple = rememberInfiniteTransition(label = "ripple_${peer.id}")
                    val rippleScale by infiniteRipple.animateFloat(
                        initialValue = 1f,
                        targetValue = 2.4f,
                        animationSpec = infiniteRepeatable(
                            tween(2000, delayMillis = index * 600, easing = LinearEasing),
                            RepeatMode.Restart
                        ),
                        label = "scale_${peer.id}"
                    )
                    val rippleAlpha by infiniteRipple.animateFloat(
                        initialValue = 0.4f,
                        targetValue = 0f,
                        animationSpec = infiniteRepeatable(
                            tween(2000, delayMillis = index * 600),
                            RepeatMode.Restart
                        ),
                        label = "alpha_${peer.id}"
                    )

                    val angleRad = angle.toDouble() * (PI / 180.0)

                    // Dot-sized box aligned where the dot lives, drawing + hit-testing in one.
                    // Each dot used to be a fillMaxSize clickable: every peer's tap target was
                    // the WHOLE radar, so with 2+ peers the top-most box swallowed every tap
                    // and selected the wrong device — and screen readers saw N overlapping
                    // unlabeled fullscreen buttons.
                    val biasX = (radius * cos(angleRad)).toFloat()
                    val biasY = (radius * sin(angleRad)).toFloat()
                    Box(
                        modifier = Modifier
                            .align(BiasAlignment(biasX, biasY))
                            .size(32.dp)
                            .drawBehind {
                                val c = Offset(size.width / 2f, size.height / 2f)
                                val dotR = 5.dp.toPx()
                                // ripple — alpha is animated so copy() must stay here
                                drawCircle(
                                    color = colors.accent.copy(alpha = rippleAlpha),
                                    radius = dotR * rippleScale,
                                    center = c,
                                )
                                drawCircle(color = colors.accent, radius = dotR, center = c)
                            }
                            .clip(CircleShape)
                            .semantics { contentDescription = peer.name }
                            .clickable(role = Role.Button) { onPeerSelected(peer) }
                    )
                }
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
            // Selection was conveyed by color/border only — invisible to screen readers.
            .semantics { selected = isSelected }
            .clickable(role = Role.Button, onClick = onClick)
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
            val initials = remember(peer.name) { peer.name.take(2).uppercase() }
            Text(
                text = initials,
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
            // B30: show the transport this session actually runs over, not a hardcoded
            // "Wi-Fi Direct" (which lied for mDNS / MPC / Bluetooth / Wi-Fi Aware peers).
            // Falls back to a neutral "Nearby" before the manager has set a technology.
            Text(
                text = LocalViewmodel.current.p2pManager?.technology?.displayName ?: stringResource(Res.string.nearby),
                fontSize = 9.ssp,
                color = colorScheme.onSurfaceVariant,
            )
        }

        // B31: no signal bars. These transports carry no RSSI — every peer shipped a hardcoded
        // signalStrength=3, so the bars always read 3/4 and were pure fiction. The transport
        // label above already says "this is a reachable nearby peer".
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

// ── Device-name editor ─────────────────────────────────────────────────────────

@Composable
private fun DeviceNameDialog(current: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var value by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.edit_name_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.sdp)) {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = stringResource(Res.string.device_name_hint),
                    fontSize = 9.ssp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(value) }) { Text(stringResource(Res.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) }
        },
    )
}
