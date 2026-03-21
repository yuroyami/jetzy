package jetzy.models

import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.size
import io.github.vinceglb.filekit.source
import jetzy.ui.transfer.EntryType
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlinx.io.writeString

sealed interface JetzyElement {
    val name: String
    val source: RawSource
    val relativePath: String get() = ""
    val entryType: EntryType get() = EntryType.FILE
    suspend fun size(): Long

    data class File(val file: PlatformFile, override val relativePath: String = "") : JetzyElement {
        override val name get() = file.name
        override val source get() = file.source()
        override suspend fun size(): Long = file.size()
    }

    data class Photo(val image: PlatformFile) : JetzyElement {
        override val name get() = image.name
        override val source get() = image.source()
        override suspend fun size(): Long = image.size()

    }

    data class Video(val video: PlatformFile) : JetzyElement {
        override val name get() = video.name
        override val source get() = video.source()
        override suspend fun size(): Long = video.size()
    }

    data class Text(val text: String, override val name: String = "text.txt") : JetzyElement {
        override val source get() = Buffer().also { it.writeString(text) }
        override suspend fun size(): Long = text.encodeToByteArray().size.toLong()
        override val entryType: EntryType get() = EntryType.TEXT
    }

    data class Folder(val folder: PlatformFile) : JetzyElement {
        override val name get() = folder.name
        override val source: RawSource get() = error("Flatten folder before sending")
        override suspend fun size(): Long = error("Flatten folder before sending")
    }
}
