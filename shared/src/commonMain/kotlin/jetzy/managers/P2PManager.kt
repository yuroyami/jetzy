package jetzy.managers

import androidx.annotation.CallSuper
import androidx.compose.runtime.mutableStateListOf
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.atomicMove
import io.github.vinceglb.filekit.path
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
import jetzy.models.flattenFolder
import jetzy.p2p.P2pOperation
import jetzy.ui.Screen
import jetzy.ui.transfer.EntryType
import jetzy.ui.transfer.FileTransferEntry
import jetzy.ui.transfer.FileTransferStatus
import jetzy.ui.transfer.ManifestEntry
import jetzy.ui.transfer.PeerInfo
import jetzy.ui.transfer.ReceivedItem
import jetzy.ui.transfer.TransferManifest
import jetzy.utils.P2pIoApi
import jetzy.utils.Platform
import jetzy.utils.PreferablyIO
import jetzy.utils.generateTimestampMillis
import jetzy.utils.getDeviceName
import jetzy.utils.loggy
import jetzy.utils.platform
import jetzy.viewmodel.JetzyViewmodel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.readByteArray

/**
 * Base class for all P2P transfer methods.
 *
 * Protocol (in order):
 *   1. MANIFEST  — sender declares total file count, total bytes, and per-file metadata
 *   2. MANIFEST_ACK — receiver confirms it is ready
 *   3. For each file: FILE_DATA stream → FILE_ACK
 *   4. END_SIGNAL (writeInt(0)) — sender signals completion
 */
abstract class P2PManager {

    lateinit var viewmodel: JetzyViewmodel

    private val coroutineSupervisor = SupervisorJob()
    protected val p2pScope = CoroutineScope(PreferablyIO + coroutineSupervisor)

    open val usesPeerDiscovery: Boolean = false

    // ── Ktor Connection ────────────────────────────────────────────────────────────
    //TODO Observe socket state in order to keep UI state fresh and up-to-date
    var connection: Connection? = null
        set(value) {
            field = value
            isConnected.value = value != null
            if (value != null) beginTransfer()
        }

    val isHandshaking = MutableStateFlow(false)

    // ── Public state flows ────────────────────────────────────────────────────

    /** Populated once the manifest is exchanged; null before transfer starts */
    val manifest: StateFlow<TransferManifest?>
        field = MutableStateFlow(null)

    val remotePeerInfo: StateFlow<PeerInfo?>
        field = MutableStateFlow(null)

    /**
     * Live per-file transfer state, index-aligned with [manifest].entries.
     * Each entry carries its own [FileTransferEntry.progress] (0f–1f) so the
     * UI can show a secondary progress bar per row independently of the overall bar.
     */
    val fileEntries: StateFlow<List<FileTransferEntry>>
        field = MutableStateFlow(emptyList())

    /** Overall 0f–1f progress weighted by actual byte counts, not file count */
    val transferProgress: StateFlow<Float>
        field = MutableStateFlow(0f)

    val transferStatus: StateFlow<String>
        field = MutableStateFlow("")

    val transferComplete: StateFlow<Boolean>
        field = MutableStateFlow(false)

    val saveComplete: StateFlow<Boolean>
        field = MutableStateFlow(false)

    /** Instantaneous transfer speed in bytes/second */
    val transferSpeed: StateFlow<Long>
        field = MutableStateFlow(0L)

    val isConnected: StateFlow<Boolean>
        field = MutableStateFlow(false)

    /** Files that have been fully received and staged to the temp directory */
    val itemsRECEIVED = mutableStateListOf<ReceivedItem>()

    // ── Subclass contracts ────────────────────────────────────────────────────
    open val requiredPermissions: List<String> = listOf()

    // ── Lifecycle ─────────────────────────────────────────────────────────────
    @CallSuper
    open fun initialize(viewmodel: JetzyViewmodel) {
        this.viewmodel = viewmodel
        viewmodel.platformCallback.ensurePermissions(requiredPermissions)
    }

    @P2pIoApi
    private fun beginTransfer() {
        viewmodel.navigateTo(Screen.TransferScreen, noWayToReturn = true)
        p2pScope.launch {
            when (viewmodel.currentOperation.value) {
                P2pOperation.SEND    -> sendFiles(viewmodel.elementsToSend)
                P2pOperation.RECEIVE -> receiveFiles()
                else -> throw Exception("Unknown P2P operation")
            }
        }
    }

    @CallSuper
    open suspend fun cleanup() {
        connection?.let {
            runCatching {
                it.input.cancel()
                it.output.flushAndClose()
            }
        }
        connection = null
    }

