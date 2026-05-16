package jetzy.managers

import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import jetzy.p2p.P2pPeer
import jetzy.utils.PreferablyIO
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.RandomAccessFile
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

/**
 * Desktop (JVM) Bluetooth Classic SPP P2P manager.
 *
 * **Linux**: We shell out to BlueZ's `bluetoothctl`/`rfcomm` user-space tools.
 * The pattern is:
 *   1. `bluetoothctl devices` to enumerate paired devices.
 *   2. `bluetoothctl scan on` for discovery, parse `[NEW] Device <addr> <name>`.
 *   3. `rfcomm bind 0 <addr> <channel>` to map the peer to /dev/rfcomm0.
 *   4. Read/write `/dev/rfcomm0` as a regular file — that's the RFCOMM stream.
 *
 * **macOS**: IOBluetooth APIs reachable from JVM only via JNA/Rococoa — large
 * undertaking, deferred. Reports as unavailable.
 *
 * **Windows**: Native BT stack doesn't surface SPP server profiles to user
 * code without WinRT (32feet.NET in the .NET world). Deferred. Reports unavailable.
 *
 * Whichever OS we're on, [isPlatformSupported] gates discovery start — if false,
 * the manager surfaces a clear "not supported here" message and the transport
 * selector falls through.
 */
class BluetoothSppP2PM : PeerDiscoveryP2PM() {

