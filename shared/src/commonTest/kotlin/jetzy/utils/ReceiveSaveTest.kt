package jetzy.utils

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.readByteArray
import kotlinx.io.writeString
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Per-item durability contract of [moveStagedFilesToDir]: one missing/bad file must never
 * strand the rest of the batch, and the returned temp paths must be exactly what moved —
 * the engine's retry logic ([savedTempPaths]) is only as correct as this report.
 */
class ReceiveSaveTest {

    private val testRoot = Path(SystemTemporaryDirectory, "jetzy_save_test_${Random.nextInt(1_000_000)}")
    private val destRoot = Path(testRoot, "dest")

    private fun stage(name: String, content: String = "data"): StagedReceivedFile {
        SystemFileSystem.createDirectories(testRoot)
        val p = Path(testRoot, name)
        SystemFileSystem.sink(p).buffered().use { it.writeString(content) }
        return StagedReceivedFile(tempPath = p.toString(), name = name, relativePath = "")
    }

    @AfterTest
    fun cleanup() {
        // Best-effort recursive cleanup of the per-test sandbox.
        fun rm(p: Path) {
            val meta = SystemFileSystem.metadataOrNull(p) ?: return
            if (meta.isDirectory) SystemFileSystem.list(p).forEach { rm(it) }
            runCatching { SystemFileSystem.delete(p) }
        }
        rm(testRoot)
    }

    @Test
    fun movesEveryFile_andReportsExactlyWhatMoved() {
        val a = stage("a.txt")
        val b = stage("b.txt")
        val saved = moveStagedFilesToDir(listOf(a, b), destRoot.toString())
        assertEquals(listOf(a.tempPath, b.tempPath), saved)
        assertTrue(SystemFileSystem.exists(Path(destRoot, "a.txt")))
        assertTrue(SystemFileSystem.exists(Path(destRoot, "b.txt")))
        assertFalse(SystemFileSystem.exists(Path(a.tempPath)), "temp must be moved out, not copied")
    }

    @Test
    fun missingTemp_isSkipped_withoutStrandingTheRest() {
        val a = stage("a.txt")
        val ghost = StagedReceivedFile(tempPath = Path(testRoot, "ghost.bin").toString(), name = "ghost.bin", relativePath = "")
        val b = stage("b.txt")
        val saved = moveStagedFilesToDir(listOf(a, ghost, b), destRoot.toString())
        assertEquals(listOf(a.tempPath, b.tempPath), saved, "ghost must be skipped, not reported saved")
        assertTrue(SystemFileSystem.exists(Path(destRoot, "b.txt")), "files after the bad one must still move")
    }

    @Test
    fun destinationCollision_getsSuffixedNeverOverwritten() {
        SystemFileSystem.createDirectories(destRoot)
        SystemFileSystem.sink(Path(destRoot, "a.txt")).buffered().use { it.writeString("original") }
        val a = stage("a.txt", content = "incoming")
        val saved = moveStagedFilesToDir(listOf(a), destRoot.toString())
        assertEquals(listOf(a.tempPath), saved)
        assertTrue(SystemFileSystem.exists(Path(destRoot, "a_1.txt")), "collision must suffix")
        val original = SystemFileSystem.source(Path(destRoot, "a.txt")).buffered().use { it.readByteArray() }
        assertEquals("original", original.decodeToString(), "existing file must be untouched")
    }

    @Test
    fun hostileRelativePath_cannotEscapeTheDestination() {
        val a = stage("evil.txt").copy(relativePath = "../../outside/evil.txt")
        moveStagedFilesToDir(listOf(a), destRoot.toString())
        assertFalse(
            SystemFileSystem.exists(Path(testRoot, "outside/evil.txt")),
            "zip-slip: sanitized segments must keep the file under the destination root",
        )
    }
}
