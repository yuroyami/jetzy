package jetzy.managers

import androidx.annotation.CallSuper
import androidx.compose.runtime.mutableStateListOf
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.atomicMove
import io.github.vinceglb.filekit.path
import io.ktor.network.sockets.Connection
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.cancel
import io.ktor.utils.io.readFully
import io.ktor.utils.io.readInt
import io.ktor.utils.io.writeFully
import io.ktor.utils.io.writeInt
import jetzy.managers.JetzyProtocol.AckStatus
import jetzy.managers.JetzyProtocol.FileAck
import jetzy.managers.JetzyProtocol.HelloFrame
import jetzy.managers.JetzyProtocol.ManifestAckFrame
import jetzy.managers.JetzyProtocol.ManifestFrame
import jetzy.models.JetzyElement
import jetzy.models.flattenFolder
import jetzy.p2p.CapabilityProfile
import jetzy.p2p.DirectionResolver
import jetzy.p2p.P2pOperation
import jetzy.p2p.P2pTechnology
import jetzy.p2p.TransferDirection
import jetzy.p2p.TransferParty
import jetzy.p2p.TransportMatch
import jetzy.p2p.TransportNegotiator
import jetzy.permissions.PermissionRequirement
import jetzy.ui.Screen
import jetzy.ui.transfer.EntryType
import jetzy.ui.transfer.FileTransferEntry
import jetzy.ui.transfer.FileTransferStatus
import jetzy.ui.transfer.ManifestEntry
import jetzy.ui.transfer.PeerInfo
import jetzy.ui.transfer.ReceivedItem
import jetzy.ui.transfer.TransferManifest
import jetzy.utils.P2pIoApi
import jetzy.utils.PreferablyIO
import jetzy.utils.generateTimestampMillis
import jetzy.utils.getAvailableStorageBytes
import jetzy.utils.deviceName
import jetzy.utils.loggy
import jetzy.utils.platform
import jetzy.viewmodel.JetzyViewmodel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.readByteArray
import kotlin.concurrent.Volatile
import kotlin.time.TimeSource
import kotlin.uuid.Uuid

/**
 * Base class for all P2P transfer methods. Owns the wire protocol, per-file
 * CRC verification, stall detection, and in-memory resume bookkeeping. See
 * [JetzyProtocol] for the exact on-wire format.
 */
abstract class P2PManager {

    lateinit var viewmodel: JetzyViewmodel

    private val coroutineSupervisor = SupervisorJob()
    protected val p2pScope = CoroutineScope(PreferablyIO + coroutineSupervisor)

    open val usesPeerDiscovery: Boolean = false

    /**
     * Which [P2pTechnology] this manager's data plane actually runs over. Lets the live handshake
     * negotiation tell whether a *faster* mutual transport exists than the one we bootstrapped on
     * (see [recommendedUpgrade]). Null = "unknown / not yet declared" → no upgrade is ever suggested
     * (we never blindly recommend switching off a transport we can't rank ourselves against).
     */
    open val technology: P2pTechnology? = null

    // ── Ktor Connection ────────────────────────────────────────────────────────────
    var connection: Connection? = null
        set(value) {
            field = value
            isConnected.value = value != null
            if (value != null) beginTransfer()
        }

    // ── Direct channel support (for non-TCP transports like MPC) ─────────────
    private var _directInput: ByteReadChannel? = null
    private var _directOutput: ByteWriteChannel? = null

    protected val activeInput: ByteReadChannel? get() = _directInput ?: connection?.input
    protected val activeOutput: ByteWriteChannel? get() = _directOutput ?: connection?.output

    /** Starts a transfer using raw byte channels (no TCP Connection required). */
    protected fun startTransferWithChannels(input: ByteReadChannel, output: ByteWriteChannel) {
        _directInput = input
        _directOutput = output
        isConnected.value = true
        beginTransfer()
    }

    val isHandshaking = MutableStateFlow(false)

    // ── Public state flows ────────────────────────────────────────────────────
    val manifest: StateFlow<TransferManifest?>
        field = MutableStateFlow(null)

    val remotePeerInfo: StateFlow<PeerInfo?>
        field = MutableStateFlow(null)

    /**
     * Every transport this pair *mutually* supports, best-first, computed by [TransportNegotiator]
     * the instant the HELLO capability masks are exchanged — the live result of the negotiation
     * brain that until now was dead code. Empty until handshake, or when the peer is a legacy
     * (caps == 0) build.
     */
    val negotiatedTransports: StateFlow<List<TransportMatch>>
        field = MutableStateFlow(emptyList())

