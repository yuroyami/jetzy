package jetzy.utils

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.CoroutineDispatcher

enum class Platform(val label: String, val brandColor: Color) {
    Android("Android", Color(0xff32de84)),
    IOS("iOS", Color(0xffa2aaad)),
    Web("Browser", Color(0xff3778bf)),
    PC("PC", Color(0xfff14f21))
}

expect val platform: Platform

expect fun generateTimestampMillis(): Long

/** Getting screen size info for UI-related calculations */
data class ScreenSizeInfo(val hPX: Int, val wPX: Int, val hDP: Dp, val wDP: Dp)
@Composable expect fun getScreenSizeInfo(): ScreenSizeInfo

expect fun getDeviceName(): String

expect val PreferablyIO: CoroutineDispatcher
