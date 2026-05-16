package jetzy.utils

import kotlinx.coroutines.Dispatchers
import java.io.File
import java.net.InetAddress
import java.util.Locale

actual val PreferablyIO = Dispatchers.IO

actual val platform: Platform = Platform.PC

actual fun generateTimestampMillis(): Long = System.currentTimeMillis()

/**
 * "MacBook of Macbook" on macOS, "MACBOOK-PC" on Windows, host name on Linux.
 * We prefer the OS-assigned host name over Java's `user.name` because that's
 * what an Android peer's discovery UI would naturally display.
 */
actual fun getDeviceName(): String = runCatching {
    InetAddress.getLocalHost().hostName.takeIf { !it.isNullOrBlank() }
}.getOrNull() ?: System.getProperty("user.name")?.takeIf { it.isNotBlank() }
    ?: osLabel()

private fun osLabel(): String {
    val os = System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT)
    return when {
        "win"   in os -> "Windows PC"
        "mac"   in os || "darwin" in os -> "Mac"
        "linux" in os || "nix" in os || "nux" in os -> "Linux PC"
        else -> "PC"
    }
}

actual fun getAvailableStorageBytes(): Long = runCatching {
    File(getPersistentStoragePath()).usableSpace.takeIf { it > 0 } ?: Long.MAX_VALUE
}.getOrDefault(Long.MAX_VALUE)

/**
 * Per-user data dir. We honour OS-specific conventions so received files end up
 * somewhere users actually expect:
 *  - macOS: ~/Library/Application Support/Jetzy
 *  - Windows: %APPDATA%\Jetzy
 *  - Linux: $XDG_DATA_HOME/Jetzy (defaults to ~/.local/share/Jetzy)
 *
 * Falls back to `~/Jetzy` if none of the above is resolvable.
 */
actual fun isWifiAwareSupported(): Boolean = false  // Apple hasn't shipped on macOS, Microsoft has no roadmap.

actual fun getPersistentStoragePath(): String {
    val home = System.getProperty("user.home") ?: "."
    val os = System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT)
    val dir = when {
        "win" in os -> {
            val appData = System.getenv("APPDATA")?.takeIf { it.isNotBlank() }
            File(appData ?: "$home\\AppData\\Roaming", "Jetzy")
        }
        "mac" in os || "darwin" in os -> File("$home/Library/Application Support", "Jetzy")
        else -> {
            val xdg = System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() }
            File(xdg ?: "$home/.local/share", "Jetzy")
        }
    }
    if (!dir.exists()) dir.mkdirs()
    return dir.absolutePath
}
