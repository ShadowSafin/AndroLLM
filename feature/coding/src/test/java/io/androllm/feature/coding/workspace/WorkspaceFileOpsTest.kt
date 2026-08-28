package io.androllm.feature.coding.workspace

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Sandboxed file primitives: read/write/edit/grep/list/tree/summary + security. */
class WorkspaceFileOpsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun ops(): WorkspaceFileOps {
        val root = tmp.newFolder("ws")
        return WorkspaceFileOps(root)
    }

    @Test
    fun `write then read returns same content`() {
        val ops = ops()
        ops.writeFile("src/Hello.kt", "fun main() { println(\"hi\") }")
        val content = ops.readFile("src/Hello.kt")
        assertEquals("fun main() { println(\"hi\") }", content)
    }

    @Test
    fun `write creates parent directories`() {
        val ops = ops()
        ops.writeFile("a/b/c/deep.txt", "deep")
        assertTrue(ops.exists("a/b/c/deep.txt"))
    }

    @Test
    fun `edit replaces unique text`() {
        val ops = ops()
        ops.writeFile("f.txt", "hello world")
        val count = ops.editFile("f.txt", "world", "there")
        assertEquals(1, count)
        assertEquals("hello there", ops.readFile("f.txt"))
    }

    @Test
    fun `edit fails when old text not found`() {
        val ops = ops()
        ops.writeFile("f.txt", "hello")
        try {
            ops.editFile("f.txt", "missing", "x")
            fail("expected WorkspaceIoException")
        } catch (e: WorkspaceIoException) {
            assertTrue(e.message!!.contains("not found"))
        }
    }

    @Test
    fun `edit requires more context for multiple matches`() {
        val ops = ops()
        ops.writeFile("f.txt", "a a a")
        try {
            ops.editFile("f.txt", "a", "b", replaceAll = false)
            fail("expected ambiguous-match failure")
        } catch (e: WorkspaceIoException) {
            assertTrue(e.message!!.contains("3 times") || e.message!!.contains("replace_all"))
        }
    }

    @Test
    fun `edit replace_all replaces every occurrence`() {
        val ops = ops()
        ops.writeFile("f.txt", "a a a")
        val count = ops.editFile("f.txt", "a", "b", replaceAll = true)
        assertEquals(3, count)
        assertEquals("b b b", ops.readFile("f.txt"))
    }

    @Test
    fun `grep finds pattern with file and line number`() {
        val ops = ops()
        ops.writeFile("src/A.kt", "line one\nfun target() {}\nline three")
        ops.writeFile("src/B.txt", "nothing here")
        val matches = ops.grep("target")
        assertEquals(1, matches.size)
        assertEquals("src/A.kt", matches[0].relativePath)
        assertEquals(2, matches[0].lineNumber)
    }

    @Test
    fun `grep respects include glob filter`() {
        val ops = ops()
        ops.writeFile("a.kt", "needle")
        ops.writeFile("b.txt", "needle")
        val onlyKt = ops.grep("needle", include = "*.kt")
        assertEquals(1, onlyKt.size)
        assertEquals("a.kt", onlyKt[0].relativePath)
    }

    @Test
    fun `list dir returns files and folders sorted`() {
        val ops = ops()
        ops.writeFile("z.txt", "z")
        ops.writeFile("a.txt", "a")
        ops.writeFile("dir/inner.txt", "i")
        val entries = ops.listDir("")
        assertTrue(entries.any { it.isDirectory && it.name == "dir" })
        assertTrue(entries.any { !it.isDirectory && it.name == "a.txt" })
    }

    @Test
    fun `file tree returns nested structure`() {
        val ops = ops()
        ops.writeFile("src/main/A.kt", "x")
        val tree = ops.fileTree(maxDepth = 3)
        assertTrue(tree.isDirectory)
        assertTrue(tree.children.any { it.name == "src" })
    }

    @Test
    fun `summarize counts files and extensions`() {
        val ops = ops()
        ops.writeFile("a.kt", "x")
        ops.writeFile("b.kt", "y")
        ops.writeFile("c.txt", "z")
        val s = ops.summarize()
        assertEquals(3, s.fileCount)
        assertEquals(2, s.filesByExtension["kt"])
        assertEquals(1, s.filesByExtension["txt"])
    }

    @Test
    fun `write outside workspace is rejected`() {
        val root = tmp.newFolder("ws")
        val ops = WorkspaceFileOps(root)
        try {
            ops.writeFile("../escape.txt", "bad")
            fail("expected security exception")
        } catch (e: WorkspaceSecurityException) {
            // expected — path traversal blocked
        }
        assertFalse(File(root.parentFile, "escape.txt").exists())
    }

    @Test
    fun `read outside workspace is rejected`() {
        val root = tmp.newFolder("ws")
        val secret = File(root.parentFile, "secret.txt").apply { writeText("top secret") }
        val ops = WorkspaceFileOps(root)
        try {
            ops.readFile(secret.absolutePath)
            fail("expected security exception")
        } catch (e: WorkspaceSecurityException) {
            // expected
        }
    }
}
