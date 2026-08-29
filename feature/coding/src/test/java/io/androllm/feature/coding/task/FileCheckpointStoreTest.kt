package io.androllm.feature.coding.task

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Checkpoint store: snapshot / restore / list / delete. */
class FileCheckpointStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `create captures all files and returns a ref`() = runBlocking {
        val store = FileCheckpointStore(tmp.newFolder("cp"))
        val src = tmp.newFolder("ws")
        File(src, "a.txt").writeText("alpha")
        File(src, "sub").mkdirs()
        File(src, "sub/b.txt").writeText("beta")
        val files = store.snapshot(src)
        assertEquals(2, files.size)

        val ref = store.create("first", files)
        assertTrue(ref.id.isNotBlank())
        assertEquals(2, ref.fileCount)
        assertTrue(ref.sizeBytes > 0)
    }

    @Test
    fun `restore writes the snapshot back into a directory`() = runBlocking {
        val store = FileCheckpointStore(tmp.newFolder("cp"))
        val src = tmp.newFolder("ws")
        File(src, "x.txt").writeText("xyz")
        val ref = store.create("snap", store.snapshot(src))

        val into = tmp.newFolder("into")
        val written = store.restore(ref.id, into)
        assertEquals(1, written)
        assertEquals("xyz", File(into, "x.txt").readText())
    }

    @Test
    fun `restore into a directory with prior content overwrites only snapshot files`() = runBlocking {
        val store = FileCheckpointStore(tmp.newFolder("cp"))
        val src = tmp.newFolder("ws")
        File(src, "keep.txt").writeText("original")
        val ref = store.create("snap", store.snapshot(src))

        val into = tmp.newFolder("into")
        File(into, "other.txt").writeText("other")
        store.restore(ref.id, into)
        assertEquals("original", File(into, "keep.txt").readText())
        assertEquals("other", File(into, "other.txt").readText())
    }

    @Test
    fun `list returns checkpoints newest first`() = runBlocking {
        val store = FileCheckpointStore(tmp.newFolder("cp"))
        val src = tmp.newFolder("ws")
        val first = store.create("first", store.snapshot(src))
        Thread.sleep(5)
        val second = store.create("second", store.snapshot(src))
        val list = store.list()
        assertEquals(2, list.size)
        assertEquals(second.id, list[0].id)
        assertEquals(first.id, list[1].id)
    }

    @Test
    fun `delete removes only the requested checkpoint`() = runBlocking {
        val store = FileCheckpointStore(tmp.newFolder("cp"))
        val src = tmp.newFolder("ws")
        val a = store.create("a", store.snapshot(src))
        val b = store.create("b", store.snapshot(src))
        assertTrue(store.delete(a.id))
        assertEquals(1, store.list().size)
        assertEquals(b.id, store.list().first().id)
    }

    @Test
    fun `snapshot ignores build and vcs directories`() = runBlocking {
        val store = FileCheckpointStore(tmp.newFolder("cp"))
        val src = tmp.newFolder("ws")
        File(src, "src").mkdirs()
        File(src, "src/main.kt").writeText("kotlin")
        File(src, "build").mkdirs()
        File(src, "build/output.class").writeText("class")
        File(src, "node_modules").mkdirs()
        File(src, "node_modules/lodash.js").writeText("//")
        val files = store.snapshot(src)
        assertEquals(1, files.size)
        assertTrue(files.first().first == "src/main.kt")
    }

    @Test
    fun `snapshot skips oversize files`() = runBlocking {
        val store = FileCheckpointStore(tmp.newFolder("cp"))
        val src = tmp.newFolder("ws")
        File(src, "small.txt").writeText("ok")
        File(src, "huge.bin").writeBytes(ByteArray(2_000_000))
        val files = store.snapshot(src, maxFileBytes = 1_000_000)
        assertEquals(1, files.size)
        assertEquals("small.txt", files.first().first)
    }
}