    /**
     * A mutually-supported transport strictly faster than the one we're connected over, if any —
     * the opportunistic "gear-shift" target. Null when we're already on the best mutual link (or
     * can't rank ourselves, i.e. [technology] is null). Surfaced so the UI can show "⚡ faster link
     * available" and, once the in-band UPGRADE frame lands, so the session can climb to it live.
     */
    val recommendedUpgrade: StateFlow<TransportMatch?>
        field = MutableStateFlow(null)

    val fileEntries: StateFlow<List<FileTransferEntry>>
        field = MutableStateFlow(emptyList())

    val transferProgress: StateFlow<Float>
        field = MutableStateFlow(0f)

    val transferStatus: StateFlow<String>
        field = MutableStateFlow("")

    val transferComplete: StateFlow<Boolean>
        field = MutableStateFlow(false)

    val saveComplete: StateFlow<Boolean>
        field = MutableStateFlow(false)

    /** True while [finalizeReceivedFilesAt] is moving staged files to the user's chosen folder. */
    val isSaving: StateFlow<Boolean>
        field = MutableStateFlow(false)

    val transferSpeed: StateFlow<Long>
        field = MutableStateFlow(0L)

    val isConnected: StateFlow<Boolean>
        field = MutableStateFlow(false)

    /** Current session ID. Stable across reconnects within one app lifecycle so resume works. */
    val sessionId = MutableStateFlow<String?>(null)
        //field = MutableStateFlow(null)

    /** Seed or rotate the session id from outside the manager (e.g. from a scanned QR). */
    fun setSessionId(id: String?) { sessionId.value = id }

    /** Whether the current session can be resumed (i.e. was interrupted, has staged data). */
    val canResume: StateFlow<Boolean>
        field = MutableStateFlow(false)

    /** Diagnostic breadcrumbs visible on the discovery & transfer screens (best-effort log stream). */
    val diagnostics: StateFlow<List<String>>
        field = MutableStateFlow(emptyList())

    /** Files that have been fully received and staged to the temp directory. */
    val itemsRECEIVED = mutableStateListOf<ReceivedItem>()

    // ── Subclass contracts ────────────────────────────────────────────────────
    /**
     * Everything the OS / user must satisfy before this manager can run.
     * Read by the permission-gate dialog *before* [initialize] is called, so
     * the user has a chance to flip toggles and grant runtime permissions.
     * Default is empty (iOS managers rely on system-level prompts at use-site).
     */
    open val permissionRequirements: List<PermissionRequirement> = emptyList()

    // ── Lifecycle ─────────────────────────────────────────────────────────────
    @CallSuper
    open fun initialize(viewmodel: JetzyViewmodel) {
        this.viewmodel = viewmodel
    }

    /**
     * Guards against starting the transfer twice. Symmetric transports run a server
     * (accept) *and* a client (dial) and can establish both — each would set [connection]
     * or call [startTransferWithChannels], firing [beginTransfer] a second time and
     * launching a duplicate send/receive over a second socket, corrupting the stream.
     * Reset in [prepareForResume] so a reconnect can begin a fresh attempt.
     */
    @Volatile private var transferBegun = false

    @P2pIoApi
    private fun beginTransfer() {
        if (transferBegun) return
        transferBegun = true
        viewmodel.navigateTo(Screen.TransferScreen, noWayToReturn = true)
        p2pScope.launch {
            val input = activeInput
            val output = activeOutput
            if (input == null || output == null) { diag("beginTransfer: no active channels"); return@launch }

            // v3: handshake FIRST, then *derive* who sends from the exchanged intents — replacing
            // the old "the user already picked Send/Receive, just run it" branch. The manual pick
            // becomes advisory; the wire is authoritative.
            val direction = try {
                val peer = performHandshake(input, output)
                DirectionResolver.resolve(
                    local = TransferParty(viewmodel.elementsToSend.isNotEmpty(), platform, deviceName),
                    remote = TransferParty(peer.offeringFiles, peer.platform, peer.name),
                )
            } catch (e: Exception) {
                diag("handshake failed: ${e.message ?: e::class.simpleName}")
                transferComplete.value = true // release the Connecting… screen instead of hanging
                viewmodel.snacky(friendlyFailure(e))
                return@launch
            }
            diag("direction resolved: $direction (peer=${remotePeerInfo.value?.name})")

            // Keep currentOperation in sync so every isSender-driven UI surface stays correct.
            when (direction) {
                TransferDirection.SEND -> {
                    viewmodel.currentOperation.value = P2pOperation.SEND
                    sendFiles(viewmodel.elementsToSend)
                }
                TransferDirection.RECEIVE -> {
                    viewmodel.currentOperation.value = P2pOperation.RECEIVE
                    receiveFiles()
                }
                TransferDirection.NONE -> {
                    diag("neither device staged files — nothing to transfer")
                    transferComplete.value = true
                    viewmodel.snacky("Neither device has files to send — add some and reconnect.")
                }
            }
        }
    }

