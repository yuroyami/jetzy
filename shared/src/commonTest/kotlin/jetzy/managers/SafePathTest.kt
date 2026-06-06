package jetzy.managers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Path-traversal / zip-slip defense for untrusted manifest names + relative paths. */
class SafePathTest {

    @Test
    fun safeSegments_dropsDotDot_cannotClimbOut() {
        assertEquals(listOf("a", "b"), SafePath.safeSegments("../../a/./b"))
        assertEquals(listOf("etc", "passwd"), SafePath.safeSegments("../../etc/passwd"))
        assertTrue(".." !in SafePath.safeSegments("a/../../b"))
    }

    @Test
    fun safeSegments_dropsAbsoluteAndEmpty() {
        assertEquals(listOf("abs", "path"), SafePath.safeSegments("/abs/path"))
        assertEquals(emptyList(), SafePath.safeSegments(""))
        assertEquals(emptyList(), SafePath.safeSegments("/"))
    }

    @Test
    fun safeSegments_handlesBackslashSeparators() {
        assertEquals(listOf("a", "b"), SafePath.safeSegments("a\\b"))
        assertEquals(listOf("win", "path"), SafePath.safeSegments("..\\..\\win\\path"))
    }

    @Test
    fun safeName_stripsSeparatorsAndTraversal() {
        assertEquals("file.txt", SafePath.safeName("../../file.txt"))
        assertEquals("b", SafePath.safeName("a/b"))
        assertEquals("c", SafePath.safeName("a\\b\\c"))
        assertEquals("x.png", SafePath.safeName("x.png"))
    }

    @Test
    fun safeName_fallsBackWhenNothingUsable() {
        assertEquals("file", SafePath.safeName(".."))
        assertEquals("file", SafePath.safeName("."))
        assertEquals("file", SafePath.safeName(""))
        assertEquals("file", SafePath.safeName("a/.."))
    }

    @Test
    fun aTraversalPayload_isFullyNeutralized() {
        val rel = "../../../../etc"
        val segs = SafePath.safeSegments(rel)
        assertFalse(segs.any { it == ".." || it.isEmpty() })
        assertEquals(listOf("etc"), segs)
    }
}
