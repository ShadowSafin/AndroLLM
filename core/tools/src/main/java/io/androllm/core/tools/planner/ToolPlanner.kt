package io.androllm.core.tools.planner

import io.androllm.core.tools.agent.AgentContextBuilder
import io.androllm.core.tools.api.ToolCall
import io.androllm.core.tools.api.ToolCallParser
import io.androllm.core.tools.api.ToolSpec
import io.androllm.core.common.getOrNull
import io.androllm.core.tools.registry.ToolRegistry
import io.androllm.core.tools.settings.AutomationSettingsStore
import io.androllm.engine.api.EngineRepository
import io.androllm.engine.models.ChatPromptMessage
import io.androllm.engine.models.GenerationConfig
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import timber.log.Timber

/**
 * Decides WHICH tool(s) to call for a user request — the LLM never executes
 * Android code, it only produces tool calls.
 *
 * Two backends behind one interface:
 * - **Cloud**: the OpenAI-compatible `tools` array is passed to the provider
 *   (native function calling, [buildCloudTools]).
 * - **Local GGUF**: a small JSON-Schema-grammar-constrained generation asks
 *   the loaded model for `{"calls":[...]}` (llama.cpp grammar support, same
 *   mechanism the memory extractor uses). [planLocal] runs it.
 */
@Singleton
class ToolPlanner @Inject constructor(
    private val registry: ToolRegistry,
    private val settingsStore: AutomationSettingsStore,
    private val engineRepository: EngineRepository,
    private val agentContext: AgentContextBuilder
) {

    /**
     * Tools visible to the model: everything registered and not user-blocked.
     */
    suspend fun allowedTools(): List<ToolSpec> {
        val settings = settingsStore.current()
        if (!settings.toolCallingEnabled) return emptyList()
        return registry.all()
            .map { it.spec }
            .filter { settings.isToolEnabled(it.name) }
            .sortedBy { it.name }
    }

    /**
     * OpenAI-compatible `tools` array for the cloud path.
     */
    suspend fun buildCloudTools(): List<io.androllm.core.cloud.model.CloudTool> =
        allowedTools().map { spec ->
            io.androllm.core.cloud.model.CloudTool(
                type = "function",
                function = io.androllm.core.cloud.model.CloudToolFunction(
                    name = spec.name,
                    description = spec.description,
                    parameters = spec.parameters
                )
            )
        }

    /**
     * Runs the local planner against the loaded GGUF model and returns the
     * tool calls it wants to make (empty when none / model unavailable).
     */
    suspend fun planLocal(messages: List<ChatPromptMessage>): List<ToolCall> {
        val specs = allowedTools()
        if (specs.isEmpty()) return emptyList()
        // Defensive: planning needs a loaded GGUF model.
        if (engineRepository.engineState.value !is io.androllm.engine.api.EngineState.Ready) {
            Timber.w("ToolPlanner: no model loaded — skipping plan")
            return emptyList()
        }

        val planMessages = listOf(
            ChatPromptMessage(role = "system", content = ToolPrompts.system(specs, agentContext.buildBlock())),
            ChatPromptMessage(role = "user", content = ToolPrompts.buildUserContent(messages))
        )
        val prompt = engineRepository.buildChatPrompt(planMessages, addAssistant = true).getOrNull()
        if (prompt.isNullOrBlank()) {
            Timber.w("ToolPlanner: chat template unavailable")
            return emptyList()
        }

        val config = GenerationConfig(
            maxTokens = 512,
            temperature = 0.1f,
            topP = 1.0f,
            minP = 0.0f,
            repetitionPenalty = 1.05f,
            jsonSchema = PLAN_SCHEMA,
            reuseKvCache = false
        )
        val output = engineRepository.generateQuiet(prompt, config).getOrNull()
        if (output.isNullOrBlank()) return emptyList()

        val calls = ToolCallParser.parse(output)
        // The planner must never ask for tools it cannot see.
        val known = registry.all().map { it.spec.name }.toSet()
        val filtered = calls.filter { it.name in known }
        if (filtered.size != calls.size) {
            Timber.w("ToolPlanner: dropped ${calls.size - filtered.size} unknown tool call(s)")
        }
        return filtered
    }

    companion object {
        /**
         * JSON schema passed to the native grammar generator. The shape is
         * deliberately simple (array of {name, arguments}) so small GGUF
         * models can satisfy it reliably.
         */
        const val PLAN_SCHEMA: String = """
        {
          "type": "object",
          "properties": {
            "calls": {
              "type": "array",
              "items": {
                "type": "object",
                "properties": {
                  "name": { "type": "string" },
                  "arguments": { "type": "object" }
                },
                "required": ["name", "arguments"]
              }
            }
          },
          "required": ["calls"]
        }
        """
    }
}

