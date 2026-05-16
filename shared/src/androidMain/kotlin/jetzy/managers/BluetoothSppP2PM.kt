package jetzy.managers

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import jetzy.MainActivity
import jetzy.p2p.P2pPeer
import jetzy.permissions.PermissionRequirement
import jetzy.shared.generated.resources.Res
import jetzy.shared.generated.resources.perm_nearby_desc
import jetzy.shared.generated.resources.perm_nearby_title
import jetzy.utils.JETZY_BLUETOOTH_SPP_UUID
import jetzy.utils.PreferablyIO
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

/**
 * Bluetooth Classic SPP (Serial Port Profile / RFCOMM) P2P manager. Fallback
 * transport when Wi-Fi-based options aren't available. Slow (~2 Mbps practical)
 * but works in scenarios where every Wi-Fi path fails (faraday cage, no Wi-Fi,
 * networks blocking multicast).
 *
 * Discovery: enumerate already-paired devices via [BluetoothAdapter.bondedDevices]
 * + listen for unpaired-device discovery via `ACTION_FOUND`. We surface BOTH so the
 * user can pick paired-without-friction OR a newly-discovered peer (which will
 * trigger the system pairing dialog on connect).
 *
 * Transport: open a server socket via `listenUsingRfcommWithServiceRecord` on
 * our custom UUID; client opens `BluetoothSocket` with the same UUID and dials.
 * Once connected, bridge socket I/O streams to Ktor `ByteChannel`s — same shape
 * MPC + Wi-Fi Aware already use.
 */
class BluetoothSppP2PM(private val context: Context) : PeerDiscoveryP2PM() {

    private val appContext = context.applicationContext
    private val bluetoothManager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter = bluetoothManager?.adapter

    private var serverJob: Job? = null
    private var serverSocket: BluetoothServerSocket? = null
    private var discoveryReceiver: BroadcastReceiver? = null

    private val foundDevices = mutableMapOf<String, BluetoothDevice>()
    private var connectionReady: CompletableDeferred<Boolean>? = null
    private val bridgeJobs = mutableListOf<Job>()

    private val serviceUuid: UUID = UUID.fromString(JETZY_BLUETOOTH_SPP_UUID)

    override val permissionRequirements: List<PermissionRequirement>
        get() {
            val activity = context as? MainActivity ?: return emptyList()
            return listOf(
                bluetoothConnectPermission(activity),
                bluetoothScanPermission(activity),
            )
        }

