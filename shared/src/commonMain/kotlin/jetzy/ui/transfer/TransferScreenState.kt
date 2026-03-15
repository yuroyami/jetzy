package jetzy.ui.transfer

data class TransferScreenState(
    val senderName: String,
    val receiverName: String,
    val completedCount: Int,
    val totalCount: Int,
    val isSender: Boolean,
)