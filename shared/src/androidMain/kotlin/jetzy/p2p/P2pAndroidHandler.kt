package jetzy.p2p

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import jetzy.utils.loggy
import jetzy.utils.toasty
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.io.RawSink
import kotlinx.io.RawSource
import kotlinx.io.asSink
import kotlinx.io.asSource
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

@SuppressLint("MissingPermission", "Recycle")
class P2pAndroidHandler(private val activity: ComponentActivity) : P2pHandler() {

    /* Nearby-Connections client properties */
    lateinit var ncClient: ConnectionsClient
    private val ncAlias = "${Build.BRAND} - ${Build.DEVICE}"
    private val ncServiceID = "GMIX_P2P_PLAYLIST_TRANSFER"
    private val ncListener = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            selectedPeer = P2pPeerAndroid(endpointId, info.endpointName)

            ncClient.acceptConnection(endpointId, object : PayloadCallback() {
                    override fun onPayloadReceived(endpointId: String, payload: Payload) {
                        if (payload.type == Payload.Type.STREAM) {
                            if (p2pInput == null) {
                                p2pInput = payload.asStream()?.asInputStream()?.asSource()
                            }
                        }
                    }
                    override fun onPayloadTransferUpdate(p0: String, p1: PayloadTransferUpdate) {}
                }
            )
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            when (result.status.statusCode) {
                ConnectionsStatusCodes.STATUS_OK -> {
                    carryOnP2P()

                    if (viewmodel.userMode.value != true) {
                        val outPipe = PipedOutputStream()
                        val inPipe = PipedInputStream()
                        inPipe.connect(outPipe)

                        p2pOutput = outPipe.asSink()

                        val payload = Payload.fromStream(inPipe)

                        ncClient.sendPayload(endpointId, payload)
                    }
                }

                ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED -> activity.toasty("P2P Connection rejected. Check peer device.")
                ConnectionsStatusCodes.STATUS_ERROR -> activity.toasty("Undefined P2P error")
                else -> {
                    stopP2pOperations()
                    activity.toasty("Unknown P2P error")
                }
            }
        }

        override fun onDisconnected(p0: String) {
            stopP2pOperations()
            activity.toasty("Disconnected from P2P peer.")
        }
    }
    override var p2pMode: P2pMode? = P2pMode.ANDROID_TO_ANDROID
    private var socket: Socket? = null
    private var socketJob: Job? = null

    lateinit var browserLambda: (String) -> Unit

    private val ncPermissions = mutableListOf<String>().apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_CONNECT)
            add(Manifest.permission.BLUETOOTH_ADVERTISE)
            add(Manifest.permission.BLUETOOTH_SCAN)
        } else {
            add(Manifest.permission.BLUETOOTH)
            add(Manifest.permission.BLUETOOTH_ADMIN)
        }
    }
    private val p2pPermissioner = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (!isGranted) {
            activity.toasty("You didn't grant all P2P permissions (Nearby, GPS, Bluetooth)")
        } else {
            runNcPermissions()
        }
    }
    private var p2pReceptionResult = activity.registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@registerForActivityResult

        /* Taking persistable permission */
        activity.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)

        val dest = DocumentFile.fromTreeUri(activity, uri) ?: return@registerForActivityResult
        loggy(dest.uri.toString())

        activity.lifecycleScope.launch(Dispatchers.IO) {
            for (s in receivedSongs) {
                try {
                    activity.contentResolver.openInputStream(s.file.uri)?.use { input ->
                        val newF = dest.createFile("audio/*", s.file.name ?: "received_${(0..99999).random()}.mp3")
                        newF?.let { f ->
                            activity.contentResolver.openOutputStream(f.uri)?.use { output ->
                                input.copyTo(output)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("GMIX", "Failed to save transferred P2P file", e)
                }
            }

            withContext(Dispatchers.Main) {
               browserLambda.invoke(uri.toString())
            }

            activity.toasty("Playlist is successfully saved to selected folder.")
        }
    }

    private fun runNcPermissions() {
        if (ncPermissions.isNotEmpty()) {
            val permission = ncPermissions.first()
            p2pPermissioner.launch(permission)
            ncPermissions.remove(permission)
        } else {
            viewmodel.p2pChoosePeerPopup.value = true
            startP2PforReal()
        }
    }

    fun init() {
        p2pHandler = this
        isP2Ptransferring = false
        selectedPeer = null
        runCatching {
            //todo coroutineP2p.cancel()
        }
        //coroutineP2p = CoroutineScope(Dispatchers.IO)
    }

    /*********** Android to Android **************/
    override fun startNative() {
        p2pMode = P2pMode.ANDROID_TO_ANDROID
        ncClient.stopAllEndpoints() //Making sure there are no leftover connections
        runNcPermissions()
    }

    fun startP2PforReal() {
        when (GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(activity)) {
            ConnectionResult.SUCCESS -> {
                loggy("Google Play services are available.")
                if (viewmodel.userMode.value == true) startP2PDiscovery() else startP2PAdvertising()
                viewmodel.p2pChoosePeerPopup.value = true
            }

            ConnectionResult.SERVICE_DISABLED -> loggy("Google Play services are disabled. P2P unavailable.")
            ConnectionResult.SERVICE_MISSING -> loggy("Google Play services are missing. P2P unavailable.")
            ConnectionResult.SERVICE_UPDATING -> loggy("Google Play services are updating. P2P unavailable.")
            ConnectionResult.SERVICE_VERSION_UPDATE_REQUIRED -> loggy("Google Play services require update. P2P unavailable.")
            ConnectionResult.SERVICE_INVALID -> loggy("Google Play services are invalid. P2P unavailable.")
        }
    }

    private fun startP2PAdvertising() {
        val advertisingOptions = AdvertisingOptions.Builder().setStrategy(Strategy.P2P_POINT_TO_POINT).build()
        ncClient.startAdvertising(ncAlias, ncServiceID, ncListener, advertisingOptions)
            .addOnSuccessListener {
                activity.toasty("Scanning for Gmix receiving peers...")
            }
            .addOnFailureListener { e ->
                activity.toasty("Unable to start P2P. Check the status of your WiFi, GPS and Bluetooth.")
                loggy(e.stackTraceToString())
            }
    }

    private fun startP2PDiscovery() {
        ncClient.startDiscovery(
            ncServiceID,
            object : EndpointDiscoveryCallback() {
                override fun onEndpointFound(endpointId: String, p1: DiscoveredEndpointInfo) {
                    val peer = P2pPeerAndroid(endpointId, p1.endpointName)
                    if (!viewmodel.p2pPeers.contains(peer)) {
                        viewmodel.p2pPeers.add(peer)
                    }
                }

                override fun onEndpointLost(p0: String) {
                    viewmodel.p2pPeers.firstOrNull { it.id == p0 }?.let {
                        viewmodel.p2pPeers.remove(it)
                    }
                }
            },
            DiscoveryOptions.Builder().setStrategy(Strategy.P2P_POINT_TO_POINT).build()
        )
            .addOnSuccessListener {
                activity.toasty("Scanning for Gmix sending peers...")
            }
            .addOnFailureListener { e ->
                activity.toasty("Unable to start P2P. Check the status of your WiFi, GPS and Bluetooth.")
                loggy(e.stackTraceToString())
            }
    }

    private fun stopAntennas() {
        ncClient.stopAdvertising()
        ncClient.stopDiscovery()
    }

    override fun connectNativePeer(peer: P2pPeer) {
        selectedPeer = peer
        ncClient.requestConnection(ncAlias, peer.id, ncListener)
            .addOnSuccessListener {}
            .addOnFailureListener { e ->
                loggy(e.stackTraceToString())
            }
    }

    private fun carryOnP2P() {
        /** Most important code here, we're finally connected */
        if (isP2Ptransferring) return

        isP2Ptransferring = true

        viewmodel.p2pChoosePeerPopup.value = false
        viewmodel.p2pTransferPopup.value = true //Show transfer window

        val isReceiving = viewmodel.userMode.value == true

        viewmodel.textPeer1.value = selectedPeer?.name ?: crossPeer
        viewmodel.textPeer2.value = "You (${Build.DEVICE})"

        viewmodel.transferStatusText.value = "Initiating connection..."

        GlobalScope.launch(Dispatchers.IO) {
            viewmodel.transferStatusText.value = "Initiating bridge..."

            try {
                //The group owner creates a server socket, while the non-owner creates a client socket
                viewmodel.transferStatusText.value = "Forming socket bridge..."

                while (p2pInput == null && p2pOutput == null) {
                    continue
                }

                viewmodel.transferStatusText.value = "Successfully opened socket. Exchanging info.."

                if (!isReceiving) {
                    sendPlaylist()
                } else {
                    receivePlaylist()
                }
            } catch (e: Exception) {
                loggy(e.stackTraceToString())
            }
        }
    }

    override suspend fun withinTempFolder(doThis: suspend P2pTempFolder.() -> Unit) {
        val tempy = DocumentFile.fromFile(activity.filesDir)
        tempy.listFiles().forEach { f ->
            f?.delete()
        }

        doThis.invoke(P2pTempFolder(tempy))
    }

    override fun promptSavePlaylist() {
        p2pReceptionResult.launch(null)
    }

    override fun songSource(uri: String, doLast: CompletableDeferred<() -> Unit>?): CompletableDeferred<Triple<RawSource, Long, String>> {
        val future = CompletableDeferred<Triple<RawSource, Long, String>>()
        GlobalScope.launch {
            val df = DocumentFile.fromSingleUri(activity, uri.toUri())
            if (df != null) {
                val source = activity.contentResolver.openInputStream(df.uri)?.asSource()
                if (source != null && df.name != null) {
                    future.complete(Triple(source, df.length(), df.name!!))
                } else future.completeExceptionally(Exception("Song source is invalid 1"))
            } else future.completeExceptionally(Exception("Song source is invalid 2"))
        }
        doLast?.complete {}
        return future
    }

    override fun songSink(tempName: String, scope: P2pTempFolder): CompletableDeferred<Pair<RawSink, P2pReceivedFile?>> {
        val future = CompletableDeferred<Pair<RawSink, P2pReceivedFile?>>()
        GlobalScope.launch {
            try {
                val product = scope.tempDirectory.createFile("audio/*", tempName) ?: throw Exception()
                val sink = activity.contentResolver.openOutputStream(product.uri)?.asSink() ?: throw Exception()
                future.complete(Pair(sink, P2pReceivedFile(tempName, product)))
            } catch (e: Exception) {
                future.completeExceptionally(Exception("Song sink is unwritable"))
            }
        }
        return future
    }

    override fun songRename(oldName: String, scope: P2pTempFolder, tempProduct: P2pReceivedFile, finalName: String): CompletableDeferred<P2pReceivedFile> {
        val future = CompletableDeferred<P2pReceivedFile>()
        GlobalScope.launch {
            tempProduct.file.renameTo(finalName)
            future.complete(tempProduct)
        }
        return future
    }

    override fun stopP2pOperations() {
        selectedPeer = null
        stopAntennas()
        ncClient.stopAllEndpoints()
        super.stopP2pOperations()
    }

    /**************** Cross-platform ********************/
    private fun carryOnP2PCross() {
        /** Most important code here, we're finally connected */
        isP2Ptransferring = true

        viewmodel.p2pQRpopup.value = false
        viewmodel.p2pTransferPopup.value = true

        val isReceiving = viewmodel.userMode.value == true

        GlobalScope.launch(Dispatchers.IO) {
            try {
                while (p2pInput == null && p2pOutput == null) { continue }

                viewmodel.transferStatusText.value = "Successfully opened socket. Exchanging info.."

                if (!isReceiving) {
                    sendPlaylist()
                } else {
                    receivePlaylist()
                }
            } catch (e: Exception) {
                loggy(e.stackTraceToString())
            }
        }
    }

    override fun startCrossPlatform() {
        p2pMode = P2pMode.CROSS_PLATFORM
    }

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
                    p2pInput = socket?.getInputStream()?.asSource()
                } else {
                    p2pOutput = socket?.getOutputStream()?.asSink()
                }

                //At this point we're connected by QR code on LAN
                carryOnP2PCross()

            } catch (e: Exception) {
                loggy(e.stackTraceToString())
                future.complete(Pair("", 0))
            }
        }
        return future
    }

}