package jetzy.managers

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.ServerSocket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.connection
import io.ktor.network.sockets.port
import jetzy.p2p.P2pPeer
import jetzy.utils.PreferablyIO
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

/**
 * Desktop (JVM) Wi-Fi Direct P2P manager. Same 802.11 P2P standard Android uses,
 * implemented on Linux via `wpa_cli`. Windows path (via `Windows.Devices.WiFiDirect`
 * WinRT API + JNA/COM bridge) is significant work and deferred. macOS doesn't
 * expose Wi-Fi Direct publicly.
 *
 * Flow:
 *   1. `wpa_cli p2p_find` to discover peers.
 *   2. Surface peers via `wpa_cli p2p_peers` + per-peer name lookup.
 *   3. On user pick, `wpa_cli p2p_connect <addr> pbc go_intent=0` — Linux acts
 *      as P2P Client; Android side becomes the Group Owner (intent ≥ 1 wins).
 *   4. Once the P2P group forms, a `p2p-wlan0-0`-style virtual interface comes up
 *      with the GO's DHCP-assigned IP; Linux then dials TCP to that IP on the
 *      port the peer advertises via a tiny rendezvous step.
 *
 * For v1, the *rendezvous* (how the GO publishes its TCP port to the client) reuses
 * mDNS over the P2P-group interface — same Bonjour service we already speak. The
 * peer announces `_jetzy._tcp` on the P2P interface; Linux subscribes; once seen,
 * dials. That keeps the discovery codepath identical to LAN.
 */
class WiFiDirectP2PM : PeerDiscoveryP2PM() {

