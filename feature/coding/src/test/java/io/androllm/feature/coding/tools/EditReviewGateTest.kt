package io.androllm.feature.coding.tools

import io.androllm.feature.coding.environment.CommandExecutor
import io.androllm.feature.coding.environment.FakeShellBackend
import io.androllm.feature.coding.tools.impl.EditFileTool
import io.androllm.feature.coding.tools.impl.WriteFileTool
import io.androllm.feature.coding.workspace.CodingWorkspace
import io.androllm.feature.coding.workspace.WorkspaceFileOps
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Tests for the major-edit review gate: large changes must be approved by the
 * user (via [EditReviewGate]) BEFORE they touch disk; rejection leaves the file
 * untouched and reports back to the model.
 */
class EditReviewGateTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var root: File
    private lateinit var context: CodingToolContext
    private val reviewed = mutableListOf<PendingFileChange>()
    private var approve = true

    @Before
    fun setUp() {
        root = tmp.newFolder("ws")
        val fileOps = WorkspaceFileOps(root)
        val executor = CommandExecutor(root, FakeShellBackend())
        context = CodingToolContext(
            workspace = CodingWorkspace("ws-1", "WS", root.canonicalPath),
            fileOps = fileOps,
            executor = executor,
            editReviewGate = { change ->
                reviewed += change
                approve
            }
        )
    }

    private fun writeArgs(path: String, content: String): JsonObject = buildJsonObject {
        put("path", path)
        put("content", content)
    }

    private fun editArgs(path: String, old: String, new: String, replaceAll: Boolean = false): JsonObject =
        buildJsonObject {
            put("path", path)
            put("old_text", old)
            put("new_text", new)
            put("replace_all", replaceAll)
        }

    private suspend fun write(path: String, content: String): CodingToolResult =
        WriteFileTool().execute(writeArgs(path, content), context)

    private suspend fun edit(path: String, old: String, new: String, replaceAll: Boolean = false): CodingToolResult =
        EditFileTool().execute(editArgs(path, old, new, replaceAll), context)

    // ── thresholds ───────────────────────────────────────────────────────────

    @Test
    fun `small new file is applied without review`() = runBlocking {
        val result = write("small.txt", "hello\nworld")
        assertTrue(result.isSuccess)
        assertTrue("small change must not hit the gate", reviewed.isEmpty())
        assertEquals("hello\nworld", File(root, "small.txt").readText())
    }

    @Test
    fun `new file over the line threshold requires review`() = runBlocking {
        approve = true
        val big = (1..EditReviewThresholds.NEW_FILE_LINES + 10).joinToString("\n") { "line $it" }
        val result = write("big.txt", big)
        assertTrue(result.isSuccess)
        assertEquals(1, reviewed.size)
        assertEquals(ChangeKind.CREATE, reviewed.single().kind)
        assertEquals("big.txt", reviewed.single().path)
        assertTrue(reviewed.single().unifiedDiff.contains("+ line 1"))
        assertTrue(File(root, "big.txt").exists())
    }

    @Test
    fun `rejected new file is never written`() = runBlocking {
        approve = false
        val big = (1..EditReviewThresholds.NEW_FILE_LINES + 10).joinToString("\n") { "line $it" }
        val result = write("big.txt", big) as CodingToolResult.Failure
        assertFalse(result.retryable)
        assertTrue(result.summary.contains("REJECTED"))
        assertFalse("rejected change must not touch disk", File(root, "big.txt").exists())
    }

    @Test
    fun `small edit is applied without review`() = runBlocking {
        File(root, "code.txt").writeText("one two three")
        approve = false // would reject if asked — must not be asked
        val result = edit("code.txt", "two", "TWO")
        assertTrue(result.isSuccess)
        assertTrue(reviewed.isEmpty())
        assertEquals("one TWO three", File(root, "code.txt").readText())
    }

    @Test
    fun `large edit is gated and rejection keeps original content`() = runBlocking {
        val original = (1..50).joinToString("\n") { "old line $it" }
        File(root, "code.txt").writeText(original)
        approve = false

        // Rewriting every line = 50 adds + 50 removes > EDIT_LINES threshold.
        val result = edit("code.txt", "old line", "new line", replaceAll = true) as CodingToolResult.Failure

        assertTrue(result.summary.contains("REJECTED"))
        assertEquals(ChangeKind.EDIT, reviewed.single().kind)
        assertEquals(original, File(root, "code.txt").readText())
    }

    @Test
    fun `approved large edit is applied`() = runBlocking {
        val original = (1..50).joinToString("\n") { "old line $it" }
        File(root, "code.txt").writeText(original)
        approve = true

        val result = edit("code.txt", "old line", "new line", replaceAll = true)
        assertTrue(result.isSuccess)
        assertTrue(File(root, "code.txt").readText().startsWith("new line 1"))
    }

    @Test
    fun `no gate wired means everything auto-applies`() = runBlocking {
        val ungated = CodingToolContext(
            workspace = CodingWorkspace("ws-1", "WS", root.canonicalPath),
            fileOps = WorkspaceFileOps(root),
            executor = CommandExecutor(root, FakeShellBackend()),
            editReviewGate = null
        )
        val big = (1..EditReviewThresholds.NEW_FILE_LINES + 10).joinToString("\n") { "line $it" }
        val result = WriteFileTool().execute(writeArgs("auto.txt", big), ungated)
        assertTrue(result.isSuccess)
        assertTrue(File(root, "auto.txt").exists())
    }

    // ── diff payloads for the UI ─────────────────────────────────────────────

    @Test
    fun `write success carries diff data for the UI card`() = runBlocking {
        val result = write("a.txt", "one\ntwo") as CodingToolResult.Success
        assertEquals("a.txt", (result.data["path"] as JsonPrimitive).content)
        assertEquals(true, (result.data["created"] as JsonPrimitive).content.toBoolean())
        assertEquals(2, (result.data["added"] as JsonPrimitive).content.toInt())
        assertTrue((result.data["diff"] as JsonPrimitive).content.contains("+ one"))
    }

    @Test
    fun `edit success carries diff stats in summary and data`() = runBlocking {
        File(root, "b.txt").writeText("alpha\nbeta\ngamma")
        val result = edit("b.txt", "beta", "BETA") as CodingToolResult.Success
        assertTrue(result.summary.contains("(+1"))
        assertEquals(1, (result.data["added"] as JsonPrimitive).content.toInt())
        assertEquals(1, (result.data["removed"] as JsonPrimitive).content.toInt())
        val diff = (result.data["diff"] as JsonPrimitive).content
        assertTrue(diff.contains("- beta"))
        assertTrue(diff.contains("+ BETA"))
    }
}
