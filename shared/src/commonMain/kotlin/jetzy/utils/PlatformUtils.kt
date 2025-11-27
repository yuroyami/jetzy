package jetzy.utils

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import compose.icons.FontAwesomeIcons
import compose.icons.fontawesomeicons.Brands
import compose.icons.fontawesomeicons.Solid
import compose.icons.fontawesomeicons.brands.Android
import compose.icons.fontawesomeicons.brands.Apple
import compose.icons.fontawesomeicons.brands.Chrome
import compose.icons.fontawesomeicons.solid.Desktop
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.compose.PickerResultLauncher
import kotlinx.coroutines.CoroutineDispatcher

enum class Platform(val label: String, val brandColor: Color, val icon: ImageVector, val canScanQR: Boolean) {
    Android("Android", Color(0xff32de84), FontAwesomeIcons.Brands.Android, canScanQR = true),
    IOS("iOS", Color(0xffa2aaad), FontAwesomeIcons.Brands.Apple, canScanQR = true),
    Web("Browser", Color(0xff3778bf), FontAwesomeIcons.Brands.Chrome, canScanQR = false),
    PC("PC", Color(0xfff14f21), FontAwesomeIcons.Solid.Desktop, canScanQR = false)
}

expect val platform: Platform

expect fun generateTimestampMillis(): Long

expect fun getDeviceName(): String

expect val PreferablyIO: CoroutineDispatcher

/** Picks a directory (folder).
 * Note that this is written in expect/actual fashion because FileKit does NOT provide it in common code when one of the targets
 * does not support it (which in this case is JS)
 */
@Composable
expect fun rememberDirectoryPickerLauncher(onResult: (PlatformFile?) -> Unit): PickerResultLauncher?

