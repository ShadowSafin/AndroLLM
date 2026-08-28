package io.androllm.feature.coding.agent

import io.androllm.feature.coding.environment.EnvironmentManager
import io.androllm.feature.coding.workspace.CodingWorkspace
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Tests for the coding agent system prompt (task modes, planning, review, servers). */
class CodingSystemPromptTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var root: File
    private lateinit var workspace: CodingWorkspace
    private lateinit var environment: EnvironmentManager

    @Before
    fun setUp() {
        root = tmp.newFolder("ws")
        workspace = CodingWorkspace("ws-1", "Demo", root.canonicalPath)
        environment = EnvironmentManager(envRoot = { tmp.newFolder("env") })
    }

    private fun build(
        taskMode: CodingTaskMode = CodingTaskMode.GENERAL,
        linuxBaseReady: Boolean = false,
        objective: String = ""
    ): String = CodingSystemPrompt.build(
        workspace = workspace,
        environment = environment,
        toolNames = setOf("read_file", "write_file", "run_command", "update_plan"),
        objective = objective,
        linuxBaseReady = linuxBaseReady,
        taskMode = taskMode
    )

    @Test
    fun `general mode omits the task mode section`() {
        val prompt = build()
        assertFalse(prompt.contains("TASK MODE"))
    }

    @Test
    fun `core sections are always present`() {
        val prompt = build()
        assertTrue(prompt.contains("WORKSPACE"))
        assertTrue(prompt.contains("PLANNING"))
        assertTrue(prompt.contains("CHANGE REVIEW"))
        assertTrue(prompt.contains("CODE QUALITY"))
        assertTrue(prompt.contains("WORKING METHOD"))
        assertTrue(prompt.contains("SAFETY"))
        assertTrue(prompt.contains("update_plan"))
        assertTrue(prompt.contains(workspace.name))
    }

    @Test
    fun `task mode guidance is folded in with an uppercase label`() {
        val prompt = build(taskMode = CodingTaskMode.BUILD_WEBSITE)
        assertTrue(prompt.contains("TASK MODE: BUILD WEBSITE"))
        assertTrue(prompt.contains("Responsive layout first"))
    }

    @Test
    fun `fix bug mode includes the reproduce-first method`() {
        val prompt = build(taskMode = CodingTaskMode.FIX_BUG)
        assertTrue(prompt.contains("TASK MODE: FIX A BUG"))
        assertTrue(prompt.contains("Reproduce first"))
        assertTrue(prompt.contains("MINIMAL correct fix"))
    }

    @Test
    fun `linux base ready prompt documents background servers and binding`() {
        val prompt = build(linuxBaseReady = true)
        assertTrue(prompt.contains("BACKGROUND SERVICES"))
        assertTrue(prompt.contains("0.0.0.0"))
        assertTrue(prompt.contains("http://localhost:<port>"))
        assertTrue(prompt.contains("list_background_services"))
    }

    @Test
    fun `without linux base the prompt points to the marketplace`() {
        val prompt = build(linuxBaseReady = false)
        assertTrue(prompt.contains("NOT provisioned yet"))
        assertTrue(prompt.contains("missing addon"))
    }

    @Test
    fun `objective is appended when set`() {
        val prompt = build(objective = "Build a todo app")
        assertTrue(prompt.contains("CURRENT OBJECTIVE"))
        assertTrue(prompt.contains("Build a todo app"))
    }

    @Test
    fun `task mode fromId round trips and defaults to general`() {
        assertEquals(CodingTaskMode.FIX_BUG, CodingTaskMode.fromId("FIX_BUG"))
        assertEquals(CodingTaskMode.REFACTOR, CodingTaskMode.fromId("refactor"))
        assertEquals(CodingTaskMode.GENERAL, CodingTaskMode.fromId(null))
        assertEquals(CodingTaskMode.GENERAL, CodingTaskMode.fromId("bogus"))
    }

    @Test
    fun `every task mode has a label and emoji`() {
        CodingTaskMode.entries.forEach { mode ->
            assertTrue(mode.label.isNotBlank())
            assertTrue(mode.emoji.isNotBlank())
            if (mode != CodingTaskMode.GENERAL) {
                assertTrue("$mode should carry guidance", mode.guidance.isNotBlank())
            }
        }
    }
}