    val bufferSize = 512 * 1024 //512 KB

    // ── Prepare elements (flatten folders) ──────────────────────────────────────
    /**
     * Flattens the element list: Folders are expanded into their constituent files
     * with relative paths preserved. Other elements pass through unchanged.
     */
    private suspend fun prepareElements(elements: List<JetzyElement>): List<JetzyElement> {
        val result = mutableListOf<JetzyElement>()
        for (element in elements) {
            when (element) {
                is JetzyElement.Folder -> {
                    val flatFiles = flattenFolder(element.folder)
                    for (flat in flatFiles) {
                        result.add(JetzyElement.File(flat.file, relativePath = flat.relativePath))
                    }
                }
                else -> result.add(element)
            }
        }
        return result
    }

    // ── Send ──────────────────────────────────────────────────────────────────
    private suspend fun sendFiles(rawFiles: List<JetzyElement>) {
        try {
            val conn = connection ?: run { loggy("[~] sendFiles() called but connection is null — aborting"); return }
            val output = conn.output
            val input = conn.input

            // Flatten folders into individual files with relative paths
            val files = prepareElements(rawFiles)

            // ── 1. Build & send manifest ──────────────────────────────────────────
            val entries = files.map {
                ManifestEntry(
                    name = it.name,
                    sizeBytes = it.size(),
                    relativePath = it.relativePath,
                    entryType = it.entryType,
                )
            }
            val totalBytes = entries.sumOf { it.sizeBytes }
            val mf = TransferManifest(
                totalFiles = files.size,
                totalBytes = totalBytes,
                entries = entries,
                senderName = getDeviceName(),
                senderPlatform = platform
            )

            output.writeInt(files.size)
            output.writeLong(totalBytes)

            // write senderName
            val senderNameBytes = mf.senderName.encodeToByteArray()
            output.writeInt(senderNameBytes.size)
            output.writeFully(senderNameBytes)

            // write senderPlatform
            val platformBytes = mf.senderPlatform.name.encodeToByteArray()
            output.writeInt(platformBytes.size)
            output.writeFully(platformBytes)

            entries.forEach { entry ->
                val nameBytes = entry.name.encodeToByteArray()
                output.writeInt(nameBytes.size)
                output.writeFully(nameBytes)
                output.writeLong(entry.sizeBytes)

                val mimeBytes = (entry.mimeType ?: "").encodeToByteArray()
                output.writeInt(mimeBytes.size)
                if (mimeBytes.isNotEmpty()) output.writeFully(mimeBytes)

                // write relativePath
                val relPathBytes = entry.relativePath.encodeToByteArray()
                output.writeInt(relPathBytes.size)
                if (relPathBytes.isNotEmpty()) output.writeFully(relPathBytes)

                // write entryType
                val typeBytes = entry.entryType.name.encodeToByteArray()
                output.writeInt(typeBytes.size)
                output.writeFully(typeBytes)
            }
            output.flush()

            manifest.value = mf
            fileEntries.value = entries.map {
                FileTransferEntry(
                    name = it.name,
                    sizeBytes = it.sizeBytes,
                    mimeType = it.mimeType,
                    relativePath = it.relativePath,
                    entryType = it.entryType,
                )
            }

            // ── read receiver's PeerInfo instead of bare ACK ─────────────────────
            val receiverNameLen = input.readInt()
            val receiverNameBytes = ByteArray(receiverNameLen).also { input.readFully(it) }
            val receiverPlatLen = input.readInt()
            val receiverPlatBytes = ByteArray(receiverPlatLen).also { input.readFully(it) }
            remotePeerInfo.value = PeerInfo(
                name = receiverNameBytes.decodeToString(),
                platform = Platform.valueOf(receiverPlatBytes.decodeToString())
            )
            loggy("[ok] Receiver identified as '${remotePeerInfo.value?.name}' — starting file stream")

            // ── 2. Stream files ───────────────────────────────────────────────────
            var totalBytesSent = 0L
            var speedWindowStart = generateTimestampMillis()
            var speedWindowBytes = 0L

            files.forEachIndexed { index, file ->
                fileEntries.updateAt(index) { it.copy(status = FileTransferStatus.Active) }
                loggy("[>] [${index + 1}/${files.size}] Streaming '${file.name}' (${entries[index].sizeBytes} bytes)")

                val src = file.source.buffered()
                val buf = ByteArray(bufferSize)

                var bytesTransferred = 0L

                try {
                    while (true) {
                        val read = src.readAtMostTo(buf)
                        if (read == -1) break

                        output.writeFully(buf, 0, read)
                        bytesTransferred += read
                        totalBytesSent += read
                        speedWindowBytes += read

                        // update per-file entry
                        fileEntries.updateAt(index) { it.copy(bytesTransferred = bytesTransferred) }

                        // overall progress by actual bytes
                        transferProgress.value = totalBytesSent.toFloat() / totalBytes

                        // recalculate speed roughly every 1000 ms
                        val now = generateTimestampMillis()
                        val elapsed = now - speedWindowStart
                        if (elapsed >= 1000L) {
                            transferSpeed.value = (speedWindowBytes * 1000L) / elapsed
                            speedWindowBytes = 0L
                            speedWindowStart = now
                        }
                    }
                } finally {
                    src.close()
                }

                output.flush()
                input.readByte() // wait for per-file ACK

                fileEntries.updateAt(index) {
                    it.copy(status = FileTransferStatus.Done, bytesTransferred = it.sizeBytes)
                }
                transferStatus.value = "Sent ${index + 1} of ${files.size}: ${file.name}"
                loggy("[ok] [${index + 1}/${files.size}] '${file.name}' confirmed by receiver")
            }

            output.writeInt(0) // end signal
            output.flush()
            transferSpeed.value = 0L
            loggy("[ok] All ${files.size} file(s) sent — end signal written")

            transferComplete.value = true
        } catch (e: Exception) {
            loggy(e.stackTraceToString())
            viewmodel.snacky("An error occurred during transfer: Pipe broken")
            viewmodel.resetEverything()
        }
    }

