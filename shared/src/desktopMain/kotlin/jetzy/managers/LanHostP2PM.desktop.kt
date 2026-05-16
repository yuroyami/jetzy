package jetzy.managers

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.ServerSocket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.connection
import io.ktor.network.sockets.port
import jetzy.models.QRData
import jetzy.utils.PreferablyIO
import jetzy.utils.getDeviceName
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Desktop host-side counterpart of Android's [jetzy.managers.HotspotP2PM]. A PC
 * cannot reliably provision a Wi-Fi hotspot from JVM (Windows `netsh wlan`,
 * macOS Internet Sharing, and Linux `nmcli` are all platform/permission specific
 * and out of scope here), so the host mode assumes the user is already on a
 * Wi-Fi or wired LAN that the peer can reach.
 *
 * Behaviour mirrors HotspotP2PM minus the hotspot provisioning:
 *  - Picks the first usable LAN IPv4 address.
 *  - Binds a TCP server on an ephemeral port.
 *  - Returns a [QRData] with the SSID left blank (we don't own one) and
 *    the IP + port populated so the peer can connect directly.
 *
 * Used when peerPlatform = PC (PC↔PC) or when the desktop user wants to be the
 * receiver of a same-LAN Android peer's transfer.
 */
class LanHostP2PM : P2PManager() {

    override val usesPeerDiscovery: Boolean = false

    private var serverSocket: ServerSocket? = null

    @OptIn(ExperimentalUuidApi::class)
    fun establishTcpServer(): Deferred<QRData?> = p2pScope.async(PreferablyIO) {
        try {
            runCatching { serverSocket?.close() }
            serverSocket = null

            val localAddress = pickLocalIPv4() ?: run {
                diag("no LAN IPv4 address found")
                viewmodel.snacky("Couldn't find a LAN address. Connect to Wi-Fi or Ethernet first.")
                return@async null
            }
            diag("LAN IP: $localAddress")

            val bound = aSocket(SelectorManager(PreferablyIO))
                .tcp()
                .bind("0.0.0.0", 0)
            serverSocket = bound
            diag("server socket bound on port ${bound.port}")

            val session = sessionId.value ?: Uuid.random().toString().also { sessionId.value = it }

            p2pScope.launch(PreferablyIO) {
                try {
                    diag("waiting for peer to connect…")
                    val socket = bound.accept()
                    isHandshaking.value = true
                    connection = socket.connection()
                    diag("peer connected")
                } catch (e: Exception) {
                    diag("accept failed: ${e.message ?: e::class.simpleName}")
                }
            }

            QRData(
                hotspotSSID = "",                      // PC doesn't host a hotspot
                hotspotPassword = "",                  // ditto
                ipAddress = localAddress,
                port = bound.port,
                deviceName = getDeviceName(),
                sessionId = session,
            )
        } catch (e: Exception) {
            diag("server start failed: ${e.message ?: e::class.simpleName}")
            viewmodel.snacky("Couldn't start server: ${e.message ?: "unknown error"}")
            null
        }
    }

    /**
     * Pick a non-loopback site-local IPv4 (192.168.x, 10.x, 172.16-31.x). If
     * the host has multiple LAN NICs we just take the first one — good enough
     * for a peer that's manually scanning the QR.
     */
    private fun pickLocalIPv4(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress && it.isSiteLocalAddress }
            ?.hostAddress
    }.getOrNull()

    override suspend fun cleanup() {
        runCatching { serverSocket?.close() }
        serverSocket = null
        super.cleanup()
    }
}
