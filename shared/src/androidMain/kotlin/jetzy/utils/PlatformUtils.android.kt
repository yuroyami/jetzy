package jetzy.utils

import android.widget.Toast
import androidx.activity.ComponentActivity
import kotlinx.coroutines.Dispatchers
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Enumeration

actual val PreferablyIO = Dispatchers.IO

actual val platform: Platform = Platform.Android

actual fun generateTimestampMillis() = System.currentTimeMillis()


actual fun getDeviceName() = android.os.Build.MODEL ?: "UNKNOWN DEVICE"

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

fun ComponentActivity.toasty(s: String) {
    runOnUiThread {
        Toast.makeText(this@toasty, s, Toast.LENGTH_SHORT).show()
    }
}