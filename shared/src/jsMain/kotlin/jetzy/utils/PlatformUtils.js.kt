package jetzy.utils

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import kotlinx.browser.window
import kotlinx.coroutines.Dispatchers
import kotlin.js.Date
import kotlin.math.roundToLong

actual val PreferablyIO = Dispatchers.Default  //There is no multithreading/IO threading on JS, it is single threaded

actual val platform = Platform.Web

actual fun generateTimestampMillis() = Date().getTime().roundToLong()

@OptIn(ExperimentalComposeUiApi::class)
@Composable
actual fun getScreenSizeInfo(): ScreenSizeInfo {
    val density = LocalDensity.current
    val config = LocalWindowInfo.current.containerSize


    return remember(density, config) {
        ScreenSizeInfo(
            hPX = config.height,
            wPX = config.width,
            hDP = with(density) { config.height.toDp() },
            wDP = with(density) { config.width.toDp() }
        )
    }
}

actual fun getDeviceName(): String {
    val userAgent = window.navigator.userAgent
    val platform = window.navigator.platform

    val browser = when {
        userAgent.contains("Chrome") && !userAgent.contains("Edg") -> "Chrome"
        userAgent.contains("Firefox") -> "Firefox"
        userAgent.contains("Safari") && !userAgent.contains("Chrome") -> "Safari"
        userAgent.contains("Edg") -> "Edge"
        userAgent.contains("Opera") || userAgent.contains("OPR") -> "Opera"
        else -> "Unknown Browser"
    }

    val platformName = when {
        platform.contains("Mac") -> "macOS"
        platform.contains("Win") -> "Windows"
        platform.contains("Linux") -> "Linux"
        userAgent.contains("Android") -> "Android"
        userAgent.contains("iPhone") -> "iOS"
        userAgent.contains("iPad") -> "iPadOS"
        else -> platform
    }

    return "$browser on $platformName"
}