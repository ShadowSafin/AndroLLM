package io.androllm.core.tools.planner

import io.androllm.core.tools.agent.AgentContextBuilder
import io.androllm.core.tools.api.ToolCall
import io.androllm.core.tools.api.ToolCallParser
import io.androllm.core.tools.api.ToolSpec
import io.androllm.core.common.getOrNull
import io.androllm.core.tools.registry.ToolRegistry
import io.androllm.core.tools.router.ToolIntent
import io.androllm.core.tools.router.ToolRouter
import io.androllm.core.tools.settings.AutomationSettingsStore
import io.androllm.engine.api.EngineRepository
import io.androllm.engine.models.ChatPromptMessage
import io.androllm.engine.models.GenerationConfig
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
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
    private val agentContext: AgentContextBuilder,
    private val router: ToolRouter
) {

    /**
     * Running assessment of whether the LOADED model can emit structured tool
     * calls directly (native JSON) or needs the parser compatibility layer.
     * Updated for free on every [planLocal] round — no extra inference.
     * [probeCapability] forces a live probe for the Tool Debug screen.
     */
    private val _capability = MutableStateFlow(LocalToolCapability())
    val capability: StateFlow<LocalToolCapability> = _capability.asStateFlow()

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
     * OpenAI-compatible `tools` array for the cloud path. When [query] is
     * non-blank the list is ROUTED to the request (math → calculator only,
     * device → device tools, attachments → none), so the provider's model can
     * never pick the wrong tool "just in case". Blank query = the full
     * enabled set (voice/backward-compatible callers).
     */
    suspend fun buildCloudTools(query: String = "", hasAttachments: Boolean = false): List<io.androllm.core.cloud.model.CloudTool> =
        routedTools(query, hasAttachments).map { spec ->
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
     * Tools visible for a request: the full enabled set, filtered by the
     * [ToolRouter]. Blank [query] = everything enabled (diagnostics screens).
     */
    suspend fun routedTools(query: String = "", hasAttachments: Boolean = false): List<ToolSpec> {
        val enabled = allowedTools()
        if (enabled.isEmpty()) return emptyList()
        if (query.isBlank()) return enabled
        return router.route(query, hasAttachments, enabled).specs
    }

    /**
     * Runs the local planner against the loaded GGUF model and returns the
     * tool calls it wants to make (empty when none / model unavailable).
     */
    suspend fun planLocal(
        messages: List<ChatPromptMessage>,
        hasAttachments: Boolean = false
    ): List<ToolCall> {
        // Route by the LATEST user message: math → calculator only, device →
        // device tools, attachments → no tools (content already injected),
        // small talk / writing → none. A routed-empty plan skips the LLM
        // round entirely — no inference burned proving no tools are needed.
        val latestUser = messages.lastOrNull { it.role == "user" }?.content.orEmpty()
        val routed = router.route(latestUser, hasAttachments = hasAttachments, enabledTools = allowedTools())
        if (routed.specs.isEmpty()) {
            if (routed.intent != ToolIntent.GENERAL) {
                Timber.i("ToolPlanner: route=${routed.intent.name} — no tools exposed, skipping plan")
            }
            return emptyList()
        }
        val specs = routed.specs
        // Defensive: planning needs a loaded GGUF model.
        if (engineRepository.engineState.value !is io.androllm.engine.api.EngineState.Ready) {
            Timber.w("ToolPlanner: no model loaded — skipping plan")
            return emptyList()
        }

        // Budget the planner system prompt to the container's REAL context
        // (detected at load — some Qwen3 repacks are 2048, not 4096/8192): a
        // full tool list alone can exceed it and the planning pass fails with
        // "token ids are too long" before decoding a single token. Leave room
        // for the user content + JSON output (512 max tokens).
        val realContext = (engineRepository.engineState.value as? io.androllm.engine.api.EngineState.Ready)
            ?.model?.contextLength
            ?: io.androllm.core.common.AppConstants.Model.DEFAULT_CONTEXT_LENGTH
        val planSystemChars = ((realContext - 512 - 256).coerceAtLeast(0)) * 4
        val planMessages = listOf(
            ChatPromptMessage(
                role = "system",
                content = ToolPrompts.system(specs, agentContext.buildBlock(), maxChars = planSystemChars)
            ),
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
        // BOUNDED with a REAL deadline: a hung native decode must never wedge
        // the shared engine or make the chat "appear to do nothing". A bare
        // coroutine timeout cannot interrupt the blocking JNI call — the
        // exception is only delivered after the native loop returns on its
        // own, so a stalled pass used to run the full 300s ceiling while the
        // UI spun "Preparing local model…". DefaultEngineRepository's
        // watchdog now actively aborts the engine at [timeoutMs] (nativeCancel
        // releases the decode loop promptly), so the pass fails fast and the
        // turn answers without tools instead of stalling invisible for
        // minutes. The outer withTimeoutOrNull remains as a pure coroutine
        // guard for the engine-abort window. The budget scales with the
        // rendered prompt length: the planner's system prompt alone is ~10K
        // chars (~2750 tokens), and a slow CPU prefill of that at ~20-60
        // tok/s legitimately takes 45-140s — a fixed 30s budget would kill
        // every planning pass before the first token on CPU fallback.
        val timeoutMs = planningTimeoutMs(prompt.length)
        val output = withTimeoutOrNull(timeoutMs) {
            engineRepository.generateQuiet(
                prompt,
                config,
                timeoutMs = timeoutMs
            )
        }?.getOrNull()
        if (output.isNullOrBlank()) {
            Timber.w("ToolPlanner: planning pass produced no output within ${timeoutMs}ms")
            return emptyList()
        }

        val calls = ToolCallParser.parse(output)
        // Live capability tracking: a model that answers with a clean
        // `{"calls": [...]}` JSON object natively supports structured tool
        // calling; one whose output had to be salvaged by the parser runs in
        // compatibility mode. Recorded per round, zero extra inference.
        val cleanJson = output.contains("\"calls\"")
        val previous = _capability.value
        _capability.value = previous.copy(
            modelName = (engineRepository.engineState.value as? io.androllm.engine.api.EngineState.Ready)
                ?.model?.generalName ?: previous.modelName,
            planningRounds = previous.planningRounds + 1,
            cleanParses = previous.cleanParses + if (cleanJson) 1 else 0,
            fallbackParses = previous.fallbackParses + if (cleanJson) 0 else 1,
            lastOutputSample = output.trim().take(160)
        )
        Timber.i(
            "ToolPlanner: round=${_capability.value.planningRounds} " +
                "clean=${_capability.value.cleanParses} fallback=${_capability.value.fallbackParses} " +
                "model=${_capability.value.modelName}"
        )
        // The planner must never ask for tools it cannot see.
        val known = registry.all().map { it.spec.name }.toSet()
        val filtered = calls.filter { it.name in known }
        if (filtered.size != calls.size) {
            Timber.w("ToolPlanner: dropped ${calls.size - filtered.size} unknown tool call(s)")
        }
        return filtered
    }

    /**
     * Forces a live capability probe against the loaded model: one tiny
     * generation that must answer with a JSON tool-call object. Returns the
     * probe result without changing the running [capability] stats.
     */
    suspend fun probeCapability(): LocalToolCapability {
        if (engineRepository.engineState.value !is io.androllm.engine.api.EngineState.Ready) {
            return LocalToolCapability(probeStatus = "no model loaded")
        }
        val probeMessages = listOf(
            ChatPromptMessage(
                role = "system",
                content = "You are a strict JSON tool planner. Respond with ONLY this exact JSON object (no prose):"
            ),
            ChatPromptMessage(
                role = "user",
                content = "What is the weather? -> {" +
                    "\"calls\": [{\"name\": \"get_weather\", \"arguments\": {\"location\": \"Current\"}}]}"
            )
        )
        val prompt = engineRepository.buildChatPrompt(probeMessages, addAssistant = true).getOrNull()
        if (prompt.isNullOrBlank()) return LocalToolCapability(probeStatus = "chat template unavailable")
        val output = withTimeoutOrNull(20_000L) {
            engineRepository.generateQuiet(
                prompt,
                GenerationConfig(
                    maxTokens = 48,
                    temperature = 0.1f,
                    topP = 1.0f,
                    minP = 0.0f,
                    repetitionPenalty = 1.05f,
                    reuseKvCache = false
                ),
                timeoutMs = 20_000L
            )
        }?.getOrNull()
        if (output.isNullOrBlank()) {
            return LocalToolCapability(probeStatus = "probe produced no output")
        }
        val calls = ToolCallParser.parse(output)
        val modelName = (engineRepository.engineState.value as? io.androllm.engine.api.EngineState.Ready)
            ?.model?.generalName ?: ""
        val clean = output.contains("\"calls\"") && calls.isNotEmpty()
        return LocalToolCapability(
            modelName = modelName,
            planningRounds = 1,
            cleanParses = if (clean) 1 else 0,
            fallbackParses = if (clean) 0 else 1,
            lastOutputSample = output.trim().take(160),
            probeStatus = if (calls.isNotEmpty()) "native JSON" else "parser compatibility"
        )
    }

    companion object {
        /**
         * Per-pass budget for the planner's grammar-constrained inference. A
         * healthy pass finishes in seconds; a stalled one must fail fast so
         * the chat turn can answer without tools.
         */
        const val PLANNING_TIMEOUT_MS: Long = 30_000L

        /**
         * Planning budget scaled to the rendered prompt length (floor
         * [PLANNING_TIMEOUT_MS], cap 240s). The planner's system prompt is
         * ~10K chars (~2750 tokens): a CPU-fallback prefill at 20-60 tok/s
         * needs 45-140s, so a fixed budget would cancel every planning pass
         * before its first token. Estimated tokens = chars / 4 (same
         * heuristic as the engine's first-token watchdog), +50ms each.
         */
        fun planningTimeoutMs(promptLength: Int): Long {
            val estimatedTokens = promptLength.coerceAtLeast(0) / 4
            return (PLANNING_TIMEOUT_MS + estimatedTokens * 50L)
                .coerceIn(PLANNING_TIMEOUT_MS, 240_000L)
        }

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
 * Assessment of the loaded model's ability to emit structured tool calls.
 *
 * - Native JSON: the model answers the planner prompt with a clean
 *   `{"calls": [...]}` object that parses directly (no salvage).
 * - Parser compatibility: the output needed [ToolCallParser]'s fallback
 *   scanning — still fully functional, but the model is not natively
 *   tool-calling.
 *
 * The tool pipeline is identical in both modes; this is diagnostics for the
 * Tool Debug screen and for choosing planner prompt verbosity.
 */
data class LocalToolCapability(
    val modelName: String = "",
    val planningRounds: Int = 0,
    val cleanParses: Int = 0,
    val fallbackParses: Int = 0,
    val lastOutputSample: String = "",
    /** Probe result message when [probeCapability] ran ("" = stats only). */
    val probeStatus: String = ""
) {
    /**
     * True when clean parses dominate the planning rounds — the loaded model
     * natively emits structured tool JSON (no parser salvage needed).
     */
    val nativeJsonSupport: Boolean get() = cleanParses >= fallbackParses && planningRounds > 0
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
    fun system(specs: List<ToolSpec>, contextBlock: String = "", maxChars: Int = 0): String {
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
            // Same budget guard as ToolPromptBuilder: small-context containers
            // (2048-token Qwen3 repacks) can be overrun by the tool list alone,
            // which makes the planning pass fail with "token ids are too long".
            if (maxChars > 0 && sb.length >= maxChars) break
            val line = StringBuilder()
            line.append("- ").append(spec.name).append(": ").append(spec.description).append('\n')
            val params = describeParameters(spec.parameters)
            if (params.isNotBlank()) line.append("    arguments: ").append(params).append('\n')
            if (maxChars > 0 && sb.length + line.length > maxChars) break
            sb.append(line)
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
