package jetzy.models

import io.github.vinceglb.filekit.PlatformFile

sealed interface JetzyElement {
    data class File(
        val file: PlatformFile
    ): JetzyElement

    data class Folder(
        val folder: PlatformFile
    ): JetzyElement

    data class Photo(
        val image: PlatformFile
    ): JetzyElement

    data class Video(
        val video: PlatformFile
    ): JetzyElement

    data class Text(
        val text: String
    ): JetzyElement
}