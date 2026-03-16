package jetzy.utils

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import jetzy.shared.generated.resources.Res
import jetzy.shared.generated.resources.nauman_heavy
import jetzy.theme.jetzyYellow
import org.jetbrains.compose.resources.Font
import org.jetbrains.compose.resources.FontResource

/** Contains a bunch of composable functions that are frequently reused */
object ComposeUtils {

    /** Convenience variable to shorten calling for compose scheme */
    val scheme: ColorScheme
        @Composable
        @ReadOnlyComposable
        get() = MaterialTheme.colorScheme


    val FontResource.font: FontFamily
        @Composable get() = FontFamily(Font(this))

    @Composable
    fun JetzyText(
        modifier: Modifier = Modifier.Companion,
        text: String,
        size: TextUnit,
        strokeThickness: Float = 4f,
        font: FontResource = Res.font.nauman_heavy
    ) {
        Box {
            val font = FontFamily(Font(font))

            // Black stroke (background text)
            Text(
                text,
                style = MaterialTheme.typography.titleMedium.copy(
                    color = Color.Black,
                    drawStyle = Stroke(width = strokeThickness)
                ),
                modifier = modifier,
                textAlign = TextAlign.Center,
                fontSize = size,
                fontFamily = font
            )

            // Yellow fill (foreground text)
            Text(
                text,
                style = MaterialTheme.typography.titleMedium.copy(
                    color = jetzyYellow
                ),
                modifier = modifier,
                textAlign = TextAlign.Center,
                fontSize = size,
                fontFamily = font
            )
        }
    }
    /** Adds a gradient overlay on the composable (gradient default if null) */
    fun Modifier.gradientOverlay(colors: List<Color>? = null): Modifier = composed {
        return@composed this
            .graphicsLayer(alpha = 0.99f)
            .drawWithCache {
                onDrawWithContent {
                    drawContent()
                    drawRect(
                        brush = Brush.linearGradient(
                            colors = colors!!
                        ), blendMode = BlendMode.SrcAtop
                    )
                }
            }
    }

    /** Adds an OVAL gradient overlay on the composable (Syncplay gradient by default) */
    fun Modifier.solidOverlay(color: Color): Modifier {
        return this
            .graphicsLayer(alpha = 0.99f)
            .drawWithCache {
                onDrawWithContent {
                    drawContent()
                    drawRect(
                        color = color,
                        blendMode = BlendMode.SrcAtop
                    )
                }
            }
    }

}