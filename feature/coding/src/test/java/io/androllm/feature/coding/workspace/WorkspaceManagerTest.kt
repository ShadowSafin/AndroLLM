package io.androllm.feature.coding.workspace

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** In-memory [WorkspaceStore] for tests. */
class InMemoryWorkspaceStore : WorkspaceStore {
    var activeId = ""
    var session = CodingSessionState()
    var registry = mutableListOf<CodingWorkspace>()
    override suspend fun saveActiveWorkspaceId(id: String) { activeId = id }
    override suspend fun loadActiveWorkspaceId(): String = activeId
    override suspend fun saveSession(state: CodingSessionState) { session = state }
    override suspend fun loadSession(): CodingSessionState = session
    override suspend fun loadRegistry(): List<CodingWorkspace> = registry.toList()
    override suspend fun saveRegistry(workspaces: List<CodingWorkspace>) {
        registry = workspaces.toMutableList()
    }
    override suspend fun clear() { activeId = ""; session = CodingSessionState() }
}

/** Workspace lifecycle: create, open-in-place, list, select, validate, persist. */
class WorkspaceManagerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun manager(legacyRoot: File? = null): WorkspaceManager {
        val root = tmp.newFolder("root")
        return WorkspaceManager(
            rootProvider = object : WorkspaceRootProvider {
                override fun rootDir(): File = root
                override fun legacyRootDir(): File? = legacyRoot
            },
            store = InMemoryWorkspaceStore()
        )
    }

    @Test
    fun `create workspace makes a real directory with metadata`() = runBlocking {
        val mgr = manager()
        val ws = mgr.createWorkspace("My Project")
        assertEquals("My Project", ws.name)
        assertTrue(File(ws.absolutePath).exists())
        assertTrue(File(ws.absolutePath, WorkspaceManager.META_FILE).exists())
    }

    @Test
    fun `list workspaces returns created ones newest first`() = runBlocking {
        val mgr = manager()
        mgr.createWorkspace("first")
        Thread.sleep(5)
        mgr.createWorkspace("second")
        val list = mgr.listWorkspaces()
        assertEquals(2, list.size)
        assertEquals("second", list.first().name)
    }

    @Test
    fun `open folder uses the folder directly without copying`() = runBlocking {
        val mgr = manager()
        val project = tmp.newFolder("my-project")
        File(project, "main.py").writeText("print('hi')")

        val ws = mgr.openFolder(project.absolutePath)

        assertEquals(WorkspaceSource.OPENED, ws.source)
        assertEquals(project.absolutePath, ws.absolutePath)
        assertEquals("my-project", ws.name)
        // The folder is used in place: nothing is copied anywhere.
        assertTrue(File(ws.absolutePath, "main.py").exists())
        assertTrue(mgr.listWorkspaces().any { it.id == ws.id })
    }

    @Test
    fun `open folder twice returns the same stable workspace`() = runBlocking {
        val mgr = manager()
        val project = tmp.newFolder("proj")
        val first = mgr.openFolder(project.absolutePath)
        val second = mgr.openFolder(project.absolutePath)
        assertEquals(first.id, second.id)
        assertEquals(1, mgr.listWorkspaces().count { it.absolutePath == project.absolutePath })
    }

    @Test
    fun `open folder fails cleanly when the folder does not exist`() = runBlocking {
        val mgr = manager()
        try {
            mgr.openFolder(File(tmp.root, "missing").absolutePath)
            fail("expected IllegalStateException for a missing folder")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("Folder not found"))
        }
        Unit
    }

    @Test
    fun `opened folder survives listing via the registry`() = runBlocking {
        val store = InMemoryWorkspaceStore()
        val root = tmp.newFolder("root")
        val project = tmp.newFolder("external-proj")
        val first = WorkspaceManager(WorkspaceRootProvider { root }, store)
        first.openFolder(project.absolutePath)

        // A fresh manager over the same store still sees the opened folder.
        val second = WorkspaceManager(WorkspaceRootProvider { root }, store)
        val listed = second.listWorkspaces()
        assertEquals(1, listed.size)
        assertEquals(project.absolutePath, listed.first().absolutePath)
    }

    @Test
    fun `delete opened workspace forgets it but never deletes the folder`() = runBlocking {
        val mgr = manager()
        val project = tmp.newFolder("keep-me")
        File(project, "data.txt").writeText("precious")
        val ws = mgr.openFolder(project.absolutePath)
        mgr.setActive(ws)

        mgr.deleteWorkspace(ws)

        assertTrue("user folder must survive removal", project.exists())
        assertTrue(File(project, "data.txt").exists())
        assertNull(mgr.getActive())
        assertTrue(mgr.listWorkspaces().none { it.id == ws.id })
    }

    @Test
    fun `set active then get active round-trips`() = runBlocking {
        val mgr = manager()
        val ws = mgr.createWorkspace("proj")
        mgr.setActive(ws)
        val active = mgr.getActive()
        assertNotNull(active)
        assertEquals(ws.id, active!!.id)
    }

    @Test
    fun `validate current returns active when dir exists`() = runBlocking {
        val mgr = manager()
        val ws = mgr.createWorkspace("proj")
        mgr.setActive(ws)
        assertNotNull(mgr.validateCurrent())
    }

    @Test
    fun `validate current clears stale reference when dir deleted`() = runBlocking {
        val mgr = manager()
        val ws = mgr.createWorkspace("proj")
        mgr.setActive(ws)
        File(ws.absolutePath).deleteRecursively()
        assertNull("deleted workspace must not validate", mgr.validateCurrent())
        assertNull(mgr.getActive())
    }

    @Test
    fun `legacy root workspaces stay listed after the root move`() = runBlocking {
        val legacy = tmp.newFolder("legacy-root")
        val mgr = manager(legacyRoot = legacy)
        // Simulate a workspace created by an older app version under the old root.
        val oldDir = File(legacy, "old-uuid").apply { mkdirs() }
        val meta = CodingWorkspace(
            id = "old-uuid",
            name = "Old project",
            absolutePath = oldDir.absolutePath,
            source = WorkspaceSource.CREATED,
            createdAtEpochMs = 1L
        )
        File(oldDir, WorkspaceManager.META_FILE).writeText(
            kotlinx.serialization.json.Json.encodeToString(CodingWorkspace.serializer(), meta)
        )

        val list = mgr.listWorkspaces()
        assertTrue("legacy workspace must still be listed", list.any { it.id == "old-uuid" })
    }

    @Test
    fun `session state persists workspace id and history`() = runBlocking {
        val mgr = manager()
        val ws = mgr.createWorkspace("proj")
        mgr.setActive(ws)
        mgr.saveSession(mgr.loadSession().withCommand("ls").withRecentFile("a.kt"))
        val session = mgr.loadSession()
        assertEquals(ws.id, session.workspaceId)
        assertTrue(session.commandHistory.contains("ls"))
        assertTrue(session.recentFiles.contains("a.kt"))
    }

    @Test
    fun `delete workspace removes directory and clears active`() = runBlocking {
        val mgr = manager()
        val ws = mgr.createWorkspace("proj")
        mgr.setActive(ws)
        mgr.deleteWorkspace(ws)
        assertFalse(File(ws.absolutePath).exists())
        assertNull(mgr.getActive())
    }

    @Test
    fun `file ops are scoped to the workspace`() = runBlocking {
        val mgr = manager()
        val ws = mgr.createWorkspace("proj")
        val ops = mgr.fileOps(ws)
        ops.writeFile("hello.txt", "hi")
        assertEquals("hi", ops.readFile("hello.txt"))
    }
}
