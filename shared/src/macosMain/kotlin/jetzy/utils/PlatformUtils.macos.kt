package jetzy.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import platform.Foundation.NSDate
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSystemFreeSize
import platform.Foundation.NSHost
import platform.Foundation.NSNumber
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUserDomainMask
import platform.Foundation.NSUserName
import platform.Foundation.timeIntervalSince1970
import kotlin.math.roundToLong

actual val PreferablyIO = Dispatchers.IO

actual val platform = Platform.PC  // macOS native build is a desktop PC for Jetzy's UX purposes

actual fun generateTimestampMillis(): Long {
    return (NSDate().timeIntervalSince1970 * 1000).roundToLong()
}

actual fun getDeviceName(): String {
    // Order: user-set localized host name → bare host name → user login name → "Mac"
    return NSHost.currentHost().localizedName?.takeIf { it.isNotBlank() }
        ?: NSHost.currentHost().name?.takeIf { it.isNotBlank() }
        ?: NSUserName().takeIf { it.isNotBlank() }
        ?: "Mac"
}

actual fun getAvailableStorageBytes(): Long {
    val path = getPersistentStoragePath()
    return runCatching {
        val attrs = NSFileManager.defaultManager.attributesOfFileSystemForPath(path, error = null)
            ?: return@runCatching Long.MAX_VALUE
        (attrs[NSFileSystemFreeSize] as? NSNumber)?.longLongValue ?: Long.MAX_VALUE
    }.getOrDefault(Long.MAX_VALUE)
}

actual fun getPersistentStoragePath(): String {
    val paths = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true)
    return paths.firstOrNull() as? String ?: NSTemporaryDirectory()
}

/**
 * Wi-Fi Aware is iOS-only as of iOS 26 — Apple has not extended the framework
 * to macOS. macOS users fall back to the existing transports.
 */
actual fun isWifiAwareSupported(): Boolean = false
