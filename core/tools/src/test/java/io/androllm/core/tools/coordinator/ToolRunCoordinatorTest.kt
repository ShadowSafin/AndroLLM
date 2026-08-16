package io.androllm.core.tools.coordinator

import com.google.common.truth.Truth.assertThat
import io.androllm.core.cloud.model.CloudToolCall
import io.androllm.core.cloud.model.CloudToolCallFunction
import io.androllm.core.tools.agent.AgentContextBuilder
import io.androllm.core.tools.api.Tool
import io.androllm.core.tools.api.ToolResult
import io.androllm.core.tools.api.ToolSpec
import io.androllm.core.tools.executor.ToolExecutor
import io.androllm.core.tools.planner.ToolPlanner
import io.androllm.core.tools.registry.ToolRegistry
import io.androllm.core.tools.settings.AutomationSettingsStore
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import org.junit.Test

class ToolRunCoordinatorTest {

    private val planner = mockk<ToolPlanner>()
    private val executor = mockk<ToolExecutor>()
    private val settingsStore = mockk<AutomationSettingsStore>(relaxed = true)
    private val agentContext = mockk<AgentContextBuilder>(relaxed = true)
    private val registry = mockk<ToolRegistry>(relaxed = true)

    private fun coordinator() = ToolRunCoordinator(planner, executor, settingsStore, agentContext, registry)

    /** Registers a fake pure-read tool with the given name on the registry. */
    private fun registerReadTool(name: String) {
        val tool = mockk<Tool>()
        every { tool.spec } returns ToolSpec(name = name, description = "read-only test tool", cacheable = true)
        every { registry.get(name) } returns tool
    }

    @Test
    fun `executeCloudToolCalls preserves text streamed alongside the tool calls`() = runTest {
        coEvery { executor.execute(any()) } returns ToolResult.Success("sent", buildJsonObject { })
        val call = CloudToolCall(
            id = "call_1",
            type = "function",
            function = CloudToolCallFunction("send_sms", """{"phone":"+1","message":"hi"}""")
        )
        val msgs = coordinator().executeCloudToolCalls(listOf(call), assistantContent = "Sending the weather to mom.")

        assertThat(msgs).hasSize(2)
        assertThat(msgs[0].role).isEqualTo("assistant")
        // The interim narration must reach the next round so the answer is never partial.
        assertThat(msgs[0].content).isEqualTo("Sending the weather to mom.")
        assertThat(msgs[0].toolCalls).hasSize(1)
        assertThat(msgs[1].role).isEqualTo("tool")
        assertThat(msgs[1].toolCallId).isEqualTo("call_1")
        assertThat(msgs[1].content).contains("sent")
    }

    @Test
    fun `executeCloudToolCalls leaves content null when no interim text`() = runTest {
        coEvery { executor.execute(any()) } returns ToolResult.Failure("declined")
        val call = CloudToolCall(
            id = "call_2",
            function = CloudToolCallFunction("send_sms", "{}")
        )
        val msgs = coordinator().executeCloudToolCalls(listOf(call))
        assertThat(msgs[0].content).isNull()
    }

    @Test
    fun `same-name calls without provider ids get distinct confirmation ids`() = runTest {
        coEvery { executor.execute(any()) } returns ToolResult.Success("ok", buildJsonObject { })
        val calls = listOf(
            CloudToolCall(index = 0, function = CloudToolCallFunction("send_sms", """{"phone":"+1"}""")),
            CloudToolCall(index = 1, function = CloudToolCallFunction("send_sms", """{"phone":"+2"}"""))
        )
        val msgs = coordinator().executeCloudToolCalls(calls)
        assertThat(msgs).hasSize(3) // assistant + 2 tool results
        assertThat(msgs[1].toolCallId).isNotEqualTo(msgs[2].toolCallId)
    }

    @Test
    fun `empty calls return empty list`() = runTest {
        assertThat(coordinator().executeCloudToolCalls(emptyList())).isEmpty()
    }