    private fun bluetoothConnectPermission(activity: MainActivity): PermissionRequirement {
        val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Manifest.permission.BLUETOOTH_CONNECT
        } else null
        return PermissionRequirement(
            id = "bluetooth_connect",
            titleRes = Res.string.perm_nearby_title,
            descriptionRes = Res.string.perm_nearby_desc,
            kind = PermissionRequirement.Kind.RUNTIME_PERMISSION,
            isGrantedNow = {
                perm == null || ContextCompat.checkSelfPermission(activity, perm) == PackageManager.PERMISSION_GRANTED
            },
            request = { perm?.let { activity.requestRuntimePermissions(arrayOf(it)) } },
        )
    }

    private fun bluetoothScanPermission(activity: MainActivity): PermissionRequirement {
        val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Manifest.permission.BLUETOOTH_SCAN
        } else null
        return PermissionRequirement(
            id = "bluetooth_scan",
            titleRes = Res.string.perm_nearby_title,
            descriptionRes = Res.string.perm_nearby_desc,
            kind = PermissionRequirement.Kind.RUNTIME_PERMISSION,
            isGrantedNow = {
                perm == null || ContextCompat.checkSelfPermission(activity, perm) == PackageManager.PERMISSION_GRANTED
            },
            request = { perm?.let { activity.requestRuntimePermissions(arrayOf(it)) } },
        )
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    override suspend fun startDiscoveryAndAdvertising(deviceName: String) {
        val adapter = adapter ?: run {
            diag("no BluetoothAdapter")
            viewmodel.snacky("This device has no Bluetooth.")
            return
        }
        if (!adapter.isEnabled) {
            diag("Bluetooth disabled")
            viewmodel.snacky("Turn on Bluetooth to use this transport.")
            return
        }

        isDiscovering.value = true
        isAdvertising.value = true

        // Surface already-paired devices immediately.
        for (device in adapter.bondedDevices.orEmpty()) {
            registerDevice(device)
        }

        // Start a background discovery scan for unpaired peers.
        registerDiscoveryReceiver()
        adapter.startDiscovery()

        // Start the RFCOMM server.
        serverJob = p2pScope.launch(PreferablyIO) {
            try {
                val ss = adapter.listenUsingRfcommWithServiceRecord(SDP_SERVICE_NAME, serviceUuid)
                serverSocket = ss
                diag("Bluetooth SPP server listening")
                val accepted = ss.accept()  // blocks
                ss.close()
                bridgeBluetoothSocket(accepted)
            } catch (e: Exception) {
                diag("BT accept failed: ${e.message ?: e::class.simpleName}")
            }
        }
    }

    @SuppressLint("MissingPermission")
    override suspend fun stopDiscoveryAndAdvertising() {
        runCatching { adapter?.cancelDiscovery() }
        discoveryReceiver?.let {
            runCatching { appContext.unregisterReceiver(it) }
        }
        discoveryReceiver = null
        runCatching { serverSocket?.close() }
        serverSocket = null
        serverJob?.cancel()
        serverJob = null
        isDiscovering.value = false
        isAdvertising.value = false
    }

    @SuppressLint("MissingPermission")
    private fun registerDevice(device: BluetoothDevice) {
        val id = device.address ?: return
        foundDevices[id] = device
        val nameSafe = try { device.name } catch (_: SecurityException) { null }
            ?: "Bluetooth device"
        availablePeers.value = foundDevices.values.map {
            val n = try { it.name } catch (_: SecurityException) { null } ?: "Bluetooth device"
            P2pPeer(id = it.address ?: "?", name = n, signalStrength = 2)
        }
        diag("BT peer: $nameSafe @ $id")
    }

    private fun registerDiscoveryReceiver() {
        val filter = IntentFilter(BluetoothDevice.ACTION_FOUND)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != BluetoothDevice.ACTION_FOUND) return
                @Suppress("DEPRECATION")
                val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                    ?: return
                registerDevice(device)
            }
        }
        // Receiver registration flag differs on API 33+; we don't take broadcasts from external apps.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            appContext.registerReceiver(receiver, filter)
        }
        discoveryReceiver = receiver
    }

    // ── Connect (client side) ──────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    override suspend fun connectToPeer(peer: P2pPeer): Result<Unit> {
        val device = foundDevices[peer.id]
            ?: return Result.failure(Exception("peer not found: ${peer.id}"))

        val ready = CompletableDeferred<Boolean>()
        connectionReady = ready

        p2pScope.launch(PreferablyIO) {
            try {
                // Stop discovery to free the radio for connect.
                adapter?.cancelDiscovery()
                val socket = device.createRfcommSocketToServiceRecord(serviceUuid)
                socket.connect()  // blocking; may trigger system pairing dialog
                diag("BT outbound connected to ${peer.name}")
                bridgeBluetoothSocket(socket)
                ready.complete(true)
            } catch (e: Exception) {
                diag("BT connect failed: ${e.message ?: e::class.simpleName}")
                viewmodel.snacky("Couldn't reach ${peer.name} over Bluetooth: ${e.message ?: "unknown"}")
                ready.complete(false)
            }
        }

        val ok = withTimeoutOrNull(CONNECT_TIMEOUT) { ready.await() } ?: false
        return if (ok) Result.success(Unit)
        else Result.failure(Exception("BT connect timed out / failed"))
    }

    // ── Stream bridging ────────────────────────────────────────────────────────

    /**
     * Bridge a connected RFCOMM [BluetoothSocket] to Ktor ByteChannels — same
     * pattern as MpcP2PM uses for NSStream and WifiAwareP2PM uses for the
     * Swift bridge's NSStream pair.
     */
    private fun bridgeBluetoothSocket(socket: BluetoothSocket) {
        isHandshaking.value = true
        val read = ByteChannel()
        val write = ByteChannel()

        bridgeJobs += p2pScope.launch(PreferablyIO) {
            val input = socket.inputStream
            val buf = ByteArray(bufferSize)
            try {
                while (true) {
                    val n = input.read(buf)
                    if (n == -1) break
                    read.writeFully(buf, 0, n)
                    read.flush()
                }
            } catch (e: Exception) {
                diag("BT read pump error: ${e.message}")
            } finally {
                read.close()
            }
        }

        bridgeJobs += p2pScope.launch(PreferablyIO) {
            val output = socket.outputStream
            val buf = ByteArray(bufferSize)
            try {
                while (!write.isClosedForRead) {
                    val n = write.readAvailable(buf)
                    if (n <= 0) {
                        if (write.isClosedForRead) break
                        continue
                    }
                    output.write(buf, 0, n)
                    output.flush()
                }
            } catch (e: Exception) {
                diag("BT write pump error: ${e.message}")
            } finally {
                runCatching { output.close() }
                runCatching { socket.close() }
            }
        }

        startTransferWithChannels(input = read, output = write)
    }

    override suspend fun cleanup() {
        super.cleanup()
        stopDiscoveryAndAdvertising()
        bridgeJobs.forEach { runCatching { it.cancel() } }
        bridgeJobs.clear()
        connectionReady?.complete(false)
        connectionReady = null
        foundDevices.clear()
    }

    companion object {
        private const val SDP_SERVICE_NAME = "Jetzy"
        private val CONNECT_TIMEOUT = 30.seconds
    }
}
