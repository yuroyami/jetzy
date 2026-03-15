package jetzy.ui.transfer

data class TransferFile(
    val name: String,
    val sizeLabel: String,
    val typeLabel: String,   // "IMG", "VID", "DOC", etc.
    val status: FileTransferStatus,
)