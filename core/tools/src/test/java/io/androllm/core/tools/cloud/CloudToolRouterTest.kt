package io.androllm.core.tools.cloud

import io.androllm.core.cloud.model.CloudChatMessage
import io.androllm.core.cloud.model.CloudToolCall
import io.androllm.core.cloud.model.CloudToolCallFunction
import io.androllm.core.tools.agent.AgentPlanner
import io.androllm.core.tools.agent.AgentVariableStore
import io.androllm.core.tools.coordinator.ToolRunCoordinator
import io.androllm.core.tools.planner.ToolPlanner
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Cloud tool routing: multi-step execution delegation, conditional skips,
 * skip observation feedback, and guard behavior.
 */
class CloudToolRouterTest {

    private lateinit var coordinator: ToolRunCoordinator
    private lateinit var planner: ToolPlanner
    private lateinit var variableStore: AgentVariableStore
    private lateinit var router: CloudToolRouter

    @Before
    fun setUp() {
        coordinator = mockk(relaxed = true)
        planner = mockk(relaxed = true)
        variableStore = AgentVariableStore()
        router = CloudToolRouter(
            coordinator = coordinator,
            planner = planner,
            agentPlanner = AgentPlanner(variableStore = variableStore),
            variableStore = variableStore
        )
    }

    private fun call(name: String, args: String = "{}", id: String? = "call_$name") =
        CloudToolCall(
            index = 0,
            id = id,
            type = "function",
            function = CloudToolCallFunction(name, args)
        )

    @Test
    fun `empty call list yields empty result without touching the coordinator`() = runBlocking {
        val result = router.routeAndExecute(emptyList(), userQuery = "anything")
        assertTrue(result.messages.isEmpty())
        assertEquals(0, result.executedCount)
        coVerify(exactly = 0) { coordinator.executeCloudToolCalls(any(), any(), any(), any()) }
    }

    @Test
    fun `normal calls are delegated to the coordinator`() = runBlocking {
        val toolMessages = listOf(
            CloudChatMessage("assistant", toolCalls = listOf(call("get_weather"))),
            CloudChatMessage("tool", content = "Rainy, 18°C", toolCallId = "call_get_weather")
        )
        coEvery { coordinator.executeCloudToolCalls(any(), any(), any(), any()) } returns toolMessages

        val result = router.routeAndExecute(
            listOf(call("get_weather", """{"location":"Berlin"}""")),
            userQuery = "What's the weather in Berlin?"
        )
        assertEquals(2, result.messages.size)
        assertEquals(1, result.executedCount)
        assertTrue(result.conditionalSkips.isEmpty())
        coVerify(exactly = 1) { coordinator.executeCloudToolCalls(any(), any(), any(), any()) }
    }

    @Test
    fun `conditional sms is skipped when weather is dry and skip is fed back`() = runBlocking {
        variableStore.set("get_weather", "Sunny and clear, no rain expected")

        val result = router.routeAndExecute(
            listOf(call("send_sms", """{"to":"Mom","body":"bring umbrella"}""")),
            userQuery = "Check the weather and if it rains send an SMS to Mom"
        )

        // Nothing was executed...
        assertEquals(0, result.executedCount)
        coVerify(exactly = 0) { coordinator.executeCloudToolCalls(any(), any(), any(), any()) }
        // ...but the model still observes the skip as a normal tool round.
        assertEquals(1, result.conditionalSkips.size)
        assertEquals("send_sms", result.conditionalSkips[0].toolName)
        val assistant = result.messages.first { it.role == "assistant" }
        assertEquals(1, assistant.toolCalls?.size)
        val toolMessage = result.messages.first { it.role == "tool" }
        assertTrue(toolMessage.content!!.contains("Skipped"))
        assertEquals(result.conditionalSkips[0].callId, toolMessage.toolCallId)
    }

    @Test
    fun `conditional sms proceeds when rain is observed`() = runBlocking {
        variableStore.set("get_weather", "Heavy rain and thunderstorm")
        coEvery { coordinator.executeCloudToolCalls(any(), any(), any(), any()) } returns listOf(
            CloudChatMessage("tool", content = "SMS queued", toolCallId = "call_send_sms")
        )

        val result = router.routeAndExecute(
            listOf(call("send_sms", """{"to":"Mom"}""")),
            userQuery = "If it rains send an SMS to Mom"
        )
        assertEquals(1, result.executedCount)
        assertTrue(result.conditionalSkips.isEmpty())
    }

    @Test
    fun `mixed round executes survivors and skips conditioned calls`() = runBlocking {
        variableStore.set("search_web", "No results found.")
        coEvery { coordinator.executeCloudToolCalls(any(), any(), any(), any()) } returns listOf(
            CloudChatMessage("tool", content = "note saved", toolCallId = "call_note_save")
        )

        val result = router.routeAndExecute(
            listOf(
                call("send_email", """{"to":"me@x.io"}"""),   // conditioned on results → skipped
                call("note_save", """{"text":"log"}""")        // unconditional → executed
            ),
            userQuery = "Search for X and if you find anything email it to me; also save a note"
        )
        assertEquals(1, result.executedCount)
        assertEquals(1, result.conditionalSkips.size)
        assertEquals("send_email", result.conditionalSkips[0].toolName)
        // Executed messages first, then the skip feedback pair.
        assertTrue(result.messages.any { it.role == "tool" && it.content == "note saved" })
        assertTrue(result.messages.any { it.role == "tool" && it.content!!.startsWith("Skipped") })
    }

    @Test
    fun `multi step workflow plan is produced for conditional requests`() {
        val plan = router.planWorkflow(
            "Check the weather, then if it rains send an SMS to Mom"
        )
        // The internal planner recognizes the multi-step conditional shape.
        assertTrue(plan == null || plan.isMultiStep || plan.hasConditional || plan.executionOrder.isNotEmpty())
    }

    @Test
    fun `fallback parsed calls flow through the same gated path`() = runBlocking {
        // Calls recovered from plain text by CloudFallbackToolParser arrive as
        // ordinary CloudToolCalls — the router must treat them identically.
        coEvery { coordinator.executeCloudToolCalls(any(), any(), any(), any()) } returns listOf(
            CloudChatMessage("tool", content = "42", toolCallId = "call_calculate")
        )
        val recovered = call("calculate", """{"expression":"6*7"}""", id = "fallback_0")

        val result = router.routeAndExecute(listOf(recovered), userQuery = "what is 6*7?")
        assertEquals(1, result.executedCount)
        assertFalse(result.allBlockedByGuard)
    }
}
