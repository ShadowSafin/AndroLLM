package io.androllm.feature.coding.agent

import io.androllm.core.cloud.model.CloudChatMessage
import io.androllm.core.cloud.model.CloudStreamEvent
import io.androllm.core.cloud.model.CloudTool
import io.androllm.feature.coding.workspace.CodingSessionState
import io.androllm.feature.coding.workspace.CodingWorkspace
import io.androllm.feature.coding.workspace.WorkspaceManager
import io.androllm.feature.coding.workspace.WorkspaceRootProvider
import io.androllm.feature.coding.workspace.WorkspaceStore
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Cloud-only + workspace-required gating for the coding agent. */
class CodingAvailabilityTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class FakeCloud(var configured: Boolean = true, var throwOnCheck: Boolean = false) : CodingCloudClient {
        override suspend fun isConfigured(): Boolean {
            if (throwOnCheck) error("boom")
            return configured
        }
        override suspend fun maxOutputTokens(): Long? = null
        override suspend fun activeModelLabel(): String = "fake-model"
        override fun stream(
            messages: List<CloudChatMessage>,
            tools: List<CloudTool>,
            sessionId: String?,
            maxTokens: Int?
        ): Flow<CloudStreamEvent> = flowOf()
    }

    private class MemStore : WorkspaceStore {
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

    private fun manager(root: File, store: MemStore): WorkspaceManager = WorkspaceManager(
        rootProvider = WorkspaceRootProvider { root },
        store = store
    )

    @Test
    fun `no cloud config blocks with NeedsCloud even when a workspace exists`() = runBlocking {
        val root = tmp.newFolder("root")
        val store = MemStore()
        val mgr = manager(root, store)
        val ws = mgr.createWorkspace("proj")
        mgr.setActive(ws)

        val gate = CodingAvailabilityChecker(FakeCloud(configured = false), mgr).check()
        assertTrue("expected NeedsCloud, got $gate", gate is CodingGate.NeedsCloud)
        assertTrue((gate as CodingGate.NeedsCloud).message.contains("cloud"))
    }

    @Test
    fun `cloud check failure is treated as not configured`() = runBlocking {
        val mgr = manager(tmp.newFolder("root2"), MemStore())
        val gate = CodingAvailabilityChecker(FakeCloud(throwOnCheck = true), mgr).check()
        assertTrue(gate is CodingGate.NeedsCloud)
    }

    @Test
    fun `cloud ready but no workspace blocks with NeedsWorkspace`() = runBlocking {
        val mgr = manager(tmp.newFolder("root3"), MemStore())
        val gate = CodingAvailabilityChecker(FakeCloud(), mgr).check()
        assertTrue("expected NeedsWorkspace, got $gate", gate is CodingGate.NeedsWorkspace)
    }

    @Test
    fun `cloud plus active workspace is Ready`() = runBlocking {
        val store = MemStore()
        val mgr = manager(tmp.newFolder("root4"), store)
        val ws = mgr.createWorkspace("proj")
        mgr.setActive(ws)

        val gate = CodingAvailabilityChecker(FakeCloud(), mgr).check()
        assertTrue("expected Ready, got $gate", gate is CodingGate.Ready)
    }

    @Test
    fun `deleted workspace directory falls back to NeedsWorkspace`() = runBlocking {
        val store = MemStore()
        val mgr = manager(tmp.newFolder("root5"), store)
        val ws = mgr.createWorkspace("proj")
        mgr.setActive(ws)
        File(ws.absolutePath).deleteRecursively()

        val gate = CodingAvailabilityChecker(FakeCloud(), mgr).check()
        assertTrue(gate is CodingGate.NeedsWorkspace)
        assertTrue("stale active id must be cleared", store.activeId.isBlank())
    }
}
