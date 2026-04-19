package jetzy.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment.Companion.CenterHorizontally
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jetzy.shared.generated.resources.Res
import jetzy.shared.generated.resources.genos
import jetzy.theme.sdp
import jetzy.utils.ComposeUtils.scheme
import org.jetbrains.compose.resources.Font

@Composable
fun VerticalCardButton(
    modifier: Modifier = Modifier,
    text: String,
    icon: ImageVector,
    selectedIconTint: Color,
    isSelected: Boolean,
    upperSupportingContent: @Composable (ColumnScope.() -> Unit)? = null,
    onClick: () -> Unit
) {
    val haptic = LocalHapticFeedback.current

    OutlinedCard(
        modifier = modifier.height(78.sdp).padding(vertical = 3.sdp, horizontal = 2.sdp),
        border = CardDefaults.outlinedCardBorder(enabled = isSelected),
        shape = RoundedCornerShape(if (isSelected) 10.sdp else 5.sdp),
        onClick = {
            onClick()
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    ) {
        BadgedBox(
            modifier = Modifier.fillMaxSize(),
            badge = {
                if (isSelected) {
                    Badge {}
                }
            }
        ) {
            Column (
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = CenterHorizontally
            ) {
                upperSupportingContent?.invoke(this@Column)

                Icon(
                    modifier = Modifier.fillMaxWidth(0.9f).weight(1f).padding(4.sdp),
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (isSelected) selectedIconTint else scheme.outlineVariant
                )

                Text(
                    modifier = Modifier.fillMaxWidth(0.85f),
                    text = text,
                    autoSize = TextAutoSize.StepBased(minFontSize = 1.sp, maxFontSize = 25.sp),
                    fontFamily = FontFamily(Font(Res.font.genos)),
                    color = if (isSelected) scheme.onSurface else scheme.outlineVariant,
                    fontWeight = FontWeight.W800,
                    maxLines = 1,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}