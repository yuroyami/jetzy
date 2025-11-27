package jetzy.managers

data class TransferMetadata(
    val fileCount: Int,
    val totalSize: Long,
    val compressionEnabled: Boolean = false
)