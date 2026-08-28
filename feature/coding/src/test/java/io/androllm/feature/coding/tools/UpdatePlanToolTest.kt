package io.androllm.feature.coding.tools

import io.androllm.feature.coding.environment.CommandExecutor
import io.androllm.feature.coding.environment.FakeShellBackend
import io.androllm.feature.coding.tools.impl.UpdatePlanTool
import io.androllm.feature.coding.workspace.CodingWorkspace
import io.androllm.feature.coding.workspace.WorkspaceFileOps
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Unit tests for the update_plan tool (visible task plan). */
class UpdatePlanToolTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var context: CodingToolContext
    private val plans = mutableListOf<List<PlanStep>>()

    @Before
    fun setUp() {
        val root = tmp.newFolder("ws")
        context = CodingToolContext(
            workspace = CodingWorkspace("ws-1", "WS", root.canonicalPath),
            fileOps = WorkspaceFileOps(root),
            executor = CommandExecutor(root, FakeShellBackend()),
            onPlanUpdated = { plans += it }
        )
    }

    private suspend fun run(args: String): CodingToolResult =
        UpdatePlanTool().execute(Json.parseToJsonElement(args).jsonObject, context)

    @Test
    fun `parses steps and fires the plan hook`() = runBlocking {
        val result = run(
            """{"steps":[
                {"text":"Read the code","status":"done"},
                {"text":"Fix the bug","status":"in_progress"},
                {"text":"Run tests","status":"pending"}
            ]}"""
        )
        assertTrue(result.isSuccess)
        assertEquals(1, plans.size)
        val plan = plans.single()
        assertEquals(3, plan.size)
        assertEquals(PlanStepStatus.DONE, plan[0].status)
        assertEquals(PlanStepStatus.IN_PROGRESS, plan[1].status)
        assertEquals(PlanStepStatus.PENDING, plan[2].status)
        assertTrue(result.summary.contains("3 steps"))
        assertTrue(result.summary.contains("working on: Fix the bug"))
    }

    @Test
    fun `rejects more than one in_progress step`() = runBlocking {
        val result = run(
            """{"steps":[
                {"text":"a","status":"in_progress"},
                {"text":"b","status":"in_progress"}
            ]}"""
        ) as CodingToolResult.Failure
        assertTrue(result.retryable)
        assertTrue(result.summary.contains("ONE step"))
        assertTrue("rejected plan must not fire the hook", plans.isEmpty())
    }

    @Test
    fun `rejects empty step list`() = runBlocking {
        val result = run("""{"steps":[]}""") as CodingToolResult.Failure
        assertFalse(result.retryable)
    }

    @Test
    fun `rejects missing steps array`() = runBlocking {
        val result = run("""{}""") as CodingToolResult.Failure
        assertFalse(result.retryable)
    }

    @Test
    fun `rejects too many steps`() = runBlocking {
        val steps = (1..UpdatePlanTool.MAX_STEPS + 1).joinToString(",") { """{"text":"step $it","status":"pending"}""" }
        val result = run("""{"steps":[$steps]}""") as CodingToolResult.Failure
        assertFalse(result.retryable)
        assertTrue(result.summary.contains("at most"))
    }

    @Test
    fun `unknown status falls back to pending`() = runBlocking {
        val result = run("""{"steps":[{"text":"a","status":"bogus"}]}""")
        assertTrue(result.isSuccess)
        assertEquals(PlanStepStatus.PENDING, plans.single().single().status)
    }

    @Test
    fun `empty step text is rejected`() = runBlocking {
        val result = run("""{"steps":[{"text":"   ","status":"pending"}]}""") as CodingToolResult.Failure
        assertFalse(result.retryable)
    }

    @Test
    fun `long step text is truncated not rejected`() = runBlocking {
        val long = "x".repeat(UpdatePlanTool.MAX_STEP_CHARS + 50)
        val result = run("""{"steps":[{"text":"$long","status":"pending"}]}""")
        assertTrue(result.isSuccess)
        assertEquals(UpdatePlanTool.MAX_STEP_CHARS, plans.single().single().text.length)
    }

    @Test
    fun `plan step status wire format round trips`() {
        assertEquals(PlanStepStatus.PENDING, PlanStepStatus.fromWire("pending"))
        assertEquals(PlanStepStatus.IN_PROGRESS, PlanStepStatus.fromWire("in_progress"))
        assertEquals(PlanStepStatus.DONE, PlanStepStatus.fromWire("done"))
        assertEquals(PlanStepStatus.PENDING, PlanStepStatus.fromWire("???"))
    }
}
