package jetzy.p2p

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import jetzy.shared.generated.resources.Res
import jetzy.shared.generated.resources.broshk4blue
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
        modifier: Modifier = Modifier,
        text: String,
        size: TextUnit,
        strokeThickness: Float = 4f,
        font: FontResource = Res.font.broshk4blue
    ) {
        Box {
            val font = FontFamily(Font(font))

            // Black stroke (background text)
            Text(
                text,
                style = MaterialTheme.typography.titleMediumEmphasized.copy(
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
                style = MaterialTheme.typography.titleMediumEmphasized.copy(
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
        val clrs = colors
        return@composed this
            .graphicsLayer(alpha = 0.99f)
            .drawWithCache {
                onDrawWithContent {
                    drawContent()
                    drawRect(
                        brush = Brush.linearGradient(
                            colors = clrs!!
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

    /** Shows a popup with the given content.
     * @param dialogOpen Controls whether the popup dialog is shown or not.
     * When this is false, the dialog is not rendered at all.
     * @param widthPercent Width it occupies relative to the screen's width. 0f by default (wraps content).
     * @param heightPercent Percentage of screen's height it occupies. 0f by default (wraps content).
     * @param blurState A [MutableState] variable we should pass to control blur on other composables
     * using Cloudy. The dialog will control the mutable state for us and all we have to do is wrap
     * our Composables in Cloudy composables with the value of said mutable state.
     * @param dismissable Whether the popup dialog can be dismissed or not (via outside click or backpress).
     * @param onDismiss Block of code to execute when there is a dismiss request. If dismissable is false,
     * then the block of code will never get executed (you would have to close the dialog manually via booleans).
     * @param content Composable content.*/
    //TODO post-KMM migration: Activate annotations by leveraging non JVM annotations... if any...
    @Composable
    fun AppPopup(
        dialogOpen: Boolean,
        /* @IntRange(from = 0)*/ cardCornerRadius: Int = 10,
        /* @FloatRange(0.0, 10.0)*/ strokeWidth: Float = 1.5f,
        /* @FloatRange(0.0, 1.0)*/ widthPercent: Float = 0f,
        /* @FloatRange(0.0, 1.0)*/ heightPercent: Float = 0f,
        dismissable: Boolean = true,
        onDismiss: () -> Unit = {},
        content: @Composable BoxScope.() -> Unit,
    ) {
        if (dialogOpen) {
            Dialog(
                onDismissRequest = {
                    onDismiss()
                },
                properties = DialogProperties(
                    usePlatformDefaultWidth = false,
                    //decorFitsSystemWindows = false,
                    dismissOnClickOutside = dismissable,
                    dismissOnBackPress = dismissable
                )
            ) {
                var modifier: Modifier = Modifier
                modifier = if (widthPercent == 0f) {
                    modifier.wrapContentWidth()
                } else {
                    modifier.fillMaxWidth(widthPercent)
                }
                modifier = if (heightPercent == 0f) {
                    modifier.wrapContentHeight()
                } else {
                    modifier.fillMaxHeight(heightPercent)
                }

                Card(
                    modifier = modifier,
                    shape = RoundedCornerShape(size = cardCornerRadius.dp),
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxSize()
                    ) {
                        content()
                    }
                }

            }
        }
    }

}