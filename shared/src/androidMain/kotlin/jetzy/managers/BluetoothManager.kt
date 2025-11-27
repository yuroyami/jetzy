// androidMain/jetzy/p2p/bluetooth/BluetoothManager.kt
@file:SuppressLint("MissingPermission")
package jetzy.managers

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import jetzy.models.JetzyElement
import jetzy.p2p.P2pPeer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID

class BluetoothManager(
    private val context: Context
) : DiscoverableP2PManager {
    
    private val _transferProgress = MutableStateFlow(0f)
    override val transferProgress: StateFlow<Float> = _transferProgress
    
    private val _transferStatus = MutableStateFlow("")
    override val transferStatus: StateFlow<String> = _transferStatus
    
    private val _isConnected = MutableStateFlow(false)
    override val isConnected: StateFlow<Boolean> = _isConnected
    
    private val _availablePeers = MutableStateFlow<List<P2pPeer>>(emptyList())
    override val availablePeers: StateFlow<List<P2pPeer>> = _availablePeers
    
    private val _isDiscovering = MutableStateFlow(false)
    override val isDiscovering: StateFlow<Boolean> = _isDiscovering
    
    private val _isAdvertising = MutableStateFlow(false)
    override val isAdvertising: StateFlow<Boolean> = _isAdvertising
    
    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        BluetoothAdapter.getDefaultAdapter()
    }
    
    private var discoveryReceiver: BroadcastReceiver? = null
    private var acceptThread: AcceptThread? = null
    private var connectThread: ConnectThread? = null
    private var connectedSocket: BluetoothSocket? = null
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    companion object {
        private val SERVICE_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB") // SPP UUID
        private const val SERVICE_NAME = "JetzyTransfer"
        private const val BUFFER_SIZE = 8192
    }
    
    override suspend fun initialize() {
        if (bluetoothAdapter == null) {
            _transferStatus.value = "Bluetooth not supported"
            throw IllegalStateException("Bluetooth not supported on this device")
        }
        
        if (!bluetoothAdapter!!.isEnabled) {
            _transferStatus.value = "Bluetooth is disabled"
            throw IllegalStateException("Bluetooth is disabled")
        }
        
        if (!hasPermissions()) {
            throw SecurityException("Bluetooth permissions not granted")
        }
        
        _transferStatus.value = "Bluetooth ready"
    }
    
    private fun hasPermissions(): Boolean {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE
            )
        } else {
            listOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }
        
        return permissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }
    
    override suspend fun startDiscoveryAndAdvertising(deviceName: String) = withContext(Dispatchers.Main) {
        if (!hasPermissions()) return@withContext
        
        val adapter = bluetoothAdapter ?: return@withContext
        
        // Make device discoverable (advertising)
        makeDiscoverable()
        
        // Start discovery
        startDiscovery()
        
        // Start listening for incoming connections
        startAcceptThread()
        
        _transferStatus.value = "Looking for nearby Bluetooth devices..."
    }
    
    private fun makeDiscoverable() {
        if (!hasPermissions()) return
        
        _isAdvertising.value = true
        
        // Make device discoverable for 300 seconds
        val discoverableIntent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
            putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        
        try {
            context.startActivity(discoverableIntent)
        } catch (e: Exception) {
            _transferStatus.value = "Could not make device discoverable: ${e.message}"
        }
    }
    
    @SuppressLint("MissingPermission")
    private fun startDiscovery() {
        if (!hasPermissions()) return
        
        val adapter = bluetoothAdapter ?: return
        
        // Cancel any ongoing discovery
        if (adapter.isDiscovering) {
            adapter.cancelDiscovery()
        }
        
        // Register receiver for discovered devices
        discoveryReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                        }
                        
                        device?.let { addDiscoveredDevice(it) }
                    }
                    
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                        _isDiscovering.value = false
                        _transferStatus.value = "Discovery finished. Found ${_availablePeers.value.size} device(s)"
                    }
                }
            }
        }
        
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        
        context.registerReceiver(discoveryReceiver, filter)
        
        // Start discovery
        _isDiscovering.value = adapter.startDiscovery()
    }
    
    private fun addDiscoveredDevice(device: BluetoothDevice) {
        if (!hasPermissions()) return
        
        val peer = P2pPeer(
            id = device.address,
            name = device.name ?: "Unknown Device",
            //platform = null, // Can't determine platform from Bluetooth
            //signalStrength = null
        )
        
        val currentPeers = _availablePeers.value.toMutableList()
        if (currentPeers.none { it.id == peer.id }) {
            currentPeers.add(peer)
            _availablePeers.value = currentPeers
        }
    }
    

    override suspend fun stopDiscoveryAndAdvertising() {
        bluetoothAdapter?.cancelDiscovery()
        _isDiscovering.value = false
        _isAdvertising.value = false
        
        discoveryReceiver?.let {
            try {
                context.unregisterReceiver(it)
            } catch (e: Exception) {
                // Already unregistered
            }
            discoveryReceiver = null
        }
        
        acceptThread?.cancel()
        acceptThread = null
    }
    
    override suspend fun connectToPeer(peer: P2pPeer): Result<Unit> = withContext(Dispatchers.IO) {
        if (!hasPermissions()) return@withContext Result.failure(SecurityException("Missing permissions"))
        
        val adapter = bluetoothAdapter ?: return@withContext Result.failure(IllegalStateException("Bluetooth not available"))
        
        // Cancel discovery to improve connection speed
        adapter.cancelDiscovery()
        
        val device = adapter.getRemoteDevice(peer.id)
        
        _transferStatus.value = "Connecting to ${peer.name}..."
        
        connectThread = ConnectThread(device)
        connectThread?.start()
        
        Result.success(Unit)
    }
    
    override suspend fun sendFiles(files: List<JetzyElement>): Result<Unit> = withContext(Dispatchers.IO) {
        val socket = connectedSocket ?: return@withContext Result.failure(IllegalStateException("Not connected"))
        return@withContext Result.failure(Exception())
//        try {
//            val outputStream = DataOutputStream(socket.outputStream)
//
//            // Send number of files
//            outputStream.writeInt(files.size)
//
//            var totalBytesSent = 0L
//            val totalBytes = files.sumOf { it.length() }
//
//            files.forEachIndexed { index, file ->
//                _transferStatus.value = "Sending ${index + 1}/${files.size}: ${file.name}"
//
//                // Send file metadata
//                outputStream.writeUTF(file.name)
//                outputStream.writeLong(file.length())
//
//                // Send file content
//                FileInputStream(file).use { fis ->
//                    val buffer = ByteArray(BUFFER_SIZE)
//                    var bytesRead: Int
//
//                    while (fis.read(buffer).also { bytesRead = it } != -1) {
//                        outputStream.write(buffer, 0, bytesRead)
//                        totalBytesSent += bytesRead
//                        _transferProgress.value = totalBytesSent.toFloat() / totalBytes.toFloat()
//                    }
//                }
//            }
//
//            outputStream.flush()
//            _transferStatus.value = "Transfer complete!"
//            _transferProgress.value = 1f
//
//            Result.success(Unit)
//        } catch (e: Exception) {
//            _transferStatus.value = "Send failed: ${e.message}"
//            Result.failure(e)
//        }
    }
    
    override suspend fun receiveFiles(outputDir: JetzyElement): Result<List<JetzyElement>> = withContext(Dispatchers.IO) {
        val socket = connectedSocket ?: return@withContext Result.failure(IllegalStateException("Not connected"))


        return@withContext Result.failure(Exception())
//        val receivedFiles = mutableListOf<File>()
//        try {
//            val inputStream = DataInputStream(socket.inputStream)
//
//            // Read number of files
//            val fileCount = inputStream.readInt()
//            _transferStatus.value = "Receiving $fileCount file(s)..."
//
//            repeat(fileCount) { index ->
//                // Read file metadata
//                val fileName = inputStream.readUTF()
//                val fileSize = inputStream.readLong()
//
//                _transferStatus.value = "Receiving ${index + 1}/$fileCount: $fileName"
//
//                val outputFile = File(outputDir, fileName)
//                outputFile.parentFile?.mkdirs()
//
//                // Receive file content
//                FileOutputStream(outputFile).use { fos ->
//                    val buffer = ByteArray(BUFFER_SIZE)
//                    var totalBytesRead = 0L
//
//                    while (totalBytesRead < fileSize) {
//                        val bytesToRead = minOf(BUFFER_SIZE.toLong(), fileSize - totalBytesRead).toInt()
//                        val bytesRead = inputStream.read(buffer, 0, bytesToRead)
//
//                        if (bytesRead == -1) break
//
//                        fos.write(buffer, 0, bytesRead)
//                        totalBytesRead += bytesRead
//                        _transferProgress.value = totalBytesRead.toFloat() / fileSize.toFloat()
//                    }
//                }
//
//                receivedFiles.add(outputFile)
//            }
//
//            _transferStatus.value = "Received $fileCount file(s)"
//            _transferProgress.value = 1f
//
//            Result.success(receivedFiles)
//        } catch (e: Exception) {
//            _transferStatus.value = "Receive failed: ${e.message}"
//            Result.failure(e)
//        }
    }
    
    override suspend fun disconnect() {
        try {
            connectedSocket?.close()
        } catch (e: Exception) {
            // Ignore
        }
        
        connectedSocket = null
        _isConnected.value = false
        _transferStatus.value = "Disconnected"
        
        connectThread?.cancel()
        connectThread = null
    }
    
    override suspend fun cleanup() {
        stopDiscoveryAndAdvertising()
        disconnect()
        scope.cancel()
    }
    
    // Thread for listening for incoming connections (server)
    private inner class AcceptThread : Thread() {
        private val serverSocket: BluetoothServerSocket? by lazy {
            if (!hasPermissions()) null
            else bluetoothAdapter?.listenUsingRfcommWithServiceRecord(SERVICE_NAME, SERVICE_UUID)
        }
        
        override fun run() {
            var shouldLoop = true
            while (shouldLoop) {
                try {
                    val socket = serverSocket?.accept()
                    socket?.let {
                        scope.launch {
                            _transferStatus.value = "Connected to ${it.remoteDevice.name}"
                            _isConnected.value = true
                            connectedSocket = it
                        }
                        shouldLoop = false
                    }
                } catch (e: IOException) {
                    shouldLoop = false
                }
            }
        }
        
        fun cancel() {
            try {
                serverSocket?.close()
            } catch (e: IOException) {
                // Ignore
            }
        }
    }
    
    // Thread for connecting to a remote device (client)
    private inner class ConnectThread(private val device: BluetoothDevice) : Thread() {
        private val socket: BluetoothSocket? by lazy {
            if (!hasPermissions()) null
            else device.createRfcommSocketToServiceRecord(SERVICE_UUID)
        }
        
        override fun run() {
            if (!hasPermissions()) return
            
            // Cancel discovery to improve connection reliability
            bluetoothAdapter?.cancelDiscovery()
            
            try {
                socket?.connect()
                socket?.let {
                    scope.launch {
                        _transferStatus.value = "Connected to ${device.name}"
                        _isConnected.value = true
                        connectedSocket = it
                    }
                }
            } catch (e: IOException) {
                scope.launch {
                    _transferStatus.value = "Connection failed: ${e.message}"
                }
                try {
                    socket?.close()
                } catch (closeException: IOException) {
                    // Ignore
                }
            }
        }
        
        fun cancel() {
            try {
                socket?.close()
            } catch (e: IOException) {
                // Ignore
            }
        }
    }
    
    private fun startAcceptThread() {
        acceptThread?.cancel()
        acceptThread = AcceptThread()
        acceptThread?.start()
    }
}

// Required permissions in AndroidManifest.xml:
/*
<!-- For Android 12+ -->
<uses-permission android:name="android.permission.BLUETOOTH_SCAN" android:usesPermissionFlags="neverForLocation" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<uses-permission android:name="android.permission.BLUETOOTH_ADVERTISE" />

<!-- For Android 11 and below -->
<uses-permission android:name="android.permission.BLUETOOTH" android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" android:maxSdkVersion="30" />

<uses-feature android:name="android.hardware.bluetooth" android:required="false" />
*/