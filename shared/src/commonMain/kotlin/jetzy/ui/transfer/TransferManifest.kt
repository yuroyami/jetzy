package jetzy.ui.transfer

import jetzy.utils.Platform
import kotlinx.io.files.Path
import kotlin.math.round

/** Type of entry in the transfer manifest */
enum class EntryType { FILE, TEXT }

// ── Manifest (declared before transfer begins) ────────────────────────────────
data class TransferManifest(
    val totalFiles: Int,
    val totalBytes: Long,
    val entries: List<ManifestEntry>,
    val senderName: String,
    val senderPlatform: Platform
) {
    val hasFiles: Boolean get() = entries.any { it.entryType == EntryType.FILE }
    val hasTexts: Boolean get() = entries.any { it.entryType == EntryType.TEXT }
}

data class PeerInfo(
    val name: String,
    val platform: Platform,
)

data class ManifestEntry(
    val name: String,
    val sizeBytes: Long,
    val mimeType: String? = null,
    /** Relative path for files inside folders (e.g. "MyFolder/sub/file.txt"), empty for top-level items */
    val relativePath: String = "",
    /** Whether this entry is a file or a text snippet */
    val entryType: EntryType = EntryType.FILE,
)

// ── Live per-file state during transfer ───────────────────────────────────────

data class FileTransferEntry(
    val name: String,
    val sizeBytes: Long,
    val mimeType: String? = null,
    val relativePath: String = "",
    val entryType: EntryType = EntryType.FILE,
    val status: FileTransferStatus = FileTransferStatus.Pending,
    val bytesTransferred: Long = 0L,
    /** For TEXT entries — the received text content (populated after transfer) */
    val textContent: String? = null,
) {
    /** 0f–1f progress for just this file */
    val progress: Float get() = if (sizeBytes == 0L) 1f else bytesTransferred.toFloat() / sizeBytes

    val sizeLabel: String get() = sizeBytes.toHumanSize()

    val isText: Boolean get() = entryType == EntryType.TEXT

    val displayName: String get() = when {
        relativePath.isNotEmpty() -> relativePath
        else -> name
    }

    val typeLabel: String get() = when {
        entryType == EntryType.TEXT -> "TXT"
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
    val mimeType: String? = null,
    val relativePath: String = "",
    val entryType: EntryType = EntryType.FILE,
    /** For TEXT entries — the text content */
    val textContent: String? = null,
)

// ── Helper ────────────────────────────────────────────────────────────────────
internal fun Long.toHumanSize(): String = when {
    this < 1024L -> "$this B"
    this < 1024L * 1024 -> "${round(this / 1024f * 10) / 10} KB"
    this < 1024L * 1024 * 1024 -> "${round(this / (1024f * 1024) * 10) / 10} MB"
    else -> "${round(this / (1024f * 1024 * 1024) * 100) / 100} GB"
}