    @CallSuper
    open suspend fun cleanup() {
        stallWatchdogJob?.cancel()
        stallWatchdogJob = null
        connection?.let {
            runCatching {
                it.input.cancel()
                it.output.flushAndClose()
            }
        }
        connection = null
        _directInput?.let { runCatching { it.cancel() } }
        _directOutput?.let { runCatching { it.flushAndClose() } }
        _directInput = null
        _directOutput = null
        purgeUnsavedReceivedFiles()
        runCatching { viewmodel.platformCallback.stopBackgroundService() }
    }

    /**
     * Shut the current socket down *without* discarding the session id, partial files,
     * or receiver ledger. Called when the user wants to reconnect after a broken
     * transfer — the next QR handshake will carry the same sessionId and the protocol
     * layer will ACK with RESUME so we skip already-completed files.
     */
    open suspend fun prepareForResume() {
        stallWatchdogJob?.cancel()
        stallWatchdogJob = null
        connection?.let {
            runCatching {
                it.input.cancel()
                it.output.flushAndClose()
            }
        }
        connection = null
        _directInput?.let { runCatching { it.cancel() } }
        _directOutput?.let { runCatching { it.flushAndClose() } }
        _directInput = null
        _directOutput = null
        // Reset per-attempt UI state so a fresh handshake can run; sessionId + receiverLedger stay.
        transferComplete.value = false
        transferProgress.value = 0f
        transferSpeed.value = 0L
        transferStatus.value = ""
        isHandshaking.value = false
        anyBytesMoved = false
        bytesMovedTotal = 0L
        transferBegun = false
    }

    /**
     * Deletes every temp-staged file unless the user already moved them to their
     * chosen destination. Called from [cleanup] and on viewmodel reset so the
     * next run never inherits orphan data.
     */
    fun purgeUnsavedReceivedFiles() {
        if (saveComplete.value) return

        val toRemove = itemsRECEIVED.map { it.path } + listOfNotNull(inProgressTempPath)
        toRemove.forEach { path ->
            runCatching {
                if (SystemFileSystem.exists(path)) SystemFileSystem.delete(path)
            }.onFailure { loggy("Failed to delete temp file '$path': ${it.message}") }
        }
        itemsRECEIVED.clear()
        inProgressTempPath = null
        canResume.value = false
    }

    /** Temp file currently being written. Tracked so it can be purged if the session is aborted. */
    protected var inProgressTempPath: Path? = null

    // ── Buffer sizing & stall detection ──────────────────────────────────────
    val bufferSize = 512 * 1024

    /**
     * Minimum interval between UI state pushes (fileEntries / transferProgress) during the
     * transfer hot loop. Per-chunk emission used to drive Compose recomposition at chunk
     * rate (hundreds/sec on a fast link); ~10 Hz is imperceptible but kills the storm.
     */
    private val uiRefreshIntervalMs = 100L
    private var stallWatchdogJob: Job? = null
    private val stallTimeoutMs = 8000L

    /** True once we've made any forward progress with the current connection. */
    @Volatile private var anyBytesMoved: Boolean = false

    /** Monotonic count of payload bytes moved on the current connection. Drives stall
     *  detection precisely; the watchdog used to reconstruct it from a lossy Float progress. */
    @Volatile private var bytesMovedTotal: Long = 0L

    // ── Resume ledgers (in-memory) ───────────────────────────────────────────
    /** For the receiver: map fileIndex → (tempPath, bytesWritten, sizeBytes). */
    private val receiverLedger = mutableMapOf<Int, ReceiverFileState>()

    private data class ReceiverFileState(
        val tempPath: Path,
        val bytesWritten: Long,
        val sizeBytes: Long,
    )

    // ── Diagnostics helper ───────────────────────────────────────────────────
    protected fun diag(msg: String) {
        val stamped = "[${generateTimestampMillis() % 100_000}] $msg"
        diagnostics.value = (diagnostics.value + stamped).takeLast(50)
        loggy(stamped)
    }

    // ── Prepare elements (flatten folders) ──────────────────────────────────
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
        val output = activeOutput ?: run { diag("sendFiles: no output channel"); return }
        val input = activeInput ?: run { diag("sendFiles: no input channel"); return }

