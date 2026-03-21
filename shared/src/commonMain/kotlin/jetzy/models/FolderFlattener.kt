package jetzy.models

import io.github.vinceglb.filekit.PlatformFile

/**
 * Represents a file inside a folder with its relative path preserved.
 * @param relativePath e.g. "MyFolder/subfolder/file.txt"
 * @param file the actual platform file
 */
data class FlatFile(
    val relativePath: String,
    val file: PlatformFile,
)

/**
 * Recursively list all files inside a [folder], returning each with its
 * relative path (rooted at the folder's name).
 *
 * Example: If the user selects "Photos" containing "a.jpg" and "vacation/b.jpg",
 * the result is:
 * - FlatFile("Photos/a.jpg", <PlatformFile for a.jpg>)
 * - FlatFile("Photos/vacation/b.jpg", <PlatformFile for b.jpg>)
 */
expect suspend fun flattenFolder(folder: PlatformFile): List<FlatFile>
