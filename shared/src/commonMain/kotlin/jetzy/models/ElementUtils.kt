package jetzy.models

import kotlinx.io.RawSink
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory

fun createTempFile(name: String): RawSink =
    SystemFileSystem.sink(Path(SystemTemporaryDirectory, name))