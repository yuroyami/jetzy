package jetzy.p2p

import android.Manifest
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import jetzy.utils.loggy
import jetzy.utils.toasty
import jetzy.viewmodel.JetzyViewmodel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

class P2pAndroidHandler(val activity: ComponentActivity, override val viewmodel: JetzyViewmodel) : P2pHandler(viewmodel) {

    private val wifiDirectPerms = buildList<String> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        /*
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_CONNECT)
            add(Manifest.permission.BLUETOOTH_ADVERTISE)
            add(Manifest.permission.BLUETOOTH_SCAN)
        } else {
            add(Manifest.permission.BLUETOOTH)
            add(Manifest.permission.BLUETOOTH_ADMIN)
        }*/
    }.toMutableList()

    private val p2pPermissioner = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (!isGranted) {
            activity.toasty("You didn't grant all P2P WiFi Direct permissions (Nearby, GPS)")
        } else {
            runWifiDirectPermissions()
        }
    }

    override fun beginP2p(mode: P2pMode, operation: P2pOperation) {
        super.beginP2p(mode, operation)


    }

    private fun runWifiDirectPermissions() {
        if (wifiDirectPerms.isNotEmpty()) {
            val permission = wifiDirectPerms.first()
            p2pPermissioner.launch(permission)
            wifiDirectPerms.remove(permission)
        } else {
            viewmodel.p2pChoosePeerPopup.value = true

            //TODO: WiFi Direct permissions ready
        }
    }


    override fun connectNativePeer(peer: P2pPeer) {
        selectedPeer = peer

        //TODO Do connect
    }


    override fun stopP2pOperations() {
        //TODO Force stop any operations

        super.stopP2pOperations()
    }




    private var socket: Socket? = null
    private var socketJob: Job? = null
    fun hostCrossPlatform(): CompletableDeferred<Pair<String, Int>> {
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

}