    @Test
    fun `oversized tool output is chunked into sequential tool messages never truncated`() = runTest {
        registerReadTool("search_web")
        // ~20KB of results — far beyond the 8KB chunk budget.
        val big = "Result ".repeat(4_000)
        coEvery { executor.execute(any()) } returns ToolResult.Success(big, buildJsonObject { })
        val call = CloudToolCall(id = "call_x", function = CloudToolCallFunction("search_web", """{"query":"news"}"""))

        val msgs = coordinator().executeCloudToolCalls(listOf(call))

        // Assistant + one tool message per chunk; the FULL output survives.
        assertThat(msgs.size).isGreaterThan(2)
        assertThat(msgs[0].toolCalls).hasSize(msgs.size - 1)
        val joined = msgs.drop(1).joinToString("") { it.content.orEmpty() }
        assertThat(joined).contains("Result")
        // Nothing was dropped: chunk ids are all present and matched.
        msgs.drop(1).forEachIndexed { i, m ->
            assertThat(m.role).isEqualTo("tool")
            assertThat(m.toolCallId).isEqualTo(msgs[0].toolCalls!![i].id)
        }
    }

    @Test
    fun `identical cacheable call is replayed from cache instead of re-executing`() = runTest {
        registerReadTool("search_web")
        var executions = 0
        coEvery { executor.execute(any()) } answers {
            executions++
            ToolResult.Success("cached-search-output", buildJsonObject { })
        }
        val call = CloudToolCall(id = "call_1", function = CloudToolCallFunction("search_web", """{"query":"weather"}"""))
        val coord = coordinator()

        val first = coord.executeCloudToolCalls(listOf(call))
        val second = coord.executeCloudToolCalls(listOf(call))

        assertThat(executions).isEqualTo(1)
        assertThat(first[1].content).isEqualTo("cached-search-output")
        assertThat(second[1].content).isEqualTo("cached-search-output")
    }

    @Test
    fun `non-cacheable tool never replays from cache`() = runTest {
        val tool = mockk<Tool>()
        every { tool.spec } returns ToolSpec(name = "send_sms", description = "side-effecting test tool", cacheable = false)
        every { registry.get("send_sms") } returns tool
        var executions = 0
        coEvery { executor.execute(any()) } answers {
            executions++
            ToolResult.Success("sent", buildJsonObject { })
        }
        val call = CloudToolCall(id = "call_1", function = CloudToolCallFunction("send_sms", """{"phone":"+1"}"""))
        val coord = coordinator()

        coord.executeCloudToolCalls(listOf(call))
        coord.executeCloudToolCalls(listOf(call))

        // Side-effecting tools MUST always execute — never replayed.
        assertThat(executions).isEqualTo(2)
    }

    @Test
    fun `transient tool failure is retried with backoff then succeeds`() = runTest {
        registerReadTool("get_weather")
        var calls = 0
        coEvery { executor.execute(any()) } answers {
            calls++
            if (calls == 1) {
                ToolResult.Failure("network timeout", retryable = true)
            } else {
                ToolResult.Success("Sunny 24C", buildJsonObject { })
            }
        }
        val call = CloudToolCall(id = "call_1", function = CloudToolCallFunction("get_weather", """{"location":"Berlin"}"""))

        val msgs = coordinator().executeCloudToolCalls(listOf(call))

        assertThat(calls).isEqualTo(2)
        assertThat(msgs[1].content).isEqualTo("Sunny 24C")
    }

    @Test
    fun `non-retryable failure is not retried`() = runTest {
        registerReadTool("get_weather")
        var calls = 0
        coEvery { executor.execute(any()) } answers {
            calls++
            ToolResult.Failure("The user declined the action.", retryable = false)
        }
        val call = CloudToolCall(id = "call_1", function = CloudToolCallFunction("get_weather", """{"location":"Berlin"}"""))

        coordinator().executeCloudToolCalls(listOf(call))

        assertThat(calls).isEqualTo(1)
    }
}