        val files = prepareElements(rawFiles)
        val entries = files.map {
            ManifestEntry(
                name = it.name,
                sizeBytes = it.size(),
                relativePath = it.relativePath,
                entryType = it.entryType,
            )
        }
        val totalBytes = entries.sumOf { it.sizeBytes }
        val session = sessionId.value ?: Uuid.random().toString().also { sessionId.value = it }
        val mf = TransferManifest(
            totalFiles = files.size,
            totalBytes = totalBytes,
            entries = entries,
            senderName = deviceName,
            senderPlatform = platform
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

        try {
            // Handshake + direction resolution already happened in beginTransfer; send the manifest.
            JetzyProtocol.writeManifest(output, ManifestFrame(session, mf))

            // 3. Read manifest ack
            val ack = JetzyProtocol.readManifestAck(input)
            diag("manifest ack: ${ack.status} resume=${ack.resumeFileIndex}@${ack.resumeByteOffset}")
            when (ack.status) {
                AckStatus.INSUFFICIENT_SPACE -> {
                    viewmodel.snacky("Receiver out of space: ${ack.reason.ifEmpty { "not enough free storage" }}")
                    markAllNonDoneAsFailed()
                    transferComplete.value = true
                    return
                }
                AckStatus.REJECTED -> {
                    viewmodel.snacky("Receiver rejected transfer: ${ack.reason.ifEmpty { "unspecified" }}")
                    markAllNonDoneAsFailed()
                    transferComplete.value = true
                    return
                }
                AckStatus.OK, AckStatus.RESUME -> { /* continue */ }
            }

            // 4. Stream files (honoring resume point)
            var totalBytesSent = 0L
            val clock = TimeSource.Monotonic
            var speedWindowStart = clock.markNow()
            var lastUiPush = clock.markNow()
            var speedWindowBytes = 0L
            startSenderStallWatchdog()

            // Advance already-done files in UI if resuming
            for (i in 0 until ack.resumeFileIndex) {
                fileEntries.updateAt(i) {
                    it.copy(status = FileTransferStatus.Done, bytesTransferred = it.sizeBytes)
                }
                totalBytesSent += entries[i].sizeBytes
            }

            files.drop(ack.resumeFileIndex).forEachIndexed { relIndex, file ->
                val index = ack.resumeFileIndex + relIndex
                val fileSize = entries[index].sizeBytes
                val offset = if (index == ack.resumeFileIndex) ack.resumeByteOffset else 0L

                fileEntries.updateAt(index) {
                    it.copy(status = FileTransferStatus.Active, bytesTransferred = offset)
                }
                diag("[>] ${index + 1}/${files.size} '${file.name}' offset=$offset size=$fileSize")

                val src = file.source.buffered()
                val buf = ByteArray(bufferSize)
                val crc = Crc32()
                var bytesTransferred = offset

                try {
                    // Resume: feed the bytes the receiver already has [0, offset) through our
                    // CRC *without* re-sending them. The receiver rebuilds its CRC the same way
                    // from its partial file, so the final whole-file CRC matches on both ends.
                    // (Sequential reads are more portable than skip(), which not every
                    // RawSource supports — and skip() never fed the prefix to the CRC, which is
                    // why every resumed file used to fail verification.)
                    var prefixRemaining = offset
                    while (prefixRemaining > 0) {
                        val toRead = minOf(buf.size.toLong(), prefixRemaining).toInt()
                        val read = src.readAtMostTo(buf, 0, toRead)
                        if (read == -1) break
                        crc.update(buf, 0, read)
                        prefixRemaining -= read
                    }

                    while (bytesTransferred < fileSize) {
                        val toRead = minOf(buf.size.toLong(), fileSize - bytesTransferred).toInt()
                        val read = src.readAtMostTo(buf, 0, toRead)
                        if (read == -1) break

                        output.writeFully(buf, 0, read)
                        crc.update(buf, 0, read)
                        bytesTransferred += read
                        totalBytesSent += read
                        speedWindowBytes += read
                        anyBytesMoved = true
                        bytesMovedTotal = totalBytesSent

                        // Coalesce UI pushes to ~10 Hz. Per-chunk emission used to fire
                        // fileEntries (a full O(n) list copy) + transferProgress on every
                        // 512 KB read, driving Compose recomposition at chunk rate.
                        if (lastUiPush.elapsedNow().inWholeMilliseconds >= uiRefreshIntervalMs) {
                            fileEntries.updateAt(index) { it.copy(bytesTransferred = bytesTransferred) }
                            transferProgress.value = totalBytesSent.toFloat() / totalBytes.coerceAtLeast(1L)
                            lastUiPush = clock.markNow()
                        }

                        val elapsed = speedWindowStart.elapsedNow().inWholeMilliseconds
                        if (elapsed >= 1000L) {
                            transferSpeed.value = (speedWindowBytes * 1000L) / elapsed
                            speedWindowBytes = 0L
                            speedWindowStart = clock.markNow()
                        }
                    }
                } finally {
                    src.close()
                }

                output.writeInt(crc.finish())
                output.flush()

                val fileAck = JetzyProtocol.readFileAck(input)
                when (fileAck) {
                    FileAck.OK -> {
                        fileEntries.updateAt(index) {
                            it.copy(status = FileTransferStatus.Done, bytesTransferred = fileSize)
                        }
                        transferStatus.value = "Sent ${index + 1} of ${files.size}: ${file.name}"
                        diag("[ok] ${index + 1}/${files.size} '${file.name}' confirmed")
                    }
                    FileAck.CRC_MISMATCH -> {
                        fileEntries.updateAt(index) { it.copy(status = FileTransferStatus.Failed) }
                        throw ProtocolException("CRC mismatch reported by receiver on '${file.name}'")
                    }
                    FileAck.CANCELLED -> {
                        fileEntries.updateAt(index) { it.copy(status = FileTransferStatus.Failed) }
                        diag("cancelled by receiver")
                        return
                    }
                }
            }

            // The 10 Hz throttle may have skipped the final fraction; pin it to 100%.
            transferProgress.value = 1f

            // 5. Clean close
            JetzyProtocol.writeDone(output)
            transferSpeed.value = 0L
            diag("all ${files.size} file(s) sent; DONE written")
            transferComplete.value = true
            canResume.value = false
        } catch (e: Exception) {
            diag("send failed: ${e.message ?: e::class.simpleName}")
            transferSpeed.value = 0L
            markAllNonDoneAsFailed()
            transferComplete.value = true
            canResume.value = anyBytesMoved // allow "resume" UX only if we made progress
            viewmodel.snacky(friendlyFailure(e))
        } finally {
            stallWatchdogJob?.cancel()
        }
    }

