package jetz.common.utils

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp

enum class Platform {
    Android,
    iOS
}

expect fun getPlatform(): Platform

expect fun generateTimestampMillis(): Long

/** Getting screen size info for UI-related calculations */
data class ScreenSizeInfo(val hPX: Int, val wPX: Int, val hDP: Dp, val wDP: Dp)
@Composable expect fun getScreenSizeInfo(): ScreenSizeInfo

expect fun getDeviceName(): String
