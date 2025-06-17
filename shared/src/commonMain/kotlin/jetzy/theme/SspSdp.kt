package jetzy.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Custom SDP implementation for Compose Multiplatform
 * Based on a 320dp baseline width (similar to Android SDP)
 */

@Composable
fun getScaleFactor(): Float {
    val density = LocalDensity.current
    val screenSize = LocalWindowInfo.current.containerSize

    return remember(screenSize, density.density) {
        val screenWDP = with(density) { screenSize.width.toDp() }
        val screenHDP = with(density) { screenSize.height.toDp() }

        // Use diagonal as scaling reference (like Android SDP)
        val screenDiagonal = sqrt(screenWDP.value.pow(2) + screenHDP.value.pow(2))
        val baseDiagonal = sqrt(320f.pow(2) + 480f.pow(2)) // 320x480 baseline

        screenDiagonal / baseDiagonal
    }
}

inline val Int.sdp: Dp
    @Composable get() = (this * getScaleFactor()).dp

inline val Int.ssp: TextUnit
    @Composable get() = (this * getScaleFactor()).sp