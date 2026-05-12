package jetzy.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import jetzy.permissions.PermissionRequirement
import jetzy.shared.generated.resources.Res
import jetzy.shared.generated.resources.cancel
import jetzy.shared.generated.resources.permission_gate_allow
import jetzy.shared.generated.resources.permission_gate_continue
import jetzy.shared.generated.resources.permission_gate_enabled
import jetzy.shared.generated.resources.permission_gate_grant
import jetzy.shared.generated.resources.permission_gate_granted
import jetzy.shared.generated.resources.permission_gate_open_settings
import jetzy.shared.generated.resources.permission_gate_subtitle
import jetzy.shared.generated.resources.permission_gate_title
import jetzy.theme.sdp
import jetzy.theme.ssp
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

/**
 * Dialog that walks the user through every [PermissionRequirement] declared by the
 * platform manager before transfer can begin. Re-checks each requirement every 500ms
 * while open so flipping a system toggle and returning to Jetzy reflects immediately,
 * and auto-confirms once every requirement reports granted so the user never has to
 * find the Continue button manually.
 */
@Composable
fun PermissionGateDialog(
    requirements: List<PermissionRequirement>,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (requirements.isEmpty()) return

    // Stable key based on requirement ids — `requirements` itself is a fresh list
    // on every parent recomposition (the manager rebuilds it from a `get()`), so
    // keying on the list reference would restart the producer constantly.
    val structuralKey = requirements.joinToString("|") { it.id }
    val grantedFlags by produceState(
        initialValue = requirements.map { it.isGrantedNow() },
        key1 = structuralKey,
    ) {
        // Snapshot once immediately in case the user granted everything before opening,
        // then poll while the dialog stays open.
        value = requirements.map { it.isGrantedNow() }
        while (true) {
            delay(400L)
            value = requirements.map { it.isGrantedNow() }
        }
    }
    val allGranted = grantedFlags.size == requirements.size && grantedFlags.all { it }

    // Auto-progress: as soon as everything is green, give the user a brief beat to
    // register the final check landing, then close the dialog without a tap.
    LaunchedEffect(allGranted) {
        if (allGranted) {
            delay(450L)
            onConfirm()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
        )
    ) {
        Surface(
            shape = RoundedCornerShape(18.sdp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            shadowElevation = 12.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.sdp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.sdp, vertical = 16.sdp)) {

                Text(
                    text = stringResource(Res.string.permission_gate_title),
                    fontSize = 13.ssp,
                    fontWeight = FontWeight.W600,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )

                Text(
                    text = stringResource(Res.string.permission_gate_subtitle),
                    fontSize = 9.ssp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.sdp, bottom = 12.sdp),
                    textAlign = TextAlign.Center,
                )

                Column(
                    verticalArrangement = Arrangement.spacedBy(6.sdp),
                    modifier = Modifier
                        .heightIn(max = 440.sdp)
                        .verticalScroll(rememberScrollState())
                ) {
                    requirements.forEachIndexed { index, req ->
                        RequirementRow(req, isGranted = grantedFlags.getOrElse(index) { false })
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.sdp),
                    horizontalArrangement = Arrangement.spacedBy(8.sdp),
                ) {
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(Res.string.cancel), fontSize = 10.ssp)
                    }
                    FilledTonalButton(
                        onClick = onConfirm,
                        enabled = allGranted,
                        modifier = Modifier.weight(1.4f),
                        shape = RoundedCornerShape(9.sdp),
                    ) {
                        Text(
                            stringResource(Res.string.permission_gate_continue),
                            fontSize = 10.ssp,
                            fontWeight = FontWeight.W600,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RequirementRow(req: PermissionRequirement, isGranted: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val grantedColor = scheme.tertiary
    val pendingColor = scheme.primary

    // Top-aligned so the indicator dot and trailing action anchor to the title's
    // baseline rather than floating in the vertical middle of a wrapped description.
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.sdp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.sdp))
            .background(scheme.surfaceContainer)
            .border(
                width = 0.5.dp,
                color = if (isGranted) grantedColor.copy(alpha = 0.4f) else scheme.outlineVariant,
                shape = RoundedCornerShape(10.sdp)
            )
            .padding(horizontal = 10.sdp, vertical = 9.sdp)
    ) {
        Box(
            modifier = Modifier
                .padding(top = 1.sdp)
                .size(18.sdp)
                .clip(CircleShape)
                .background(if (isGranted) grantedColor else pendingColor.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) {
            if (isGranted) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(12.sdp),
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(7.sdp)
                        .clip(CircleShape)
                        .background(pendingColor)
                )
            }
        }

        Column(
            verticalArrangement = Arrangement.spacedBy(2.sdp),
            modifier = Modifier.weight(1f),
        ) {
            Text(
                text = stringResource(req.titleRes),
                fontSize = 10.ssp,
                fontWeight = FontWeight.W500,
                color = scheme.onSurface,
                lineHeight = 13.ssp,
            )
            Text(
                text = stringResource(req.descriptionRes),
                fontSize = 8.ssp,
                color = scheme.onSurfaceVariant,
                lineHeight = 11.ssp,
            )
        }

        Box(modifier = Modifier.width(6.sdp))

        if (isGranted) {
            Text(
                text = when (req.kind) {
                    PermissionRequirement.Kind.RUNTIME_PERMISSION -> stringResource(Res.string.permission_gate_granted)
                    else -> stringResource(Res.string.permission_gate_enabled)
                },
                fontSize = 8.ssp,
                color = grantedColor,
                fontWeight = FontWeight.W500,
                modifier = Modifier.padding(top = 2.sdp),
            )
        } else {
            FilledTonalButton(
                onClick = req.request,
                shape = RoundedCornerShape(7.sdp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.sdp, vertical = 3.sdp),
            ) {
                Text(
                    text = when (req.kind) {
                        PermissionRequirement.Kind.RUNTIME_PERMISSION -> stringResource(Res.string.permission_gate_grant)
                        PermissionRequirement.Kind.SYSTEM_TOGGLE -> stringResource(Res.string.permission_gate_open_settings)
                        PermissionRequirement.Kind.BACKGROUND_OPTIN -> stringResource(Res.string.permission_gate_allow)
                    },
                    fontSize = 8.ssp,
                    fontWeight = FontWeight.W500,
                )
            }
        }
    }
}
