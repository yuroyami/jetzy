package jetzy.managers

/**
 * Streaming CRC32 (IEEE 802.3 polynomial 0xEDB88320).
 *
 * Small, allocation-free, Kotlin-only so it works the same on every target
 * without pulling in `java.util.zip`. Used for end-to-end file integrity
 * checks: drop one byte on the wire and the CRC changes.
 */
class Crc32 {
    private var value: Int = 0.inv()

    fun update(buf: ByteArray, offset: Int = 0, length: Int = buf.size) {
        var v = value
        val end = offset + length
        for (i in offset until end) {
            v = (v ushr 8) xor TABLE[(v xor buf[i].toInt()) and 0xFF]
        }
        value = v
    }

    fun finish(): Int = value.inv()

    fun reset() { value = 0.inv() }

    companion object {
        private val TABLE: IntArray = IntArray(256).also { t ->
            for (i in 0 until 256) {
                var c = i
                repeat(8) {
                    c = if (c and 1 != 0) (c ushr 1) xor 0xEDB88320.toInt() else c ushr 1
                }
                t[i] = c
            }
        }
    }
}
