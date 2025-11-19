package jetzy

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission

@RequiresApi(Build.VERSION_CODES.O)
class HotspotManager(private val context: Context) {
    
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null
    
    @SuppressLint("MissingPermission")
    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.NEARBY_WIFI_DEVICES])
    fun startLocalHotspot(
        onStarted: (ssid: String, password: String) -> Unit,
        onFailed: (reason: Int) -> Unit
    ) {
        wifiManager.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
            override fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation?) {
                this@HotspotManager.reservation = reservation
                
                reservation?.wifiConfiguration?.let { config ->
                    val ssid = config.SSID
                    val password = config.preSharedKey
                    onStarted(ssid, password)
                }
            }
            
            override fun onStopped() {
                // Hotspot stopped
            }
            
            override fun onFailed(reason: Int) {
                onFailed(reason)
                // Reasons:
                // ERROR_NO_CHANNEL = 1
                // ERROR_GENERIC = 2
                // ERROR_INCOMPATIBLE_MODE = 3
                // ERROR_TETHERING_DISALLOWED = 4
            }
        }, null)
    }
    
    fun stopLocalHotspot() {
        reservation?.close()
        reservation = null
    }
}