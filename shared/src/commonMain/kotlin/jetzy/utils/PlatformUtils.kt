package jetzy.utils

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.CoroutineDispatcher

enum class Platform {
    Android,
    IOS,
    Web
}

expect val platform: Platform

expect fun generateTimestampMillis(): Long

/** Getting screen size info for UI-related calculations */
data class ScreenSizeInfo(val hPX: Int, val wPX: Int, val hDP: Dp, val wDP: Dp)
@Composable expect fun getScreenSizeInfo(): ScreenSizeInfo

expect fun getDeviceName(): String

expect val PreferablyIO: CoroutineDispatcher
