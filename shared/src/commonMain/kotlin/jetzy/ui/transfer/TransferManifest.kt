package jetzy.ui.transfer

import jetzy.utils.Platform
import kotlinx.io.files.Path
import kotlin.math.round

// ── Manifest (declared before transfer begins) ────────────────────────────────
data class TransferManifest(
    val totalFiles: Int,
    val totalBytes: Long,
    val entries: List<ManifestEntry>,
    val senderName: String,
    val senderPlatform: Platform
)

data class PeerInfo(
    val name: String,
    val platform: Platform,
)

data class ManifestEntry(
    val name: String,
    val sizeBytes: Long,
    val mimeType: String? = null
)

// ── Live per-file state during transfer ───────────────────────────────────────

data class FileTransferEntry(
    val name: String,
    val sizeBytes: Long,
    val mimeType: String? = null,
    val status: FileTransferStatus = FileTransferStatus.Pending,
    val bytesTransferred: Long = 0L
) {
    /** 0f–1f progress for just this file */
    val progress: Float get() = if (sizeBytes == 0L) 1f else bytesTransferred.toFloat() / sizeBytes

    val sizeLabel: String get() = sizeBytes.toHumanSize()

    val typeLabel: String get() = when {
        mimeType != null -> when {
            mimeType.startsWith("video/") -> "VID"
            mimeType.startsWith("image/") -> "IMG"
            mimeType.startsWith("audio/") -> "AUD"
            mimeType.contains("pdf")      -> "PDF"
            mimeType.contains("zip") || mimeType.contains("compressed") -> "ZIP"
            mimeType.contains("word") || mimeType.contains("document")  -> "DOC"
            else -> name.substringAfterLast('.', "BIN").uppercase().take(3)
        }
        else -> name.substringAfterLast('.', "BIN").uppercase().take(3)
    }
}

enum class FileTransferStatus { Pending, Active, Done, Failed }

// ── Received item (after transfer completes for a file) ───────────────────────

data class ReceivedItem(
    val name: String,
    val path: Path,
    val sizeBytes: Long,
    val mimeType: String? = null
)

// ── Helper ────────────────────────────────────────────────────────────────────
internal fun Long.toHumanSize(): String = when {
    this < 1024L -> "$this B"
    this < 1024L * 1024 -> "${round(this / 1024f * 10) / 10} KB"
    this < 1024L * 1024 * 1024 -> "${round(this / (1024f * 1024) * 10) / 10} MB"
    else -> "${round(this / (1024f * 1024 * 1024) * 100) / 100} GB"
}