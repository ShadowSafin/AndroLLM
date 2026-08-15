package io.androllm.core.tools.planner

import com.google.common.truth.Truth.assertThat
import io.androllm.core.tools.agent.AgentContextBuilder
import io.androllm.core.tools.api.Tool
import io.androllm.core.tools.api.ToolResult
import io.androllm.core.tools.api.ToolSpec
import io.androllm.core.tools.prompt.ToolPromptBuilder
import io.androllm.core.tools.registry.ToolRegistry
import io.androllm.core.tools.settings.AutomationSettings
import io.androllm.core.tools.settings.AutomationSettingsStore
import io.androllm.engine.api.EngineRepository
import io.androllm.engine.api.EngineState
import io.androllm.engine.models.BackendType
import io.androllm.engine.models.ChatPromptMessage
import io.androllm.engine.models.EngineModelInfo
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test

class ToolPlannerTest {

    private class FakeTool(name: String, desc: String = "a test tool") : Tool {
        override val spec = ToolSpec(
            name = name,
            description = desc,
            parameters = buildJsonObject {
                put("type", "object")
            }
        )
        override suspend fun execute(arguments: JsonObject) = ToolResult.Success("ok")
    }

    private val settingsStore = mockk<AutomationSettingsStore>(relaxed = true)
    private val engineRepository = mockk<EngineRepository>(relaxed = true)
    private val agentContext = mockk<AgentContextBuilder>(relaxed = true)

    private fun planner(vararg tools: Tool): ToolPlanner {
        val registry = ToolRegistry().apply { registerAll(tools.toList()) }
        return ToolPlanner(registry, settingsStore, engineRepository, agentContext)
    }

    @Test
    fun `allowedTools excludes disabled tools and is empty when master off`() = runTest {
        coEvery { settingsStore.current() } returns AutomationSettings(
            toolCallingEnabled = false
        )
        val p = planner(FakeTool("get_weather"), FakeTool("send_sms"))
        assertThat(p.allowedTools()).isEmpty()

        coEvery { settingsStore.current() } returns AutomationSettings(
            toolCallingEnabled = true,
            disabledTools = setOf("send_sms")
        )
        assertThat(p.allowedTools().map { it.name }).containsExactly("get_weather")
    }

    @Test
    fun `buildCloudTools maps specs to OpenAI functions`() = runTest {
        coEvery { settingsStore.current() } returns AutomationSettings(toolCallingEnabled = true)
        val p = planner(FakeTool("get_weather"))
        val tools = p.buildCloudTools()
        assertThat(tools).hasSize(1)
        assertThat(tools[0].function.name).isEqualTo("get_weather")
        assertThat(tools[0].type).isEqualTo("function")
        assertThat(tools[0].function.parameters.isEmpty()).isFalse()
    }

    @Test
    fun `planLocal parses model output into tool calls`() = runTest {
        coEvery { settingsStore.current() } returns AutomationSettings(toolCallingEnabled = true)
        every { engineRepository.engineState } returns MutableStateFlow(
            EngineState.Ready(
                EngineModelInfo(
                    id = "test",
                    filePath = "/tmp/model.gguf",
                    contextLength = 2048,
                    vocabSize = 32000,
                    backend = BackendType.CPU
                )
            )
        )
        coEvery { engineRepository.buildChatPrompt(any(), any()) } returns
            io.androllm.core.common.Result.Success("<assistant>")
        coEvery { engineRepository.generateQuiet(any(), any(), any()) } returns
            io.androllm.core.common.Result.Success(
                """{"calls":[{"name":"get_weather","arguments":{"location":"Delhi"}}]}"""
            )

        val p = planner(FakeTool("get_weather"))
        val calls = p.planLocal(
            listOf(ChatPromptMessage(role = "user", content = "What's the weather in Delhi?"))
        )
        assertThat(calls).hasSize(1)
        assertThat(calls[0].name).isEqualTo("get_weather")
        assertThat(calls[0].arguments["location"]?.toString()).isEqualTo("\"Delhi\"")
    }

