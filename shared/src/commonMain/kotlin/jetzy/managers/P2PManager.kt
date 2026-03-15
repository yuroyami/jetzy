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
import jetzy.viewmodel.JetzyViewmodel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.io.buffered

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
            speedLabel = "2.4MB/s",
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

    suspend fun receiveFiles() {
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



}