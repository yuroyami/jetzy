package jetzy.ui.transfer

import jetzy.models.JetzyElement

data class TransferFile(
    val element: JetzyElement,
    val name: String,
    val sizeLabel: String,
    val typeLabel: String,   // "IMG", "VID", "DOC", etc.
    val status: FileTransferStatus,
)