    // ── Receive ───────────────────────────────────────────────────────────────
    private suspend fun receiveFiles() {
        val input = activeInput ?: run { diag("receiveFiles: no input channel"); return }
        val output = activeOutput ?: run { diag("receiveFiles: no output channel"); return }

        try {
            // Handshake + direction resolution already happened in beginTransfer; read the manifest.
            val frame = JetzyProtocol.readManifest(input)
            val mf = frame.manifest
            val session = frame.sessionId
            manifest.value = mf
            val storedSession = sessionId.value
            sessionId.value = session
            fileEntries.value = mf.entries.map {
                FileTransferEntry(
                    name = it.name,
                    sizeBytes = it.sizeBytes,
                    mimeType = it.mimeType,
                    relativePath = it.relativePath,
                    entryType = it.entryType,
                )
            }
            diag("manifest from '${mf.senderName}': ${mf.totalFiles} files / ${mf.totalBytes} bytes / session=$session")

            // 3. Compute resume state vs. storage
            val freeBytes = getAvailableStorageBytes()
            val needed = computeRemainingBytes(mf, storedSession == session)
            val ack = when {
                freeBytes != Long.MAX_VALUE && needed > freeBytes - SPACE_SAFETY_MARGIN -> {
                    ManifestAckFrame(
                        status = AckStatus.INSUFFICIENT_SPACE,
                        receiver = PeerInfo(deviceName, platform),
                        reason = "need ${needed} bytes, free ${freeBytes}",
                    )
                }
                storedSession == session && receiverLedger.isNotEmpty() -> {
                    val resumePoint = computeResumePoint(mf)
                    ManifestAckFrame(
                        status = AckStatus.RESUME,
                        receiver = PeerInfo(deviceName, platform),
                        resumeFileIndex = resumePoint.first,
                        resumeByteOffset = resumePoint.second,
                    )
                }
                else -> {
                    // fresh session: reset our ledger since the old one is irrelevant
                    receiverLedger.clear()
                    ManifestAckFrame(
                        status = AckStatus.OK,
                        receiver = PeerInfo(deviceName, platform),
                    )
                }
            }
            JetzyProtocol.writeManifestAck(output, ack)
            diag("ack → ${ack.status} resume=${ack.resumeFileIndex}@${ack.resumeByteOffset}")

            if (ack.status == AckStatus.INSUFFICIENT_SPACE || ack.status == AckStatus.REJECTED) {
                viewmodel.snacky("Not enough free space for this transfer")
                markAllNonDoneAsFailed()
                transferComplete.value = true
                return
            }

            remotePeerInfo.value = PeerInfo(mf.senderName, mf.senderPlatform)

            // 4. Receive files (starting from resume point)
            var totalBytesRead = (0 until ack.resumeFileIndex).sumOf { mf.entries[it].sizeBytes }
            val clock = TimeSource.Monotonic
            var speedWindowStart = clock.markNow()
            var lastUiPush = clock.markNow()
            var speedWindowBytes = 0L
            startReceiverStallWatchdog()

            for (i in 0 until ack.resumeFileIndex) {
                fileEntries.updateAt(i) {
                    it.copy(status = FileTransferStatus.Done, bytesTransferred = it.sizeBytes)
                }
            }

            mf.entries.drop(ack.resumeFileIndex).forEachIndexed { relIndex, entry ->
                val index = ack.resumeFileIndex + relIndex
                val startOffset = if (index == ack.resumeFileIndex) ack.resumeByteOffset else 0L

                fileEntries.updateAt(index) {
                    it.copy(status = FileTransferStatus.Active, bytesTransferred = startOffset)
                }
                diag("[<] ${index + 1}/${mf.entries.size} '${entry.name}' offset=$startOffset size=${entry.sizeBytes}")

                val tempPath = receiverLedger[index]?.tempPath?.takeIf { startOffset > 0 }
                    ?: allocateTempPath(entry.name)
                inProgressTempPath = tempPath

                // append-or-create sink. If startOffset==0 we truncate by reopening; otherwise append.
                val append = startOffset > 0
                val sink = if (append) SystemFileSystem.sink(tempPath, append = true).buffered()
                           else SystemFileSystem.sink(tempPath).buffered()
                val buf = ByteArray(bufferSize)
                val crc = Crc32()
                // if appending, rebuild CRC from existing file bytes
                if (append && SystemFileSystem.exists(tempPath)) {
                    runCatching {
                        val src = SystemFileSystem.source(tempPath).buffered()
                        val rebuildBuf = ByteArray(bufferSize)
                        // Hash exactly the [0, startOffset) prefix the sender is skipping — this
                        // mirrors the sender's prefix-CRC so the whole-file CRCs line up. Bounding
                        // to startOffset (rather than reading to EOF) keeps us correct even if a
                        // stale temp file happens to be longer than the ledger's byte count.
                        var remaining = startOffset
                        while (remaining > 0) {
                            val toRead = minOf(rebuildBuf.size.toLong(), remaining).toInt()
                            val r = src.readAtMostTo(rebuildBuf, 0, toRead)
                            if (r == -1) break
                            crc.update(rebuildBuf, 0, r)
                            remaining -= r
                        }
                        src.close()
                    }.onFailure { diag("CRC rebuild from partial failed: ${it.message}") }
                }

                var fileRead = startOffset

                try {
                    while (fileRead < entry.sizeBytes) {
                        val toRead = minOf(buf.size.toLong(), entry.sizeBytes - fileRead).toInt()
                        input.readFully(buf, 0, toRead)
                        sink.write(buf, 0, toRead)
                        crc.update(buf, 0, toRead)

                        fileRead += toRead
                        totalBytesRead += toRead
                        speedWindowBytes += toRead
                        anyBytesMoved = true
                        bytesMovedTotal = totalBytesRead

                        // Coalesce UI pushes to ~10 Hz (see sender loop). The receiver
                        // ledger is no longer rewritten per chunk — it's recorded once in
                        // the finally below, which still runs on a mid-file disconnect.
                        if (lastUiPush.elapsedNow().inWholeMilliseconds >= uiRefreshIntervalMs) {
                            fileEntries.updateAt(index) { it.copy(bytesTransferred = fileRead) }
                            transferProgress.value = totalBytesRead.toFloat() / mf.totalBytes.coerceAtLeast(1L)
                            lastUiPush = clock.markNow()
                        }

                        val elapsed = speedWindowStart.elapsedNow().inWholeMilliseconds
                        if (elapsed >= 1000L) {
                            transferSpeed.value = (speedWindowBytes * 1000L) / elapsed
                            speedWindowBytes = 0L
                            speedWindowStart = clock.markNow()
                        }
                    }
                } finally {
                    sink.flush()
                    sink.close()
                    // Record resume progress once per file, after the buffered sink has been
                    // flushed to disk. This finally runs on clean completion and on a mid-file
                    // disconnect alike, so the in-memory ledger stays accurate without a
                    // per-chunk allocation + map write.
                    receiverLedger[index] = ReceiverFileState(tempPath, fileRead, entry.sizeBytes)
                }

                val expectedCrc = input.readInt()
                val actualCrc = crc.finish()
                val ackCode = if (expectedCrc == actualCrc) FileAck.OK else FileAck.CRC_MISMATCH
                JetzyProtocol.writeFileAck(output, ackCode)

                if (ackCode == FileAck.CRC_MISMATCH) {
                    diag("CRC mismatch on '${entry.name}': expected $expectedCrc got $actualCrc")
                    fileEntries.updateAt(index) { it.copy(status = FileTransferStatus.Failed) }
                    // delete corrupt partial
                    runCatching { if (SystemFileSystem.exists(tempPath)) SystemFileSystem.delete(tempPath) }
                    receiverLedger.remove(index)
                    throw ProtocolException("File '${entry.name}' failed CRC; transfer aborted")
                }

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
                inProgressTempPath = null
                transferStatus.value = "Received: ${entry.name}"
                diag("[ok] ${index + 1}/${mf.entries.size} '${entry.name}' received & verified")
            }

            // The 10 Hz throttle may have skipped the final fraction; pin it to 100%.
            transferProgress.value = 1f

            // 5. Expect DONE
            JetzyProtocol.readDone(input)
            transferSpeed.value = 0L
            diag("transfer complete — ${itemsRECEIVED.size} file(s) staged")
            transferComplete.value = true
            canResume.value = false
        } catch (e: Exception) {
            diag("receive failed: ${e.message ?: e::class.simpleName}")
            transferSpeed.value = 0L
            markAllNonDoneAsFailed()
            transferComplete.value = true
            canResume.value = anyBytesMoved
            viewmodel.snacky(friendlyFailure(e))
        } finally {
            stallWatchdogJob?.cancel()
        }
    }

