package io.androllm.feature.coding.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for the LCS line diff used by diff cards and the review gate. */
class LineDiffTest {

    @Test
    fun `identical texts produce only context lines`() {
        val lines = LineDiff.diff("a\nb\nc", "a\nb\nc")!!
        assertEquals(3, lines.size)
        assertTrue(lines.all { it.kind == DiffKind.CONTEXT })
        assertEquals(DiffStats(0, 0), LineDiff.stats(lines))
    }

    @Test
    fun `added line is detected`() {
        val lines = LineDiff.diff("a\nc", "a\nb\nc")!!
        assertEquals(
            listOf(DiffKind.CONTEXT, DiffKind.ADD, DiffKind.CONTEXT),
            lines.map { it.kind }
        )
        assertEquals("b", lines[1].text)
        assertEquals(DiffStats(added = 1, removed = 0), LineDiff.stats(lines))
    }

    @Test
    fun `deleted line is detected`() {
        val lines = LineDiff.diff("a\nb\nc", "a\nc")!!
        assertEquals(
            listOf(DiffKind.CONTEXT, DiffKind.DELETE, DiffKind.CONTEXT),
            lines.map { it.kind }
        )
        assertEquals("b", lines[1].text)
        assertEquals(DiffStats(added = 0, removed = 1), LineDiff.stats(lines))
    }

    @Test
    fun `modified line appears as delete plus add`() {
        val lines = LineDiff.diff("a\nold\nc", "a\nnew\nc")!!
        val stats = LineDiff.stats(lines)
        assertEquals(1, stats.added)
        assertEquals(1, stats.removed)
        assertTrue(lines.any { it.kind == DiffKind.DELETE && it.text == "old" })
        assertTrue(lines.any { it.kind == DiffKind.ADD && it.text == "new" })
    }

    @Test
    fun `creating a file from empty marks every line added`() {
        val lines = LineDiff.diff("", "one\ntwo\nthree")!!
        // "" splits to one empty context-ish line; the three real lines are adds.
        assertEquals(3, LineDiff.stats(lines).added)
    }

    @Test
    fun `renderUnified uses markers and hunk separators`() {
        val oldText = (1..20).joinToString("\n") { "line$it" }
        val newText = (1..20).joinToString("\n") { if (it == 2) "line2-changed" else if (it == 19) "line19-changed" else "line$it" }
        val lines = LineDiff.diff(oldText, newText)!!
        val rendered = LineDiff.renderUnified(lines)

        assertTrue(rendered.contains("- line2"))
        assertTrue(rendered.contains("+ line2-changed"))
        assertTrue(rendered.contains("- line19"))
        assertTrue(rendered.contains("+ line19-changed"))
        // Far-apart changes are split into hunks with an @@ separator.
        assertTrue(rendered.contains("@@"))
        // Unchanged lines far from both changes are trimmed away.
        assertFalse(rendered.contains("line10"))
    }

    @Test
    fun `renderUnified keeps context radius around changes`() {
        val oldText = (1..10).joinToString("\n") { "line$it" }
        val newText = (1..10).joinToString("\n") { if (it == 5) "line5-changed" else "line$it" }
        val lines = LineDiff.diff(oldText, newText)!!
        val rendered = LineDiff.renderUnified(lines, contextRadius = 2)

        assertTrue(rendered.contains("line3"))
        assertTrue(rendered.contains("line7"))
        // Outside the radius: trimmed away.
        assertFalse(rendered.contains("line1"))
        assertFalse(rendered.contains("line10"))
    }

    @Test
    fun `renderUnified caps very long diffs keeping the tail`() {
        val oldText = (1..100).joinToString("\n") { "old$it" }
        val newText = (1..100).joinToString("\n") { "new$it" }
        val lines = LineDiff.diff(oldText, newText)!!
        val rendered = LineDiff.renderUnified(lines, maxChars = 500)
        val marker = "\u2026[diff trimmed]\n"
        assertTrue(rendered.startsWith(marker.trimEnd()))
        assertTrue(rendered.length <= 500 + marker.length)
    }

    @Test
    fun `diff returns null when either side is too large`() {
        val huge = (1..LineDiff.MAX_DIFF_LINES + 1).joinToString("\n") { "line$it" }
        assertNull(LineDiff.diff(huge, "x"))
        assertNull(LineDiff.diff("x", huge))
    }

    @Test
    fun `stats render format`() {
        assertEquals("+3 −1", DiffStats(added = 3, removed = 1).render())
    }
}
