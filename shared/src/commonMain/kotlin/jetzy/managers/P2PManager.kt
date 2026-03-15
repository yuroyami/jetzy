package jetzy.managers

import androidx.annotation.CallSuper
import io.ktor.network.sockets.Connection
import io.ktor.utils.io.cancel
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
import jetzy.p2p.P2pIoApi
import jetzy.p2p.P2pOperation
import jetzy.p2p.P2pPlatformCallback
import jetzy.ui.Screen
import jetzy.ui.transfer.TransferScreenState
import jetzy.utils.PreferablyIO
import jetzy.utils.loggy
import jetzy.viewmodel.JetzyViewmodel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.io.buffered
import kotlin.math.round

/**
 * Base interface for all P2P transfer methods
 */
abstract class P2PManager {

    lateinit var viewmodel: JetzyViewmodel

    private val coroutineSupervisor = SupervisorJob()
    protected val coroutineScope = CoroutineScope(PreferablyIO + coroutineSupervisor)

    var connection: Connection? = null

    companion object {
        lateinit var platformCallback: P2pPlatformCallback
    }

    val transferProgress = MutableStateFlow(0f)
    val transferSpeed = MutableStateFlow(0L) //in bytes per second
    val transferStatus = MutableStateFlow("")
    val isConnected  = MutableStateFlow(false)

    abstract val discoveryMode: P2pDiscoveryMode

    open val requiredPermissions: List<String> = listOf()

    /**
     * Initialize the manager and prepare for connections
     */
    @CallSuper
    open fun initialize(viewmodel: JetzyViewmodel) {
        this.viewmodel = viewmodel
        platformCallback.ensurePermissions(requiredPermissions)
    }

    @P2pIoApi
    fun beginTransfer() {
        viewmodel.navigateTo(
            Screen.TransferScreen, noWayToReturn = true
        )

        viewmodel.transferState.value = TransferScreenState(
            senderName = "EdgyBoi",
            receiverName = "CoolGuy",
            completedCount = 0,
            totalCount = 1,
            isSender = viewmodel.currentOperation.value == P2pOperation.SEND
        )

        coroutineScope.launch {
            when (viewmodel.currentOperation.value) {
                P2pOperation.SEND -> sendFiles(viewmodel.elementsToSend)
                P2pOperation.RECEIVE -> receiveFiles()
                else -> throw Exception("What are we trying to do here?")
            }
        }
    }

    /**
     * Clean up resources and disconnect
     */
    @CallSuper
    open suspend fun cleanup() {
        connection?.let {
            it.input.cancel()
            it.output.flushAndClose()
        }
        connection = null
        isConnected.value = false
    }

    suspend fun sendFiles(files: List<JetzyElement>) {
        val conn = connection ?: run {
            loggy("📭 sendFiles() called but connection is null — aborting")
            return
        }
        loggy("🚀 Starting transfer of ${files.size} file(s): ${files.map { it.name }}")
        try {
            val output = conn.output
            val input  = conn.input

            files.forEachIndexed { index, file ->
                val fileSize = file.size()
                loggy("📦 [${ index + 1}/${files.size}] Preparing '${file.name}' (${fileSize.toHumanSize()})")

                val nameBytes = file.name.encodeToByteArray()
                output.writeInt(nameBytes.size)
                output.writeFully(nameBytes)
                output.writeLong(fileSize)

                loggy("📤 [${ index + 1}/${files.size}] Streaming '${file.name}'...")

                val src = file.source.buffered()
                val buf = ByteArray(64 * 1024)
                var bytesSent = 0L
                try {
                    while (true) {
                        val read = src.readAtMostTo(buf)
                        if (read == -1) break
                        output.writeFully(buf, 0, read)
                        bytesSent += read
                        val pct = (bytesSent * 100f / fileSize).toInt()
                        loggy("   ↑ ${bytesSent.toHumanSize()} / ${fileSize.toHumanSize()} ($pct%)")
                    }
                } finally {
                    src.close()
                }

                output.flush()
                loggy("⏳ [${ index + 1}/${files.size}] Waiting for ack from receiver...")
                input.readByte()
                loggy("✅ [${ index + 1}/${files.size}] '${file.name}' confirmed by receiver")

                transferProgress.value = (index + 1f) / files.size
                transferStatus.value = "Sent ${index + 1} of ${files.size}: ${file.name}"
            }

            output.writeInt(0)
            output.flush()
            loggy("🏁 All ${files.size} file(s) sent successfully — end signal written")
            Result.success(Unit)
        } catch (e: Exception) {
            loggy("💥 sendFiles() crashed: ${e.stackTraceToString()}")
            Result.failure(e)
        }
    }

    suspend fun receiveFiles() {
        val conn = connection ?: run {
            loggy("📭 receiveFiles() called but connection is null — aborting")
            return
        }
        loggy("📡 Waiting to receive files from peer...")
        try {
            val input   = conn.input
            val output  = conn.output
            val received = mutableListOf<JetzyElement>()
            var fileIndex = 0

            while (true) {
                loggy("👂 Waiting for next file header...")
                val nameLen = input.readInt()
                if (nameLen == 0) {
                    loggy("🏁 Received end signal — transfer complete (${received.size} file(s))")
                    break
                }

                val nameBytes = ByteArray(nameLen)
                input.readFully(nameBytes)
                val name = nameBytes.decodeToString()
                val fileSize = input.readLong()
                fileIndex++

                loggy("📥 [$fileIndex] Incoming: '$name' (${fileSize.toHumanSize()})")

                val fileBytes = ByteArray(fileSize.toInt())
                var bytesRead = 0L
                val buf = ByteArray(64 * 1024)

                while (bytesRead < fileSize) {
                    val toRead = minOf(buf.size.toLong(), fileSize - bytesRead).toInt()
                    input.readFully(buf, 0, toRead)
                    buf.copyInto(fileBytes, bytesRead.toInt(), 0, toRead)
                    bytesRead += toRead
                    transferProgress.value = bytesRead.toFloat() / fileSize
                    val pct = (bytesRead * 100f / fileSize).toInt()
                    loggy("   ↓ ${bytesRead.toHumanSize()} / ${fileSize.toHumanSize()} ($pct%)")
                }

                loggy("✅ [$fileIndex] '$name' fully received — sending ack")
                output.writeByte(1)
                output.flush()

                transferStatus.value = "Received: $name"
                //TODO received.add(JetzyElement.ReceivedFile(name, fileBytes))
                loggy("💾 [$fileIndex] '$name' saved to received list")
            }

            Result.success(received)
        } catch (e: Exception) {
            loggy("💥 receiveFiles() crashed: ${e.stackTraceToString()}")
            Result.failure(e)
        }
    }

    // ── Helper ────────────────────────────────────────────────────────────────────
    private fun Long.toHumanSize(): String = when {
        this < 1024L              -> "$this B"
        this < 1024L * 1024       -> "${round(this / 1024f * 10) / 10} KB"
        this < 1024L * 1024 * 1024 -> "${round(this / (1024f * 1024) * 10) / 10} MB"
        else                       -> "${round(this / (1024f * 1024 * 1024) * 100) / 100} GB"
    }



}