    @Test
    fun `planLocal drops calls for unknown tools`() = runTest {
        coEvery { settingsStore.current() } returns AutomationSettings(toolCallingEnabled = true)
        every { engineRepository.engineState } returns MutableStateFlow(
            EngineState.Ready(
                EngineModelInfo("test", "/tmp/m.gguf", 2048, 32000, BackendType.CPU)
            )
        )
        coEvery { engineRepository.buildChatPrompt(any(), any()) } returns
            io.androllm.core.common.Result.Success("<assistant>")
        coEvery { engineRepository.generateQuiet(any(), any(), any()) } returns
            io.androllm.core.common.Result.Success(
                """{"calls":[{"name":"nonexistent_tool","arguments":{}}]}"""
            )
        val p = planner(FakeTool("get_weather"))
        assertThat(p.planLocal(emptyList())).isEmpty()
    }

    @Test
    fun `planLocal skips when no model loaded`() = runTest {
        coEvery { settingsStore.current() } returns AutomationSettings(toolCallingEnabled = true)
        every { engineRepository.engineState } returns MutableStateFlow(EngineState.Unloaded)
        val p = planner(FakeTool("get_weather"))
        assertThat(p.planLocal(emptyList())).isEmpty()
    }

    @Test
    fun `planLocal is bounded - a stalled generateQuiet fails fast instead of hanging the turn`() = runTest {
        coEvery { settingsStore.current() } returns AutomationSettings(toolCallingEnabled = true)
        every { engineRepository.engineState } returns MutableStateFlow(
            EngineState.Ready(
                EngineModelInfo("test", "/tmp/m.gguf", 2048, 32000, BackendType.CPU)
            )
        )
        coEvery { engineRepository.buildChatPrompt(any(), any()) } returns
            io.androllm.core.common.Result.Success("<assistant>")
        // The native pass never returns — only the per-pass budget can end it.
        // The planner forwards its budget as timeoutMs; the stub must accept
        // the three-argument form the production call now uses.
        coEvery { engineRepository.generateQuiet(any(), any(), any()) } coAnswers {
            delay(ToolPlanner.PLANNING_TIMEOUT_MS + 60_000L)
            io.androllm.core.common.Result.Success("""{"calls":[]}""")
        }
        val p = planner(FakeTool("get_weather"))
        val calls = p.planLocal(
            listOf(ChatPromptMessage(role = "user", content = "What's the weather?"))
        )
        // Times out → empty plan → the turn answers without tools instead of
        // stalling invisible (regression: "chat appears to do nothing").
        assertThat(calls).isEmpty()
    }

    @Test
    fun `system prompt lists the available tools`() {
        val prompt = ToolPrompts.system(listOf(FakeTool("get_weather").spec))
        assertThat(prompt).contains("get_weather")
        assertThat(prompt).contains("calls")
    }

    @Test
    fun `regression - every user command maps to an advertised tool`() {
        // The regression list from the bug report: each command must be served
        // by a tool that BOTH the planner prompt and the chat system-prompt
        // advertisement actually carry. If a name drifts here, the model is
        // back to answering "I don't have access…".
        val regressionTools = listOf(
            "get_weather",          // "Search today's weather"
            "search_web",           // "Search GitHub" (web) — github() is the dedicated tool
            "github",               // "Search GitHub" (dedicated)
            "launch_app",           // "Open Discord" / "Navigate home" / "Open Settings"
            "make_call",            // "Call Mom"
            "send_sms",             // "Send SMS"
            "copy_to_clipboard",    // "Copy this"
            "take_screenshot",      // "Take a screenshot"
            "set_bluetooth",        // "Enable Bluetooth"
            "set_flashlight"        // "Turn on Flashlight"
        )
        val specs = regressionTools.map { FakeTool(it).spec }

        val plannerPrompt = ToolPrompts.system(specs)
        val advertisement = ToolPromptBuilder(mockk(relaxed = true)).render(specs)

        for (name in regressionTools) {
            assertThat(plannerPrompt).contains("- $name")
            assertThat(advertisement).contains("- $name")
        }
    }
}
