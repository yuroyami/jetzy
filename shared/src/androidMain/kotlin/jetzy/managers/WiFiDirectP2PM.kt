package jetzy.managers

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.lifecycle.viewModelScope
import jetzy.models.JetzyElement
import jetzy.p2p.P2pPeer
import jetzy.viewmodel.JetzyViewmodel
import kotlinx.coroutines.CoroutineScope

class WiFiDirectP2PM(
    private val context: Context,
    viewmodel: JetzyViewmodel
) : PeerDiscoveryP2PM() {

    override val coroutineScope: CoroutineScope = viewmodel.viewModelScope

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

//
    private val p2pPermissioner = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (!isGranted) {
            activity.toasty("You didn't grant all P2P WiFi Direct permissions (Nearby, GPS)")
        } else {
            runWifiDirectPermissions()
        }
    }

//    private fun runWifiDirectPermissions() {
//        if (wifiDirectPerms.isNotEmpty()) {
//            val permission = wifiDirectPerms.first()
//            p2pPermissioner.launch(permission)
//            wifiDirectPerms.remove(permission)
//        } else {
//            viewmodel.p2pChoosePeerPopup.value = true
//
//            //TODO: WiFi Direct permissions ready
//        }
//    }

    // Wi-Fi Direct platform object
    private val wifiP2pManager by lazy {
        context.getSystemService(Context.WIFI_P2P_SERVICE) as android.net.wifi.p2p.WifiP2pManager
    }
    
    override suspend fun initialize() {
        // Initialize WiFi Direct
        transferStatus.value = "WiFi Direct initialized"
    }
    
    override suspend fun startDiscoveryAndAdvertising(deviceName: String) {
        isDiscovering.value = true
        isAdvertising.value = true
        transferStatus.value = "Looking for nearby devices..."
        // Wi-Fi Direct automatically makes device discoverable when discovering
        // Implement discovery
    }
    
    override suspend fun stopDiscoveryAndAdvertising() {
        isDiscovering.value = false
        isAdvertising.value = false
    }
    
    override suspend fun connectToPeer(peer: P2pPeer): Result<Unit> {
        transferStatus.value = "Connecting to ${peer.name}..."
        // Implement connection
        return Result.success(Unit)
    }
    
    override suspend fun sendFiles(files: List<JetzyElement>): Result<Unit> {
        transferStatus.value = "Sending ${files.size} files..."
        // Implement send
        return Result.success(Unit)
    }
    
    override suspend fun receiveFiles(outputDir: JetzyElement): Result<List<JetzyElement>> {
        transferStatus.value = "Waiting to receive files..."
        // Implement receive
        return Result.success(emptyList())
    }
    
    override suspend fun disconnect() {
        isConnected.value = false
        transferStatus.value = "Disconnected"
    }
    
    override suspend fun cleanup() {
        stopDiscoveryAndAdvertising()
        disconnect()
    }
}