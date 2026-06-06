package jetzy.managers

/**
 * Sanitizes UNTRUSTED path components that arrive in a peer's manifest — file names and relative
 * folder paths — so a malicious sender cannot escape the receive/temp directory via "..", an
 * absolute path, or embedded separators (classic path traversal / "zip-slip"). Pure + unit-tested.
 *
 * The approach is *drop*, not *resolve*: "", ".", ".." and separator-split fragments are removed, so
 * the result can only ever descend into the destination — never climb out of it.
 */
object SafePath {

    /** Directory segments that can never traverse upward. e.g. "../../a/./b" → ["a", "b"]. */
    fun safeSegments(relativePath: String): List<String> =
        relativePath.split('/', '\\')
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != "." && it != ".." }

    /** A bare filename with no separators or traversal; falls back to "file" if nothing usable remains. */
    fun safeName(name: String): String {
        val base = name.substringAfterLast('/').substringAfterLast('\\').trim()
        return if (base.isEmpty() || base == "." || base == "..") "file" else base
    }
}
