package jetzy.managers

import io.ktor.network.sockets.Connection
import io.ktor.utils.io.cancel
import io.ktor.utils.io.close
import io.ktor.utils.io.readByte
import io.ktor.utils.io.readFully
import io.ktor.utils.io.readInt
import io.ktor.utils.io.readLong
import io.ktor.utils.io.writeByte
import io.ktor.utils.io.writeFully
import io.ktor.utils.io.writeInt
import io.ktor.utils.io.writeLong
import jetzy.models.JetzyElement
import jetzy.p2p.P2pDiscoveryMode
import kotlinx.io.buffered

/**
 * Manager that supports QR discovery by scanning or showing a QR code
 * Great for LAN/Hotspot technologies
 */
abstract class QRDiscoveryP2PM : P2PManager() {

    //TODO Observe socket state in order to keep UI state fresh and up-to-date

    override val discoveryMode: P2pDiscoveryMode = P2pDiscoveryMode.QRCode

    var connection: Connection? = null

    override suspend fun sendFiles(files: List<JetzyElement>) {
        val conn = connection ?: return
        try {
            val output = conn.output
            val input  = conn.input

            files.forEachIndexed { index, file ->
                val nameBytes = file.name.encodeToByteArray()
                output.writeInt(nameBytes.size)
                output.writeFully(nameBytes)
                output.writeLong(file.size())

                val src = file.source.buffered()
                val buf = ByteArray(64 * 1024)
                try {
                    while (true) {
                        val read = src.readAtMostTo(buf)
                        if (read == -1) break
                        output.writeFully(buf, 0, read)
                    }
                } finally {
                    src.close()
                }

                output.flush()
                input.readByte()

                transferProgress.value = (index + 1f) / files.size
                transferStatus.value = "Sent ${index + 1} of ${files.size}: ${file.name}"
            }

            output.writeInt(0)
            output.flush()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun receiveFiles() {
        val conn = connection ?: return
        try {
            val input  = conn.input
            val output = conn.output
            val received = mutableListOf<JetzyElement>()

            while (true) {
                val nameLen = input.readInt()
                if (nameLen == 0) break // sender signalled done

                val nameBytes = ByteArray(nameLen)
                input.readFully(nameBytes)
                val name = nameBytes.decodeToString()

                val fileSize = input.readLong()
                val fileBytes = ByteArray(fileSize.toInt()) // fine for files < 2GB
                var bytesRead = 0L
                val buf = ByteArray(64 * 1024)

                while (bytesRead < fileSize) {
                    val toRead = minOf(buf.size.toLong(), fileSize - bytesRead).toInt()
                    input.readFully(buf, 0, toRead)
                    buf.copyInto(fileBytes, bytesRead.toInt(), 0, toRead)
                    bytesRead += toRead
                    transferProgress.value = bytesRead.toFloat() / fileSize
                }

                // ack so sender moves to next file
                output.writeByte(1)
                output.flush()

                transferStatus.value = "Received: $name"
                // received.add(JetzyElement from fileBytes + name) — depends on your JetzyElement API
            }

            Result.success(received)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun cleanup() {
        connection?.let {
            it.input.cancel()
            it.output.close()
        }
        connection = null
        isConnected.value = false
    }
}