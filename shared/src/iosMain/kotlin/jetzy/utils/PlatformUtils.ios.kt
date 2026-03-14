package jetzy.utils

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