    // ── Receive ───────────────────────────────────────────────────────────────
    //TODO Protect against overwrite
    private suspend fun receiveFiles() {
        try {
            val conn = connection ?: run { loggy("[~] receiveFiles() called but connection is null — aborting"); return }
            val input = conn.input
            val output = conn.output

            // ── 1. Read manifest ──────────────────────────────────────────────────
            val totalFiles = input.readInt()
            val totalBytes = input.readLong()

            // read senderName
            val senderNameLen = input.readInt()
            val senderNameBytes = ByteArray(senderNameLen).also { input.readFully(it) }

            // read senderPlatform
            val senderPlatLen = input.readInt()
            val senderPlatBytes = ByteArray(senderPlatLen).also { input.readFully(it) }

            val entries = (0 until totalFiles).map {
                val nameLen = input.readInt()
                val nameBytes = ByteArray(nameLen).also { b -> input.readFully(b) }
                val sizeBytes = input.readLong()
                val mimeLen = input.readInt()
                val mimeType = if (mimeLen > 0) {
                    ByteArray(mimeLen).also { b -> input.readFully(b) }.decodeToString()
                } else null

                // read relativePath
                val relPathLen = input.readInt()
                val relativePath = if (relPathLen > 0) {
                    ByteArray(relPathLen).also { b -> input.readFully(b) }.decodeToString()
                } else ""

                // read entryType
                val typeLen = input.readInt()
                val typeStr = ByteArray(typeLen).also { b -> input.readFully(b) }.decodeToString()
                val entryType = runCatching { EntryType.valueOf(typeStr) }.getOrDefault(EntryType.FILE)

                ManifestEntry(
                    name = nameBytes.decodeToString(),
                    sizeBytes = sizeBytes,
                    mimeType = mimeType,
                    relativePath = relativePath,
                    entryType = entryType,
                )
            }

            val mf = TransferManifest(
                totalFiles = totalFiles,
                totalBytes = totalBytes,
                entries = entries,
                senderName = senderNameBytes.decodeToString(),
                senderPlatform = Platform.valueOf(senderPlatBytes.decodeToString())
            )
            manifest.value = mf
            fileEntries.value = entries.map {
                FileTransferEntry(
                    name = it.name,
                    sizeBytes = it.sizeBytes,
                    mimeType = it.mimeType,
                    relativePath = it.relativePath,
                    entryType = it.entryType,
                )
            }
            loggy("[<] Manifest received from '${mf.senderName}': $totalFiles file(s), $totalBytes bytes total")

            // ── reply with our own PeerInfo instead of bare ACK ──────────────────
            val myNameBytes = getDeviceName().encodeToByteArray()
            output.writeInt(myNameBytes.size)
            output.writeFully(myNameBytes)

            val myPlatBytes = platform.name.encodeToByteArray()
            output.writeInt(myPlatBytes.size)
            output.writeFully(myPlatBytes)
            output.flush()

            remotePeerInfo.value = PeerInfo(name = mf.senderName, platform = mf.senderPlatform)

            // ── 2. Receive files ──────────────────────────────────────────────────
            var totalBytesRead = 0L
            var speedWindowStart = generateTimestampMillis()
            var speedWindowBytes = 0L

            entries.forEachIndexed { index, entry ->
                fileEntries.updateAt(index) { it.copy(status = FileTransferStatus.Active) }
                loggy("[<] [${index + 1}/${entries.size}] Receiving '${entry.name}' (${entry.sizeBytes} bytes)")

                val tempPath = Path(SystemTemporaryDirectory, entry.name)
                val sink = SystemFileSystem.sink(tempPath).buffered()
                val buf = ByteArray(bufferSize)
                var fileRead = 0L

                try {
                    while (fileRead < entry.sizeBytes) {
                        val toRead = minOf(buf.size.toLong(), entry.sizeBytes - fileRead).toInt()
                        input.readFully(buf, 0, toRead)
                        sink.write(buf, 0, toRead)

                        fileRead += toRead
                        totalBytesRead += toRead
                        speedWindowBytes += toRead

                        fileEntries.updateAt(index) { it.copy(bytesTransferred = fileRead) }
                        transferProgress.value = totalBytesRead.toFloat() / totalBytes

                        val now = generateTimestampMillis()
                        val elapsed = now - speedWindowStart
                        if (elapsed >= 1000L) {
                            transferSpeed.value = (speedWindowBytes * 1000L) / elapsed
                            speedWindowBytes = 0L
                            speedWindowStart = now
                        }
                    }
                } finally {
                    sink.flush()
                    sink.close()
                }

                output.writeByte(1)
                output.flush()

                // For TEXT entries, read back the content from the temp file
                val textContent = if (entry.entryType == EntryType.TEXT) {
                    runCatching {
                        val source = SystemFileSystem.source(tempPath).buffered()
                        val bytes = source.readByteArray()
                        source.close()
                        bytes.decodeToString()
                    }.getOrNull()
                } else null

                fileEntries.updateAt(index) {
                    it.copy(
                        status = FileTransferStatus.Done,
                        bytesTransferred = entry.sizeBytes,
                        textContent = textContent,
                    )
                }
                itemsRECEIVED.add(
                    ReceivedItem(
                        name = entry.name,
                        path = tempPath,
                        sizeBytes = entry.sizeBytes,
                        mimeType = entry.mimeType,
                        relativePath = entry.relativePath,
                        entryType = entry.entryType,
                        textContent = textContent,
                    )
                )
                transferStatus.value = "Received: ${entry.name}"
                loggy("[ok] [${index + 1}/${entries.size}] '${entry.name}' fully received")
            }

            transferSpeed.value = 0L
            loggy("[ok] Transfer complete — ${itemsRECEIVED.size} file(s) staged")

            transferComplete.value = true
        } catch (e: Exception) {
            loggy(e.stackTraceToString())
            viewmodel.snacky("An error occurred during transfer: Pipe broken")
            viewmodel.resetEverything()
        }
    }