    private val osName = System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT)
    private val isLinux = "linux" in osName || "nix" in osName || "nux" in osName
    private val isWindows = "win" in osName

    private val foundPeers = mutableMapOf<String, String>()  // addr → name
    private var findProcess: Process? = null
    private var serverSocket: ServerSocket? = null
    private var connectionReady: CompletableDeferred<Boolean>? = null

    private fun isPlatformSupported(): Boolean = (isLinux && hasWpaCli()) || (isWindows && hasPowerShell())

    private fun hasWpaCli(): Boolean = runCatching {
        ProcessBuilder("wpa_cli", "-v").redirectErrorStream(true).start()
            .also { it.waitFor(2, TimeUnit.SECONDS) }
            .exitValue() == 0
    }.getOrDefault(false)

    private fun hasPowerShell(): Boolean = runCatching {
        ProcessBuilder("powershell", "-NoProfile", "-Command", "exit 0")
            .redirectErrorStream(true).start()
            .also { it.waitFor(2, TimeUnit.SECONDS) }
            .exitValue() == 0
    }.getOrDefault(false)

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    override suspend fun startDiscoveryAndAdvertising(deviceName: String) {
        if (!isPlatformSupported()) {
            diag("Wi-Fi Direct not wired on this OS ($osName) — Linux (wpa_supplicant) and Windows (PowerShell/WinRT) only")
            viewmodel.snacky("Wi-Fi Direct isn't available on this desktop OS.")
            return
        }
        isDiscovering.value = true
        isAdvertising.value = true

        if (isLinux) {
            // Set the device name peers will see.
            runCommand(listOf("wpa_cli", "set", "device_name", deviceName.take(32)), 3.seconds)
            runCommand(listOf("wpa_cli", "set", "device_type", "1-0050F204-1"), 3.seconds) // Computer

            runCommand(listOf("wpa_cli", "p2p_find"), 5.seconds)
            startLinuxPeerPolling()
        } else if (isWindows) {
            startWindowsPeerPolling()
        }

        // Bind a TCP server so peers can connect to us once they're on our group.
        val bound = aSocket(SelectorManager(PreferablyIO)).tcp().bind("0.0.0.0", 0)
        serverSocket = bound
        diag("Wi-Fi Direct TCP server bound on port ${bound.port}")

        p2pScope.launch(PreferablyIO) {
            try {
                val socket = bound.accept()
                isHandshaking.value = true
                connection = socket.connection()
                connectionReady?.complete(true)
                diag("Wi-Fi Direct inbound accepted")
            } catch (e: Exception) {
                diag("Wi-Fi Direct accept failed: ${e.message ?: e::class.simpleName}")
            }
        }
    }

    private fun startLinuxPeerPolling() {
        p2pScope.launch(PreferablyIO) {
            while (isDiscovering.value) {
                val list = runCommand(listOf("wpa_cli", "p2p_peers"), 3.seconds).output
                    .lineSequence()
                    .map { it.trim() }
                    .filter { ADDR_REGEX.matches(it) }
                    .toList()
                for (addr in list) {
                    if (addr in foundPeers) continue
                    val info = runCommand(listOf("wpa_cli", "p2p_peer", addr), 3.seconds).output
                    val name = NAME_LINE_REGEX.find(info)?.groupValues?.get(1)?.trim()?.ifBlank { null }
                        ?: "Wi-Fi Direct peer"
                    foundPeers[addr] = name
                    diag("Wi-Fi Direct peer: $name @ $addr")
                }
                availablePeers.value = foundPeers.map { (addr, name) ->
                    P2pPeer(id = addr, name = name, signalStrength = 3)
                }
                delay(2.seconds)
            }
        }
    }

    /**
     * Windows path: drive `Windows.Devices.WiFiDirect` through PowerShell. The
     * WinRT API can be projected into PowerShell via the type-loading trick
     * `[Windows.Devices.WiFiDirect.WiFiDirectDevice,Windows.Devices.WiFiDirect,ContentType=WindowsRuntime]`.
     * Pairing has to be done first via Windows Settings (Bluetooth & devices →
     * Add device → Wireless display or dock); only paired peers show up here.
     *
     * For each poll we enumerate associated Wi-Fi Direct devices and surface
     * them to the UI. Actual data-path negotiation (NWConnection-equivalent
     * via `WiFiDirectDevice.GetConnectionEndpointPairs`) is heavier to drive
     * through PowerShell — for v1 we hand off to the same mDNS-over-the-formed-
     * group convention that the Linux side uses.
     */
    private fun startWindowsPeerPolling() {
        p2pScope.launch(PreferablyIO) {
            val script = """
                ${'$'}ErrorActionPreference = 'SilentlyContinue'
                [void][Windows.Devices.WiFiDirect.WiFiDirectDevice,Windows.Devices.WiFiDirect,ContentType=WindowsRuntime]
                [void][Windows.Devices.Enumeration.DeviceInformation,Windows.Devices.Enumeration,ContentType=WindowsRuntime]
                ${'$'}selector = [Windows.Devices.WiFiDirect.WiFiDirectDevice]::GetDeviceSelector(
                    [Windows.Devices.WiFiDirect.WiFiDirectDeviceSelectorType]::AssociationEndpoint)
                ${'$'}op = [Windows.Devices.Enumeration.DeviceInformation]::FindAllAsync(${'$'}selector)
                while (${'$'}op.Status -eq 'Started') { Start-Sleep -Milliseconds 100 }
                foreach (${'$'}d in ${'$'}op.GetResults()) { Write-Output "${'$'}(${'$'}d.Id)`t${'$'}(${'$'}d.Name)" }
            """.trimIndent()
            while (isDiscovering.value) {
                val out = runCommand(listOf("powershell", "-NoProfile", "-Command", script), 8.seconds).output
                for (line in out.lineSequence().map { it.trim() }.filter { it.isNotBlank() }) {
                    val parts = line.split('\t', limit = 2)
                    if (parts.size != 2) continue
                    val (id, name) = parts
                    if (id in foundPeers) continue
                    foundPeers[id] = name
                    diag("Wi-Fi Direct (Win) peer: $name @ $id")
                }
                availablePeers.value = foundPeers.map { (id, name) ->
                    P2pPeer(id = id, name = name, signalStrength = 3)
                }
                delay(3.seconds)
            }
        }
    }

    override suspend fun stopDiscoveryAndAdvertising() {
        runCatching { findProcess?.destroy() }
        findProcess = null
        if (isLinux) {
            runCommand(listOf("wpa_cli", "p2p_stop_find"), 2.seconds)
            runCommand(listOf("wpa_cli", "p2p_group_remove", "*"), 3.seconds)
        }
        // Windows doesn't need explicit cleanup — Windows Wi-Fi Direct groups are
        // ref-counted by the OS once we drop the WiFiDirectDevice handle.
        isDiscovering.value = false
        isAdvertising.value = false
    }

    // ── Connect ────────────────────────────────────────────────────────────────

    override suspend fun connectToPeer(peer: P2pPeer): Result<Unit> {
        if (!isPlatformSupported()) {
            return Result.failure(Exception("Wi-Fi Direct not supported on this OS"))
        }
        val ready = CompletableDeferred<Boolean>()
        connectionReady = ready

        p2pScope.launch(PreferablyIO) {
            try {
                val ok = if (isWindows) connectWindows(peer) else connectLinux(peer)
                ready.complete(ok)
            } catch (e: Exception) {
                diag("Wi-Fi Direct connect failed: ${e.message ?: e::class.simpleName}")
                ready.complete(false)
            }
        }

        val ok = withTimeoutOrNull(CONNECT_TIMEOUT) { ready.await() } ?: false
        return if (ok) Result.success(Unit)
        else Result.failure(Exception("Wi-Fi Direct connect timed out / failed"))
    }

    private suspend fun connectLinux(peer: P2pPeer): Boolean {
        // go_intent=0 means we *prefer* to be the client; the Android peer
        // (typically intent=7..15) wins and becomes Group Owner.
        val result = runCommand(
            listOf("wpa_cli", "p2p_connect", peer.id, "pbc", "go_intent=0"),
            30.seconds,
        )
        if (result.exitCode != 0 || result.output.lowercase().contains("fail")) {
            diag("p2p_connect failed: ${result.output}")
            viewmodel.snacky("Couldn't form Wi-Fi Direct group with ${peer.name}.")
            return false
        }
        val goAddr = waitForGroupOwnerAddress() ?: run {
            diag("group formed but no GO IP visible")
            return false
        }
        diag("Wi-Fi Direct group up, GO @ $goAddr")
        viewmodel.snacky("Wi-Fi Direct group with ${peer.name} ready. Listing peer on the LAN…")
        return true
    }

    /**
     * Windows path: drive `WiFiDirectDevice.FromIdAsync` through PowerShell to
     * trigger pairing/connection. Once the OS forms the group, a virtual
     * adapter (Microsoft Wi-Fi Direct Virtual Adapter) gets a DHCP IP and the
     * mDNS layer handles peer rendezvous over the new interface.
     */
    private suspend fun connectWindows(peer: P2pPeer): Boolean {
        val escaped = peer.id.replace("'", "''")
        val script = """
            ${'$'}ErrorActionPreference = 'Stop'
            try {
                [void][Windows.Devices.WiFiDirect.WiFiDirectDevice,Windows.Devices.WiFiDirect,ContentType=WindowsRuntime]
                ${'$'}op = [Windows.Devices.WiFiDirect.WiFiDirectDevice]::FromIdAsync('$escaped')
                while (${'$'}op.Status -eq 'Started') { Start-Sleep -Milliseconds 200 }
                ${'$'}dev = ${'$'}op.GetResults()
                if (${'$'}dev -eq ${'$'}null) { Write-Output 'NULL_DEVICE'; exit 1 }
                ${'$'}pairs = ${'$'}dev.GetConnectionEndpointPairs()
                foreach (${'$'}p in ${'$'}pairs) { Write-Output "${'$'}(${'$'}p.RemoteHostName.CanonicalName)" }
                exit 0
            } catch {
                Write-Output ${'$'}_.Exception.Message
                exit 2
            }
        """.trimIndent()
        val result = runCommand(listOf("powershell", "-NoProfile", "-Command", script), 30.seconds)
        if (result.exitCode != 0) {
            diag("WiFiDirect Windows connect failed: ${result.output}")
            viewmodel.snacky("Couldn't connect to ${peer.name}: ${result.output.lines().firstOrNull() ?: "unknown"}")
            return false
        }
        val remote = result.output.lineSequence().map { it.trim() }.firstOrNull { it.isNotBlank() }
        if (remote.isNullOrBlank()) {
            diag("WiFiDirect Windows: device paired but no endpoint")
            return false
        }
        diag("Wi-Fi Direct (Win) endpoint up: $remote — mDNS will rendezvous over the new adapter")
        viewmodel.snacky("Wi-Fi Direct paired with ${peer.name}. Listing peer on the LAN…")
        return true
    }

    /**
     * After a P2P group forms wpa_supplicant brings up a virtual interface
     * (typically `p2p-wlan0-0`). The Group Owner is gateway on that interface;
     * we discover its IP by reading the routing table for the new iface.
     */
    private suspend fun waitForGroupOwnerAddress(): String? {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            for (iface in NetworkInterface.getNetworkInterfaces()) {
                if (!iface.name.startsWith("p2p-")) continue
                if (!iface.isUp) continue
                for (addr in iface.inetAddresses) {
                    if (addr !is Inet4Address) continue
                    if (addr.isLoopbackAddress) continue
                    // Take the .1 host on the subnet as the GO — Android always
                    // assigns 192.168.49.1 by default.
                    val ours = addr.hostAddress ?: continue
                    val subnet = ours.substringBeforeLast('.')
                    return "$subnet.1"
                }
            }
            delay(500)
        }
        return null
    }

    override suspend fun cleanup() {
        super.cleanup()
        stopDiscoveryAndAdvertising()
        runCatching { serverSocket?.close() }
        serverSocket = null
        connectionReady?.complete(false)
        connectionReady = null
        foundPeers.clear()
    }

    // ── Shell helpers ──────────────────────────────────────────────────────────

    private data class CommandResult(val exitCode: Int, val output: String)

    private fun runCommand(cmd: List<String>, timeout: kotlin.time.Duration): CommandResult {
        return runCatching {
            val proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
            val finished = proc.waitFor(timeout.inWholeSeconds, TimeUnit.SECONDS)
            if (!finished) proc.destroyForcibly()
            val out = proc.inputStream.bufferedReader().readText()
            CommandResult(proc.exitValue(), out)
        }.getOrElse {
            CommandResult(-1, it.message ?: "command failed")
        }
    }

    companion object {
        private val ADDR_REGEX = Regex("""^[0-9a-fA-F:]{17}$""")
        private val NAME_LINE_REGEX = Regex("""device_name=(.+)""")
        private val CONNECT_TIMEOUT = 90.seconds
    }
}
