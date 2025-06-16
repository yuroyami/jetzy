package jetzy.utils

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import compose.icons.FontAwesomeIcons
import compose.icons.fontawesomeicons.Brands
import compose.icons.fontawesomeicons.Solid
import compose.icons.fontawesomeicons.brands.Android
import compose.icons.fontawesomeicons.brands.Apple
import compose.icons.fontawesomeicons.brands.Chrome
import compose.icons.fontawesomeicons.solid.Desktop
import kotlinx.coroutines.CoroutineDispatcher

enum class Platform(val label: String, val brandColor: Color, val icon: ImageVector) {
    Android("Android", Color(0xff32de84), FontAwesomeIcons.Brands.Android),
    IOS("iOS", Color(0xffa2aaad), FontAwesomeIcons.Brands.Apple),
    Web("Browser", Color(0xff3778bf), FontAwesomeIcons.Brands.Chrome),
    PC("PC", Color(0xfff14f21), FontAwesomeIcons.Solid.Desktop)
}

expect val platform: Platform

expect fun generateTimestampMillis(): Long

/** Getting screen size info for UI-related calculations */
data class ScreenSizeInfo(val hPX: Int, val wPX: Int, val hDP: Dp, val wDP: Dp)
@Composable expect fun getScreenSizeInfo(): ScreenSizeInfo

expect fun getDeviceName(): String

expect val PreferablyIO: CoroutineDispatcher
