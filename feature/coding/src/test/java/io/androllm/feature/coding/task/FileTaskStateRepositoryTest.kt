package io.androllm.feature.coding.task

import kotlinx.coroutines.runBlocking
import io.androllm.feature.coding.tools.PlanStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Persistent task state: save / load / clear round-trip. */
class FileTaskStateRepositoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store() = FileTaskStateRepository(tmp.newFolder("tasks"))

    @Test
    fun `save then load round-trips the full state`() = runBlocking {
        val s = store()
        val state = CodingTaskState(
            workspaceId = "ws-1",
            objective = "Build a login page",
            plan = listOf(PlanStep.pending("Create the form"), PlanStep.inProgress("Wire the API")),
            planApproved = true,
            checkpoints = listOf(CheckpointRef(id = "cp1", name = "Initial", createdAtMs = 1L, fileCount = 5, sizeBytes = 100)),
            changedFiles = listOf(FileChangeRecord(path = "src/login.tsx", kind = "create")),
            lastTestResult = TestResultRecord(framework = "npm test", passed = 4, failed = 1, rawOutputTail = "1 fail")
        )
        s.save(state)

        val loaded = s.load("ws-1")
        assertNotNull(loaded)
        assertEquals("Build a login page", loaded!!.objective)
        assertEquals(2, loaded.plan.size)
        assertEquals("Create the form", loaded.plan[0].text)
        assertTrue(loaded.planApproved)
        assertEquals(1, loaded.checkpoints.size)
        assertEquals(1, loaded.changedFiles.size)
        assertEquals("create", loaded.changedFiles.first().kind)
        assertEquals(4, loaded.lastTestResult!!.passed)
    }

    @Test
    fun `load returns null when nothing was saved`() = runBlocking {
        assertNull(store().load("missing"))
    }

    @Test
    fun `clear removes the file`() = runBlocking {
        val s = store()
        s.save(CodingTaskState(workspaceId = "ws-2", plan = listOf(PlanStep.pending("a"))))
        s.clear("ws-2")
        assertNull(s.load("ws-2"))
    }

    @Test
    fun `save bumps version on every write`() = runBlocking {
        val s = store()
        s.save(CodingTaskState(workspaceId = "ws-3", plan = listOf(PlanStep.pending("x"))))
        val v1 = s.load("ws-3")!!.version
        s.save(s.load("ws-3")!!.copy(plan = listOf(PlanStep.pending("y"))))
        val v2 = s.load("ws-3")!!.version
        assertTrue("version should advance: $v1 -> $v2", v2 > v1)
    }

    @Test
    fun `isResumable reflects lifecycle`() {
        val draft = CodingTaskState(workspaceId = "x", plan = emptyList(), lifecycle = TaskLifecycle.AWAITING_APPROVAL)
        assertEquals(false, draft.isResumable)
        val active = draft.copy(plan = listOf(PlanStep.pending("do")))
        assertTrue(active.isResumable)
    }
}