/**
 * Prompt building for the LOCAL planner. The cloud path never uses these
 * prompts — providers get native `tools` instead.
 */
object ToolPrompts {

    /**
     * Builds the planner system prompt. [contextBlock] carries the live agent
     * context (device facts + workflow variables) that the model must use
     * instead of asking the user — re-injected on every planning round so
     * multi-step tasks can branch on previous tool outputs.
     */
    fun system(specs: List<ToolSpec>, contextBlock: String = ""): String {
        val sb = StringBuilder()
        sb.append(
            """
            You are the tool planner of an on-device AI assistant. The user's request
            may need one or more device or network actions. Decide which tools to call
            and with which arguments, then output ONLY a JSON object:
            {"calls": [{"name": "tool_name", "arguments": { ... }}]}
            Rules:
            - Output {"calls": []} when no tool is needed (small talk, questions that
              the assistant can answer from its own knowledge, requests to write text,
              summarize, translate, explain, or review code).
            - Use exactly the tool names and argument names listed below.
            - Supply only arguments that appear in the conversation, the context
              below, or the results of tools you already ran; never invent values.
              If a required argument is missing, omit the call.
            - Prefer dedicated tools (send_sms, set_alarm, get_weather, maps,
              launcher) over ui_* UI-automation tools whenever one exists.
              Use ui_run / ui_click / ui_type only for apps that have NO
              native tool (e.g. WhatsApp, Uber, YouTube).
            - For "find the nearest X" or navigation requests, prefer the maps tools.
            - You may emit MULTIPLE calls when a task needs several steps. Emit them
              in execution order — the results of earlier calls are available to
              later calls in the NEXT round via variable_get and the context block.
            - Conditional workflows: after running a tool, branch on its result in
              the next round (IF/ELSE). For lists, iterate item by item (FOR EACH)
              using variable_set to remember the current index; stop when done
              (WHILE) — never emit more than a few iterations per round.
            - Respond with the JSON object only — no prose, no markdown.
            """.trimIndent()
        )
        if (contextBlock.isNotBlank()) {
            sb.append("\n\n").append(contextBlock).append('\n')
        }
        sb.append("\n\nAVAILABLE TOOLS:\n")
        for (spec in specs) {
            sb.append("- ").append(spec.name).append(": ").append(spec.description).append('\n')
            val params = describeParameters(spec.parameters)
            if (params.isNotBlank()) sb.append("    arguments: ").append(params).append('\n')
        }
        return sb.toString()
    }

    fun buildUserContent(messages: List<ChatPromptMessage>): String {
        val sb = StringBuilder()
        sb.append("CONVERSATION:\n")
        for (msg in messages.takeLast(8)) {
            sb.append(msg.role).append(": ").append(msg.content.take(800)).append('\n')
        }
        sb.append("\nOutput the JSON plan for the LATEST user request only.")
        return sb.toString()
    }

    /** Compact "key: type" listing of the JSON-schema properties. */
    private fun describeParameters(schema: JsonObject): String {
        val props = (schema["properties"] as? JsonObject) ?: return ""
        return props.mapNotNull { (name, el) ->
            val type = (el as? JsonObject)?.get("type")?.let {
                (it as? JsonPrimitive)?.content
            } ?: "any"
            "$name ($type)"
        }.joinToString(", ")
    }
}