    private val osName = System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT)
    private val isLinux = "linux" in osName || "nix" in osName || "nux" in osName
    private val isWindows = "win" in osName
    private val isMac = "mac" in osName || "darwin" in osName

    private var scanProcess: Process? = null
    private var serverJob: Job? = null
    private val foundDevices = mutableMapOf<String, String>()  // addr → name
    private val rfcommBound = mutableSetOf<Int>()
    private var connectionReady: CompletableDeferred<Boolean>? = null
    private val bridgeJobs = mutableListOf<Job>()

    /** Full read+write transport (Linux only for now). */
    private fun isFullySupported(): Boolean = isLinux && hasBluez()

    /** Device enumeration support (Linux + Windows + macOS). Connection may still fail. */
    private fun supportsDiscoveryOnly(): Boolean = isWindows || isMac

    private fun hasBluez(): Boolean = runCatching {
        ProcessBuilder("bluetoothctl", "--version").redirectErrorStream(true).start()
            .also { it.waitFor() }
            .exitValue() == 0
    }.getOrDefault(false)

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    override suspend fun startDiscoveryAndAdvertising(deviceName: String) {
        if (!isFullySupported() && !supportsDiscoveryOnly()) {
            diag("Bluetooth SPP isn't wired on this OS ($osName)")
            viewmodel.snacky("Bluetooth Classic isn't available on this desktop OS.")
            return
        }
        isDiscovering.value = true
        isAdvertising.value = true

        if (isLinux) {
            // Seed list with already-paired devices.
            readBluetoothctl(listOf("devices", "Paired")).forEach { (addr, name) ->
                foundDevices[addr] = name
            }
        } else if (isWindows) {
            readWindowsPairedDevices().forEach { (addr, name) -> foundDevices[addr] = name }
        } else if (isMac) {
            readMacPairedDevices().forEach { (addr, name) -> foundDevices[addr] = name }
        }
        publishPeers()

        // Live discovery scan is Linux-only for now (bluetoothctl scan on). On
        // Windows/macOS we surface paired devices only — the OS's own Settings
        // app is the place to pair new ones.
        if (isLinux) startScan()

        // Server side: `rfcomm listen 0 1` listens for incoming connections on
        // channel 1, creating /dev/rfcomm0 when a peer dials in. We watch for
        // the device-node appearing and then bridge its I/O streams.
        serverJob = p2pScope.launch(PreferablyIO) { runRfcommListener() }
    }

    private suspend fun runRfcommListener() {
        val channel = 0
        val rfcommDev = File("/dev/rfcomm$channel")
        val proc = runCatching {
            ProcessBuilder("rfcomm", "listen", channel.toString(), "1")
                .redirectErrorStream(true).start()
        }.getOrElse {
            diag("`rfcomm listen` failed — typically needs sudo or the user in the bluetooth group")
            viewmodel.snacky("Couldn't open RFCOMM server. Try `sudo rfcomm listen` permission.")
            return
        }

        try {
            // The rfcomm listen process blocks until a connection arrives. Poll for
            // the device node to settle, then bridge.
            val deadline = System.currentTimeMillis() + 300_000  // 5 min wait
            while (System.currentTimeMillis() < deadline) {
                if (rfcommDev.exists()) {
                    diag("RFCOMM listener: incoming connection on $rfcommDev")
                    isHandshaking.value = true
                    bridgeRfcommNode(rfcommDev)
                    break
                }
                delay(500)
            }
        } finally {
            runCatching { proc.destroy() }
        }
    }

    override suspend fun stopDiscoveryAndAdvertising() {
        runCatching { scanProcess?.destroy() }
        scanProcess = null
        rfcommBound.toList().forEach { releaseRfcomm(it) }
        rfcommBound.clear()
        isDiscovering.value = false
        isAdvertising.value = false
    }

    private fun startScan() {
        val pb = ProcessBuilder("bluetoothctl", "scan", "on").redirectErrorStream(true)
        val proc = runCatching { pb.start() }.getOrNull() ?: run {
            diag("couldn't start bluetoothctl scan")
            return
        }
        scanProcess = proc
        p2pScope.launch(PreferablyIO) {
            proc.inputStream.bufferedReader().use { r ->
                while (true) {
                    val line = r.readLine() ?: break
                    // [NEW] Device AA:BB:CC:DD:EE:FF Some Phone
                    val match = NEW_DEVICE_REGEX.find(line) ?: continue
                    val addr = match.groupValues[1]
                    val name = match.groupValues[2].ifBlank { "Bluetooth device" }
                    foundDevices[addr] = name
                    publishPeers()
                    diag("BT discovered $name @ $addr")
                }
            }
        }
    }

    private fun publishPeers() {
        availablePeers.value = foundDevices.map { (addr, name) ->
            P2pPeer(id = addr, name = name, signalStrength = 2)
        }
    }

    // ── Connect (dial) ─────────────────────────────────────────────────────────

    override suspend fun connectToPeer(peer: P2pPeer): Result<Unit> {
        if (!isFullySupported()) {
            // Discovery worked, connection doesn't — explain clearly.
            viewmodel.snacky(
                "Bluetooth Classic connection on $osName needs a native helper that's not bundled yet. " +
                        "Use Wi-Fi Direct or LAN instead."
            )
            return Result.failure(Exception("Bluetooth Classic connect not supported on this OS"))
        }
        val addr = peer.id
        val ready = CompletableDeferred<Boolean>()
        connectionReady = ready

        p2pScope.launch(PreferablyIO) {
            try {
                // bluetoothctl pair + connect (idempotent if already paired).
                runCommand(listOf("bluetoothctl", "pair", addr), 30.seconds)
                runCommand(listOf("bluetoothctl", "trust", addr), 5.seconds)
                runCommand(listOf("bluetoothctl", "connect", addr), 15.seconds)

                // Bind /dev/rfcomm<channel> to the peer. Use channel 0 (might need
                // sudo on most distros — surface that in the diag if it fails).
                val channel = 0
                val rfcommDev = "/dev/rfcomm$channel"
                val bindResult = runCommand(
                    listOf("rfcomm", "bind", channel.toString(), addr, "1"),
                    10.seconds,
                )
                if (bindResult.exitCode != 0) {
                    diag("`rfcomm bind` failed (likely needs sudo): ${bindResult.output}")
                    viewmodel.snacky("Couldn't bind /dev/rfcomm$channel — try `sudo rfcomm bind` first.")
                    ready.complete(false)
                    return@launch
                }
                rfcommBound += channel
                // Settle delay so the device node appears.
                delay(500)

                val file = File(rfcommDev)
                if (!file.exists()) {
                    diag("$rfcommDev didn't appear")
                    ready.complete(false)
                    return@launch
                }

                isHandshaking.value = true
                bridgeRfcommNode(file)
                ready.complete(true)
            } catch (e: Exception) {
                diag("BT connect failed: ${e.message ?: e::class.simpleName}")
                viewmodel.snacky("Couldn't reach ${peer.name}: ${e.message ?: "unknown"}")
                ready.complete(false)
            }
        }

        val ok = withTimeoutOrNull(CONNECT_TIMEOUT) { ready.await() } ?: false
        return if (ok) Result.success(Unit)
        else Result.failure(Exception("BT connect timed out / failed"))
    }

    private fun bridgeRfcommNode(file: File) {
        // Read/write the rfcomm device as a regular file. RandomAccessFile lets us
        // open the same handle for both directions; otherwise we'd race on the FD.
        val raf = RandomAccessFile(file, "rw")
        val read = ByteChannel()
        val write = ByteChannel()

        bridgeJobs += p2pScope.launch(PreferablyIO) {
            val buf = ByteArray(bufferSize)
            try {
                while (true) {
                    val n = raf.read(buf)
                    if (n == -1) break
                    read.writeFully(buf, 0, n)
                    read.flush()
                }
            } catch (e: Exception) {
                diag("BT/rfcomm read pump error: ${e.message}")
            } finally {
                read.close()
            }
        }

        bridgeJobs += p2pScope.launch(PreferablyIO) {
            val buf = ByteArray(bufferSize)
            try {
                while (!write.isClosedForRead) {
                    val n = write.readAvailable(buf)
                    if (n <= 0) {
                        if (write.isClosedForRead) break
                        continue
                    }
                    raf.write(buf, 0, n)
                }
            } catch (e: Exception) {
                diag("BT/rfcomm write pump error: ${e.message}")
            } finally {
                runCatching { raf.close() }
            }
        }

        startTransferWithChannels(input = read, output = write)
    }

    private fun releaseRfcomm(channel: Int) {
        runCatching {
            ProcessBuilder("rfcomm", "release", channel.toString())
                .redirectErrorStream(true)
                .start()
                .waitFor()
        }
    }

    override suspend fun cleanup() {
        super.cleanup()
        stopDiscoveryAndAdvertising()
        serverJob?.cancel()
        bridgeJobs.forEach { runCatching { it.cancel() } }
        bridgeJobs.clear()
        connectionReady?.complete(false)
        connectionReady = null
        foundDevices.clear()
    }

    // ── Shell helpers ──────────────────────────────────────────────────────────

    private data class CommandResult(val exitCode: Int, val output: String)

    private fun runCommand(cmd: List<String>, timeout: kotlin.time.Duration): CommandResult {
        return runCatching {
            val proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
            val finished = proc.waitFor(timeout.inWholeSeconds, java.util.concurrent.TimeUnit.SECONDS)
            if (!finished) proc.destroyForcibly()
            val out = proc.inputStream.bufferedReader().readText()
            CommandResult(proc.exitValue(), out)
        }.getOrElse {
            CommandResult(-1, it.message ?: "command failed")
        }
    }

    /** Parse `bluetoothctl devices [Paired|...]` output into addr→name pairs. */
    private fun readBluetoothctl(args: List<String>): List<Pair<String, String>> {
        val out = runCommand(listOf("bluetoothctl") + args, 5.seconds).output
        return out.lineSequence().mapNotNull { line ->
            val m = DEVICES_LINE_REGEX.find(line) ?: return@mapNotNull null
            m.groupValues[1] to m.groupValues[2]
        }.toList()
    }

    /**
     * Windows: enumerate paired Bluetooth devices via PowerShell. We extract the
     * device's friendly name and a Bluetooth address derived from the InstanceId,
     * which has the form `BTHENUM\Dev_AABBCCDDEEFF\...`. The address is stable
     * across reboots and is what RFCOMM connection APIs need.
     */
    private fun readWindowsPairedDevices(): List<Pair<String, String>> {
        val script = """
            ${'$'}ErrorActionPreference = 'SilentlyContinue'
            Get-PnpDevice -Class Bluetooth -PresentOnly |
              Where-Object { ${'$'}_.Status -eq 'OK' -and ${'$'}_.InstanceId -match 'Dev_([0-9A-Fa-f]{12})' } |
              ForEach-Object {
                ${'$'}addr = ${'$'}_.InstanceId -replace '.*Dev_([0-9A-Fa-f]{12}).*', '${'$'}1'
                ${'$'}formatted = (${'$'}addr -split '(.{2})' -ne '') -join ':'
                Write-Output "${'$'}formatted`t${'$'}(${'$'}_.FriendlyName)"
              }
        """.trimIndent()
        val out = runCommand(listOf("powershell", "-NoProfile", "-Command", script), 5.seconds).output
        return out.lineSequence().mapNotNull { line ->
            val parts = line.trim().split('\t', limit = 2)
            if (parts.size != 2 || parts[0].length != 17) return@mapNotNull null
            parts[0].uppercase() to parts[1]
        }.toList()
    }

    /**
     * macOS: `system_profiler SPBluetoothDataType` dumps paired devices. The
     * output is multi-line YAML-ish; we grep for the `Address:` and the
     * preceding device-name lines. Approximate but enough for the UI list.
     */
    private fun readMacPairedDevices(): List<Pair<String, String>> {
        val out = runCommand(listOf("system_profiler", "SPBluetoothDataType"), 5.seconds).output
        val result = mutableListOf<Pair<String, String>>()
        var pendingName: String? = null
        for (raw in out.lineSequence()) {
            val line = raw.trim()
            if (line.endsWith(":") && !line.contains("Address") && !line.contains(":")) {
                pendingName = line.removeSuffix(":")
            } else if (line.startsWith("Address:")) {
                val addr = line.removePrefix("Address:").trim().uppercase()
                if (addr.length == 17 && pendingName != null) {
                    result += (addr to pendingName!!)
                    pendingName = null
                }
            }
        }
        return result
    }

    companion object {
        private val NEW_DEVICE_REGEX = Regex("""\[NEW\]\s+Device\s+([0-9A-F:]{17})\s+(.+)""", RegexOption.IGNORE_CASE)
        private val DEVICES_LINE_REGEX = Regex("""Device\s+([0-9A-F:]{17})\s+(.+)""", RegexOption.IGNORE_CASE)
        private val CONNECT_TIMEOUT = 60.seconds
    }
}