    // ── Internal helpers ──────────────────────────────────────────────────────
    /**
     * Immutably replaces a single entry in the list state flow at [index].
     * Safe to call from any coroutine since StateFlow.value assignment is atomic.
     */
    private fun MutableStateFlow<List<FileTransferEntry>>.updateAt(
        index: Int,
        transform: (FileTransferEntry) -> FileTransferEntry
    ) {
        value = value.toMutableList().also { it[index] = transform(it[index]) }
    }

    fun finalizeReceivedFilesAt(destDir: PlatformFile) {
        p2pScope.launch {
            val fileItems = itemsRECEIVED.filter { it.entryType == EntryType.FILE }

            // Group by top-level folder to reconstruct directory structure
            for (item in fileItems) {
                if (item.relativePath.isNotEmpty()) {
                    // Create parent directories for folder-sourced files
                    val parentPath = item.relativePath.substringBeforeLast('/', "")
                    if (parentPath.isNotEmpty()) {
                        // Create the directory structure in the destination
                        val dirs = parentPath.split('/')
                        var currentPath = destDir.path
                        for (dir in dirs) {
                            currentPath = "$currentPath/$dir"
                            val dirPath = Path(currentPath)
                            if (!SystemFileSystem.exists(dirPath)) {
                                SystemFileSystem.createDirectories(dirPath)
                            }
                        }
                        // Move file to the correct subdirectory
                        val destPath = Path("$currentPath/${item.name}")
                        val sourcePath = item.path
                        SystemFileSystem.atomicMove(sourcePath, destPath)
                        continue
                    }
                }
                // Top-level file — move directly to dest
                val platformFile = PlatformFile(item.path)
                platformFile.atomicMove(destDir)
            }

            saveComplete.value = true
            viewmodel.snacky("Files saved successfully!")
        }
    }
}
