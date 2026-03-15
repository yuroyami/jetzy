package jetzy.managers

import android.Manifest
import android.content.Context
import android.os.Build
import jetzy.models.JetzyElement
import jetzy.p2p.P2pPeer

class WiFiDirectP2PM(private val context: Context) : PeerDiscoveryP2PM() {

    // Wi-Fi Direct platform object
    private val wifiP2pManager by lazy {
        context.getSystemService(Context.WIFI_P2P_SERVICE) as android.net.wifi.p2p.WifiP2pManager
    }


    override val requiredPermissions = buildList<String> {
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
    
    override suspend fun sendFiles(files: List<JetzyElement>) {
        transferStatus.value = "Sending ${files.size} files..."
    }
    
    override suspend fun receiveFiles() {
        transferStatus.value = "Waiting to receive files..."
    }

    override suspend fun cleanup() {
        stopDiscoveryAndAdvertising()
    }
}