    // ── Handshake ────────────────────────────────────────────────────────────
    /**
     * Symmetric v3 handshake: **both ends do the identical thing** — write magic+version+HELLO
     * (advertising this device's intent via [HelloFrame.offeringFiles]) and then read the peer's.
     * Because neither side's order is tied to send-vs-receive, transfer direction can be resolved
     * from the exchanged intents *afterward* ([DirectionResolver]) instead of being declared up
     * front. The frames are tiny (~tens of bytes) and each writer flushes, so they land in the
     * socket/channel buffer and the simultaneous write→read cannot deadlock. Sets [remotePeerInfo]
     * and runs the live transport negotiation; returns the peer's HELLO.
     */
    private suspend fun performHandshake(input: ByteReadChannel, output: ByteWriteChannel): HelloFrame {
        JetzyProtocol.writeHandshake(output)
        JetzyProtocol.writeHello(
            output,
            HelloFrame(deviceName, platform, P2pTechnology.localCapabilitiesMask(), offeringFiles = viewmodel.elementsToSend.isNotEmpty()),
        )
        JetzyProtocol.readHandshake(input)
        val peer = JetzyProtocol.readHello(input)
        remotePeerInfo.value = PeerInfo(peer.name, peer.platform)
        negotiatePeerCapabilities(peer)
        return peer
    }

