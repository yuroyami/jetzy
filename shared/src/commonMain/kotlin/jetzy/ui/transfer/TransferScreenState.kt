package jetzy.ui.transfer

data class TransferScreenState(
    val senderName: String,
    val receiverName: String,
    val progress: Float,          // 0f..1f
    val completedCount: Int,
    val totalCount: Int,
    val speedLabel: String,       // e.g. "4.2 MB/s"
    val remainingLabel: String,   // e.g. "~14s remaining"
    val isSender: Boolean,
)