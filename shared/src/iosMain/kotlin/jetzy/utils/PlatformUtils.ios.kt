package jetzy.utils

import androidx.compose.runtime.Composable
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.compose.PickerResultLauncher
import io.github.vinceglb.filekit.dialogs.compose.rememberDirectoryPickerLauncher
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

@Composable
actual fun rememberDirectoryPickerLauncher(
    onResult: (PlatformFile?) -> Unit
): PickerResultLauncher? = rememberDirectoryPickerLauncher(
    title = "Picker a folder to send", //TODO
    onResult = onResult
)