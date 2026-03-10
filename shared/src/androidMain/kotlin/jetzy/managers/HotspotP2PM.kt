package jetzy.managers

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import androidx.annotation.RequiresPermission
import jetzy.models.JetzyElement

class HotspotP2PM(context: Context): QRDiscoveryP2PM() {

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
                this@HotspotP2PM.reservation = reservation

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

    override suspend fun initialize() {

    }

    override suspend fun cleanup() {
    }

    override suspend fun sendFiles(files: List<JetzyElement>): Result<Unit> {
        TODO("Not yet implemented")
    }

    override suspend fun receiveFiles(outputDir: JetzyElement): Result<List<JetzyElement>> {
        TODO("Not yet implemented")
    }

    override suspend fun disconnect() {
        TODO("Not yet implemented")
    }
}