package jetzy.utils

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970
import platform.UIKit.UIDevice
import kotlin.math.roundToLong

actual val PreferablyIO = Dispatchers.IO

actual val platform = Platform.IOS


actual fun generateTimestampMillis(): Long {
    return (NSDate().timeIntervalSince1970 * 1000).roundToLong()
}

actual fun getDeviceName() = UIDevice.currentDevice.name

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