    /**
     * Runs the [TransportNegotiator] against the peer's just-exchanged capability mask. This is the
     * moment the negotiation brain — previously dead code wired to nothing — goes live on the real
     * path: from the two [CapabilityProfile]s (ours + the peer's HELLO) it derives every mutual
     * transport best-first and the single faster [recommendedUpgrade] target, with zero extra
     * round-trips (both peers compute the identical result from the identical two profiles). The
     * results are pushed to state + the diagnostic stream; *acting* on an upgrade (the in-band
     * UPGRADE frame) is the next step — this commit makes the decision observable and correct.
     */
    private fun negotiatePeerCapabilities(peer: HelloFrame) {
        if (peer.capabilities == 0L) {
            diag("peer caps: (none advertised — legacy build); staying on ${technology?.id ?: "current transport"}")
            negotiatedTransports.value = emptyList()
            recommendedUpgrade.value = null
            return
        }
        val local = CapabilityProfile.local(deviceName)
        val remote = CapabilityProfile(peer.platform, peer.capabilities, peer.name)
        val ranked = TransportNegotiator.negotiate(local, remote)
        negotiatedTransports.value = ranked
        recommendedUpgrade.value = TransportNegotiator.upgradeTarget(ranked, technology)

        diag("negotiated mutual transports (best-first): ${ranked.joinToString { it.technology.id }.ifEmpty { "(none)" }}")
        recommendedUpgrade.value?.let {
            diag("⚡ faster link available: ${it.technology.id} (q=${it.technology.quality}) — we'd be ${it.localRole}")
        }
    }

    // ── Resume helpers ───────────────────────────────────────────────────────
    /** Bytes still owed to the receiver, accounting for what's already on disk. */
    private fun computeRemainingBytes(mf: TransferManifest, sessionMatches: Boolean): Long {
        if (!sessionMatches) return mf.totalBytes
        var remaining = 0L
        for ((i, entry) in mf.entries.withIndex()) {
            val state = receiverLedger[i]
            remaining += when {
                state == null -> entry.sizeBytes
                state.sizeBytes != entry.sizeBytes -> entry.sizeBytes // size mismatch, re-send whole file
                state.bytesWritten >= entry.sizeBytes -> 0L
                else -> entry.sizeBytes - state.bytesWritten
            }
        }
        return remaining
    }

