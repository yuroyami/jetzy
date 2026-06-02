package jetzy.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.font.FontFamily
import jetzy.shared.generated.resources.Res
import jetzy.shared.generated.resources.intertight
import jetzy.shared.generated.resources.notosans
import org.jetbrains.compose.resources.Font

val bodyFontFamily: FontFamily
    @Composable get() = FontFamily(Font(Res.font.intertight))


val displayFontFamily: FontFamily
    @Composable get() = FontFamily(Font(Res.font.notosans))

// Default Material 3 typography values
val baseline = Typography()

val AppTypography: Typography
    @Composable get() {
        val body = bodyFontFamily
        val display = displayFontFamily
        return remember(body, display) {
            Typography(
                displayLarge = baseline.displayLarge.copy(fontFamily = display),
                displayMedium = baseline.displayMedium.copy(fontFamily = display),
                displaySmall = baseline.displaySmall.copy(fontFamily = display),
                headlineLarge = baseline.headlineLarge.copy(fontFamily = display),
                headlineMedium = baseline.headlineMedium.copy(fontFamily = display),
                headlineSmall = baseline.headlineSmall.copy(fontFamily = display),
                titleLarge = baseline.titleLarge.copy(fontFamily = display),
                titleMedium = baseline.titleMedium.copy(fontFamily = display),
                titleSmall = baseline.titleSmall.copy(fontFamily = display),
                bodyLarge = baseline.bodyLarge.copy(fontFamily = body),
                bodyMedium = baseline.bodyMedium.copy(fontFamily = body),
                bodySmall = baseline.bodySmall.copy(fontFamily = body),
                labelLarge = baseline.labelLarge.copy(fontFamily = body),
                labelMedium = baseline.labelMedium.copy(fontFamily = body),
                labelSmall = baseline.labelSmall.copy(fontFamily = body),
            )
        }
    }

