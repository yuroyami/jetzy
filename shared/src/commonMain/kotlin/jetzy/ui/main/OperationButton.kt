package jetzy.ui.main

import androidx.compose.foundation.layout.Arrangement.Absolute.Center
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Downloading
import androidx.compose.material.icons.filled.PresentToAll
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedToggleButton
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import jetzy.p2p.P2pOperation
import jetzy.shared.generated.resources.Res
import jetzy.shared.generated.resources.genos
import jetzy.theme.sdp
import jetzy.theme.ssp
import jetzy.ui.LocalViewmodel
import org.jetbrains.compose.resources.Font

@Composable
fun OperationButton(
    modifier: Modifier = Modifier,
    operation: P2pOperation
) {
    val haptic = LocalHapticFeedback.current
    val viewmodel = LocalViewmodel.current
    val currentOperation by viewmodel.currentOperation.collectAsState()
    val isSelected by derivedStateOf { currentOperation == operation }

    OutlinedToggleButton(
        modifier = modifier.height(64.sdp).padding(vertical = 8.sdp, horizontal = 3.sdp),
        checked = isSelected,
        contentPadding = PaddingValues.Zero,
        shapes = ToggleButtonDefaults.shapes(
            shape = RoundedCornerShape(15), pressedShape = RoundedCornerShape(30), checkedShape = RoundedCornerShape(40)
        ),
        onCheckedChange = {
            viewmodel.currentOperation.value = operation
            haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
        }
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Center, verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = when (operation) {
                    P2pOperation.SEND -> Icons.Filled.PresentToAll
                    P2pOperation.RECEIVE -> Icons.Filled.Downloading
                },
                contentDescription = null,
                modifier = Modifier.padding(horizontal = 3.sdp),
                //tint = if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant
            )


            Text(
                text = when (operation) {
                    P2pOperation.SEND -> "Send"
                    P2pOperation.RECEIVE -> "Receive"
                },
                fontSize = 13.ssp,
                fontFamily = FontFamily(Font(Res.font.genos)),
                //color = if (isSelected) Color.White else MaterialTheme.colorScheme.outlineVariant,
                fontWeight = FontWeight.W800,
                maxLines = 1
            )
        }
    }
}