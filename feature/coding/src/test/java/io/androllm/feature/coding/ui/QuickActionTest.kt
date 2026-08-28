package io.androllm.feature.coding.ui

import org.junit.Assert.assertTrue
import org.junit.Test

/** Sanity checks for the quick-action templated prompts. */
class QuickActionTest {

    @Test
    fun `every quick action has a label emoji and substantial prompt`() {
        QuickAction.entries.forEach { action ->
            assertTrue("$action label", action.label.isNotBlank())
            assertTrue("$action emoji", action.emoji.isNotBlank())
            assertTrue("$action prompt too short", action.prompt.length >= 40)
        }
    }

    @Test
    fun `quick actions leave command detection to the agent`() {
        // Prompts must ask the agent to detect the right command for the stack
        // instead of hardcoding one (a Python project has no `npm run build`).
        QuickAction.entries.forEach { action ->
            assertTrue(
                "$action should tell the agent to detect the command",
                action.prompt.contains("detect the right command") ||
                    action.prompt.contains("detect the tooling") ||
                    action.prompt.contains("workspace_summary")
            )
        }
    }

    @Test
    fun `core actions exist`() {
        val names = QuickAction.entries.map { it.name }.toSet()
        assertTrue(names.containsAll(setOf("BUILD", "TEST", "LINT", "RUN", "INSPECT")))
    }
}