    /** Find the first not-yet-complete file, return (index, byteOffsetToResumeAt). */
    private fun computeResumePoint(mf: TransferManifest): Pair<Int, Long> {
        for ((i, entry) in mf.entries.withIndex()) {
            val state = receiverLedger[i]
            if (state == null || state.sizeBytes != entry.sizeBytes || state.bytesWritten < entry.sizeBytes) {
                return i to (state?.bytesWritten ?: 0L).coerceAtMost(entry.sizeBytes)
            }
        }
        return 0 to 0L
    }

    private fun allocateTempPath(name: String): Path {
        var p = Path(SystemTemporaryDirectory, name)
        if (!SystemFileSystem.exists(p)) return p
        val baseName = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "").let { if (it == name) "" else ".$it" }
        var counter = 1
        while (true) {
            val candidate = Path(SystemTemporaryDirectory, "${baseName}_${counter}${ext}")
            if (!SystemFileSystem.exists(candidate)) return candidate
            counter++
        }
    }

    // ── Stall watchdogs ──────────────────────────────────────────────────────
    private fun startSenderStallWatchdog() {
        stallWatchdogJob?.cancel()
        stallWatchdogJob = p2pScope.launch {
            var lastBytesSeen = 0L
            while (isActive && !transferComplete.value) {
                delay(stallTimeoutMs)
                if (transferComplete.value) break
                val current = bytesMovedTotal
                if (current == lastBytesSeen && current > 0L) {
                    diag("send stall detected: no bytes for ${stallTimeoutMs}ms")
                    activeOutput?.let { runCatching { it.flushAndClose() } }
                    activeInput?.let { runCatching { it.cancel() } }
                    break
                }
                lastBytesSeen = current
            }
        }
    }

    private fun startReceiverStallWatchdog() {
        stallWatchdogJob?.cancel()
        stallWatchdogJob = p2pScope.launch {
            var lastBytesSeen = 0L
            while (isActive && !transferComplete.value) {
                delay(stallTimeoutMs)
                if (transferComplete.value) break
                val current = bytesMovedTotal
                if (current == lastBytesSeen && current > 0L) {
                    diag("receive stall detected: no bytes for ${stallTimeoutMs}ms")
                    markAllNonDoneAsFailed()
                    transferSpeed.value = 0L
                    activeInput?.let { runCatching { it.cancel() } }
                    viewmodel.snacky("Transfer stalled. Any completed files can still be saved.")
                    break
                }
                lastBytesSeen = current
            }
        }
    }

    // ── Misc helpers ─────────────────────────────────────────────────────────
    private fun markAllNonDoneAsFailed() {
        // Cold error/stall path. Skip the full-list copy when nothing is in flight
        // (the common case when a clean-close hits a late protocol error).
        if (fileEntries.value.none { it.status == FileTransferStatus.Active || it.status == FileTransferStatus.Pending }) return
        fileEntries.value = fileEntries.value.map {
            if (it.status == FileTransferStatus.Active || it.status == FileTransferStatus.Pending) {
                it.copy(status = FileTransferStatus.Failed)
            } else it
        }
    }

    private fun friendlyFailure(e: Throwable): String = when (e) {
        is ProtocolException -> e.message ?: "Protocol error — peer may be running a different Jetzy version"
        else -> "Connection lost. Any completed files can still be saved."
    }

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
            isSaving.value = true
            try {
                val fileItems = itemsRECEIVED.filter { it.entryType == EntryType.FILE }
                for (item in fileItems) {
                    if (item.relativePath.isNotEmpty()) {
                        val parentPath = item.relativePath.substringBeforeLast('/', "")
                        if (parentPath.isNotEmpty()) {
                            val dirs = parentPath.split('/')
                            var currentPath = destDir.path
                            for (dir in dirs) {
                                currentPath = "$currentPath/$dir"
                                val dirPath = Path(currentPath)
                                if (!SystemFileSystem.exists(dirPath)) {
                                    SystemFileSystem.createDirectories(dirPath)
                                }
                            }
                            val destPath = Path("$currentPath/${item.name}")
                            val sourcePath = item.path
                            SystemFileSystem.atomicMove(sourcePath, destPath)
                            continue
                        }
                    }
                    val platformFile = PlatformFile(item.path)
                    platformFile.atomicMove(destDir)
                }

                saveComplete.value = true
                canResume.value = false
                viewmodel.snacky("Files saved successfully!")
            } catch (e: Exception) {
                // Don't flip saveComplete — leaving it false keeps the Save button visible and,
                // with isSaving reset below, re-enabled so the user can retry to another folder.
                diag("save failed: ${e.message ?: e::class.simpleName}")
                viewmodel.snacky("Couldn't save files: ${e.message ?: "unknown error"}. Tap Save to try again.")
            } finally {
                isSaving.value = false
            }
        }
    }

    companion object {
        /** Extra headroom we require above the declared transfer size. */
        private const val SPACE_SAFETY_MARGIN = 10L * 1024 * 1024
    }
}
