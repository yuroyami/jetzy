package jetzy.models

import kotlinx.io.RawSink

class ReceivedFile(
    val fileNameWithExt: String, //Include
    val sink: RawSink
)