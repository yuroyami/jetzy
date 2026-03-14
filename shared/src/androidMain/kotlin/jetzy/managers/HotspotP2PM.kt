package jetzy.managers

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import androidx.annotation.RequiresPermission
import androidx.lifecycle.viewModelScope
import jetzy.models.JetzyElement
import jetzy.utils.loggy
import jetzy.viewmodel.JetzyViewmodel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

class HotspotP2PM(context: Context, viewmodel: JetzyViewmodel): QRDiscoveryP2PM() {

    override val coroutineScope: CoroutineScope = viewmodel.viewModelScope

    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null

    private var socket: Socket? = null
    private var socketJob: Job? = null

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

    fun initServerTCP() {
        val future: CompletableDeferred<Pair<String, Int>> = CompletableDeferred()
        runCatching { socketJob?.cancel() }
        socketJob = GlobalScope.launch(Dispatchers.IO) {
            try {
                val localAddress = "" //todo getLocalIpAddress()

                if (localAddress == null) {
                    future.complete(Pair("", 0))
                    return@launch
                }

                val serverSocket = ServerSocket()
                serverSocket.reuseAddress = true
                val boundAddress = InetSocketAddress("0.0.0.0", 0)
                serverSocket.bind(boundAddress)
                serverSocket.soTimeout = 0

                future.complete(Pair(localAddress, serverSocket.localPort))

                socket = serverSocket.accept()

                if (viewmodel.userMode.value == true) {
                    //p2pInput = socket?.getInputStream()?.asSource()
                } else {
                    //p2pOutput = socket?.getOutputStream()?.asSink()
                }

                //At this point we're connected by QR code on LAN
                //carryOnP2PCross()

            } catch (e: Exception) {
                loggy(e.stackTraceToString())
                future.complete(Pair("", 0))
            }
        }
        return future
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