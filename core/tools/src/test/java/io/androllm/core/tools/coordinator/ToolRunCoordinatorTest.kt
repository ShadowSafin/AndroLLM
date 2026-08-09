package io.androllm.core.tools.coordinator

import com.google.common.truth.Truth.assertThat
import io.androllm.core.cloud.model.CloudToolCall
import io.androllm.core.cloud.model.CloudToolCallFunction
import io.androllm.core.tools.agent.AgentContextBuilder
import io.androllm.core.tools.api.ToolResult
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
}
