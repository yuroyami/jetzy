package jetzy.utils

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.widget.Toast
import jetzy.MainActivity
import kotlinx.coroutines.Dispatchers
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Enumeration

actual val PreferablyIO = Dispatchers.IO

actual val platform: Platform = Platform.Android

actual fun generateTimestampMillis() = System.currentTimeMillis()

actual fun getDeviceName() = android.os.Build.MODEL ?: "UNKNOWN DEVICE"

actual fun getAvailableStorageBytes(): Long = runCatching {
    val dir = MainActivity.contextGetter().filesDir
    StatFs(dir.path).availableBytes
}.getOrDefault(Long.MAX_VALUE)

actual fun getPersistentStoragePath(): String = MainActivity.contextGetter().filesDir.absolutePath

actual fun isWifiAwareSupported(): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
    val ctx = runCatching { MainActivity.contextGetter() }.getOrNull() ?: return false
    return ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE)
}

fun getLocalIpAddress(): String? {
    try {
        val interfaces: Enumeration<NetworkInterface> = NetworkInterface.getNetworkInterfaces()
        while (interfaces.hasMoreElements()) {
            val networkInterface: NetworkInterface = interfaces.nextElement()
            val addresses: Enumeration<InetAddress> = networkInterface.inetAddresses
            while (addresses.hasMoreElements()) {
                val address: InetAddress = addresses.nextElement()
                if (!address.isLoopbackAddress && address.isSiteLocalAddress) {
                    loggy(address.hostAddress ?: continue)
                    return address.hostAddress
                }
            }
        }
    } catch (e: Exception) {
        loggy(e.stackTraceToString())
    }
    return null
}

fun Context.toasty(s: String) {
    Handler(Looper.getMainLooper()).post {
        Toast.makeText(this@toasty, s, Toast.LENGTH_SHORT).show()
    }
}