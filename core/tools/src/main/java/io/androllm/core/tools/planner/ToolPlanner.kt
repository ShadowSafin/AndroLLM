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
import io.androllm.core.tools.validation.JsonSchemaValidator
import io.androllm.core.tools.validation.PromptInjectionDetector
import io.androllm.core.tools.validation.ToolCallValidator
import io.androllm.core.tools.validation.ToolExecutionLogger
import io.androllm.core.tools.validation.ValidationResult
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
    private val router: ToolRouter,
    private val validator: ToolCallValidator = ToolCallValidator(registry),
    private val logger: ToolExecutionLogger = ToolExecutionLogger()
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
     *
     * Hardened pipeline:
     * Assistant -> Generate response -> Tool detector -> JSON validator -> Registry validation -> Argument validation -> Execute
     * - Prompt injection protection: sanitizes user prompt attempting to invent tools/bypass validation
     * - Tool selection: router decides if tool required; if none, returns empty (normal assistant response)
     * - Strict JSON schema validation (types, required, enums, extra fields, nullable)
     * - Registry validation (unknown -> reject, never retry)
     * - Retry once if JSON malformed or validation fails due to formatting
     * - Recovery: remove invalid calls, ask missing info if needed, else answer normally
     * - Logging: selected tool, validation result, errors (never exposed to user)
     */
    suspend fun planLocal(
        messages: List<ChatPromptMessage>,
        hasAttachments: Boolean = false
    ): List<ToolCall> {
        // ── Prompt injection protection ──────────────────────────────────────
        val rawLatestUser = messages.lastOrNull { it.role == "user" }?.content.orEmpty()
        // Detect injection attempting to invent tools / bypass validation
        PromptInjectionDetector.validateUserPrompt(rawLatestUser)?.let { warning ->
            Timber.w("ToolPlanner: injection warning: $warning")
        }
        // Sanitize but preserve real request: strip hidden tool syntax while keeping user intent
        val latestUser = if (PromptInjectionDetector.isInjectionAttempt(rawLatestUser)) {
            val sanitized = PromptInjectionDetector.sanitizeRetrievedDocument(rawLatestUser)
            Timber.w("ToolPlanner: sanitized injection attempt (${rawLatestUser.length} -> ${sanitized.length} chars)")
            sanitized
        } else rawLatestUser

        // ── Tool selection: must first decide whether a tool is actually required ──
        val routed = router.route(latestUser, hasAttachments = hasAttachments, enabledTools = allowedTools())
        if (routed.specs.isEmpty()) {
            if (routed.intent != ToolIntent.GENERAL) {
                Timber.i("ToolPlanner: route=${routed.intent.name} — no tools exposed, skipping plan")
            }
            logger.logSelection(emptyList(), latestUser)
            return emptyList()
        }
        logger.logSelection(routed.specs.map { it.name }, latestUser)
        // Never allow prompt to modify available tool list — routed specs are already filtered to registry
        val specs = routed.specs
        // Defensive: planning needs a loaded GGUF model.
        if (engineRepository.engineState.value !is io.androllm.engine.api.EngineState.Ready) {
            Timber.w("ToolPlanner: no model loaded — skipping plan")
            return emptyList()
        }

        // Budget the planner system prompt to the container's REAL context
        val realContext = (engineRepository.engineState.value as? io.androllm.engine.api.EngineState.Ready)
            ?.model?.contextLength
            ?: io.androllm.core.common.AppConstants.Model.DEFAULT_CONTEXT_LENGTH
        val planSystemChars = ((realContext - 512 - 256).coerceAtLeast(0)) * 4
        val basePlanSystem = ToolPrompts.system(specs, agentContext.buildBlock(), maxChars = planSystemChars)
        val baseUserContent = ToolPrompts.buildUserContent(messages)

        val config = GenerationConfig(
            maxTokens = 512,
            temperature = 0.1f,
            topP = 1.0f,
            minP = 0.0f,
            repetitionPenalty = 1.05f,
            jsonSchema = PLAN_SCHEMA,
            reuseKvCache = false
        )

        // ── Hardened generation with retry logic ─────────────────────────────
        var attempt = 0
        var retryReason: String? = null
        while (attempt < 2) {
            val planMessages = if (attempt == 0) {
                listOf(
                    ChatPromptMessage(role = "system", content = basePlanSystem),
                    ChatPromptMessage(role = "user", content = baseUserContent)
                )
            } else {
                // Retry with correction instruction for malformed JSON / formatting errors
                listOf(
                    ChatPromptMessage(role = "system", content = basePlanSystem),
                    ChatPromptMessage(
                        role = "user",
                        content = baseUserContent + "\n\n[Correction: Your previous JSON was invalid: $retryReason. Output ONLY valid JSON {\"calls\":[{\"name\":\"tool_name\",\"arguments\":{...}}]} with correct types, required fields, no extra fields. No prose.]"
                    )
                )
            }
            val prompt = engineRepository.buildChatPrompt(planMessages, addAssistant = true).getOrNull()
            if (prompt.isNullOrBlank()) {
                Timber.w("ToolPlanner: chat template unavailable")
                return emptyList()
            }
            val timeoutMs = planningTimeoutMs(prompt.length)
            val output = withTimeoutOrNull(timeoutMs) {
                engineRepository.generateQuiet(prompt, config, timeoutMs = timeoutMs)
            }?.getOrNull()
            if (output.isNullOrBlank()) {
                Timber.w("ToolPlanner: planning pass produced no output within ${timeoutMs}ms (attempt ${attempt + 1})")
                // Retry once if no output (could be transient)
                if (attempt == 0) {
                    retryReason = "empty output"
                    attempt++
                    logger.logRetry("<no_output>", 1, "empty output")
                    continue
                }
                return emptyList()
            }

            // Live capability tracking
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
                "ToolPlanner: round=${_capability.value.planningRounds} clean=${_capability.value.cleanParses} fallback=${_capability.value.fallbackParses} model=${_capability.value.modelName} attempt=${attempt + 1}"
            )

            val calls = ToolCallParser.parse(output)
            val looksLikeToolAttempt = output.contains("\"name\"") || output.contains("\"calls\"")

            // ── JSON validator: malformed JSON detection ─────────────────────
            if (calls.isEmpty() && looksLikeToolAttempt) {
                val syntaxResult = JsonSchemaValidator.validateJsonSyntax(output)
                if (syntaxResult is ValidationResult.Invalid) {
                    logger.logValidation("<malformed>", syntaxResult)
                    Timber.w("ToolPlanner: malformed JSON detected: ${syntaxResult.firstError}")
                    if (attempt == 0) {
                        logger.logRetry("<malformed>", 1, syntaxResult.firstError)
                        retryReason = syntaxResult.firstError
                        attempt++
                        continue // Retry once for malformed JSON
                    } else {
                        // Recovery: remove invalid, answer normally
                        return emptyList()
                    }
                }
                // Empty but looked like tool attempt yet valid JSON -> model chose no tools (e.g., {"calls":[]}) -> normal
                return emptyList()
            }

            if (calls.isEmpty()) {
                // Fallback: local model failed to emit valid tool syntax — try heuristic
                // intent detection from the user's natural language (requirement 9).
                // This makes plain-text-only models first-class citizens.
                val heuristic = heuristicFallback(latestUser, specs)
                if (heuristic.isNotEmpty()) {
                    Timber.i("ToolPlanner: heuristic fallback from user intent produced ${heuristic.size} call(s): ${heuristic.map { it.name }}")
                    logger.logSelection(heuristic.map { it.name }, "$latestUser [heuristic fallback]")
                    // Validate heuristic calls through strict validator before returning
                    val validatedHeuristic = heuristic.mapNotNull { call ->
                        val v = validator.validate(call)
                        logger.logValidation(call.name, v)
                        if (v is ValidationResult.Valid) call else null
                    }
                    if (validatedHeuristic.isNotEmpty()) {
                        logger.logValidation("fallback", ValidationResult.Valid)
                        return validatedHeuristic
                    }
                }
                // Model correctly decided no tool needed -> normal assistant response
                return emptyList()
            }

            // ── Registry + Argument validation via strict validator ──────────
            var hasUnknownTool = false
            var hasRetryableFormattingError = false
            val validCalls = mutableListOf<ToolCall>()
            val invalidPairs = mutableListOf<Pair<ToolCall, ValidationResult.Invalid>>()

            for (call in calls) {
                // Empty tool name -> immediate reject
                if (call.name.isBlank()) {
                    val invalid = ValidationResult.Invalid("Tool name must not be empty", retryable = false)
                    logger.logValidation("<empty>", invalid)
                    invalidPairs += call to invalid
                    continue
                }
                // Hallucinated tool name check (injection protection)
                val knownNames = registry.all().map { it.spec.name }.toSet()
                if (PromptInjectionDetector.isHallucinatedToolName(call.name, knownNames)) {
                    Timber.w("ToolPlanner: hallucinated tool name '${call.name}' detected")
                }
                val result = validator.validate(call)
                logger.logValidation(call.name, result)
                when (result) {
                    is ValidationResult.Valid -> validCalls += call
                    is ValidationResult.Invalid -> {
                        if (!result.retryable) hasUnknownTool = true else hasRetryableFormattingError = true
                        invalidPairs += call to result
                    }
                }
            }

            if (invalidPairs.isNotEmpty()) {
                Timber.w("ToolPlanner: ${invalidPairs.size} invalid call(s): ${invalidPairs.map { it.second.firstError }}")
                // Safety: Never retry if tool does not exist
                if (hasUnknownTool) {
                    Timber.w("ToolPlanner: unknown tool detected — not retrying, returning only valid calls")
                    // Recovery: remove invalid, return only valid (may be empty -> answer normally)
                    // Log hallucinated names
                    return validCalls
                }
                // Retry once if validation fails due to formatting (missing required, type mismatch, extra field, enum)
                if (hasRetryableFormattingError && attempt == 0) {
                    val firstError = invalidPairs.first().second.firstError
                    logger.logRetry(invalidPairs.first().first.name, 1, firstError)
                    retryReason = firstError
                    attempt++
                    continue
                }
                // Recovery: remove invalid tool calls, return only valid; if none valid, ask missing info or answer normally
                // Missing required params -> downstream can ask user, but we just return valid subset
                return validCalls
            }

            // All calls valid
            return validCalls
        }
        // Final heuristic fallback after all retries exhausted but no valid calls emerged
        // (e.g., model never emitted valid JSON). Try once more from user intent.
        val finalFallback = heuristicFallback(latestUser, specs)
        if (finalFallback.isNotEmpty()) {
            Timber.i("ToolPlanner: final heuristic fallback produced ${finalFallback.size} call(s)")
            val validated = finalFallback.mapNotNull { call ->
                val v = validator.validate(call)
                logger.logValidation(call.name, v)
                if (v is ValidationResult.Valid) call else null
            }
            if (validated.isNotEmpty()) return validated
        }
        return emptyList()
    }

    /**
     * Heuristic fallback: when the local model fails to emit structured tool syntax,
     * infer intent directly from the user's natural language and synthesize a
     * normalized ToolCall with heuristic argument extraction (requirement 9).
     * The result is still validated strictly before execution.
     */
    private fun heuristicFallback(userMessage: String, specs: List<ToolSpec>): List<ToolCall> {
        if (userMessage.isBlank() || specs.isEmpty()) return emptyList()
        val lower = userMessage.lowercase()
        val specsByName = specs.associateBy { it.name }
        val out = mutableListOf<ToolCall>()

        // Calculator: detect math expression in user request
        if (specsByName.containsKey("calculate") && (lower.contains("calculat") || lower.contains("evaluate") || lower.contains("solve") || Regex("""\d+\s*[+\-*/x×÷^%]\s*\d+""").containsMatchIn(userMessage))) {
            val expr = extractMathExpressionFromUser(userMessage)
            if (!expr.isNullOrBlank()) {
                out += ToolCall(
                    id = "call_calculate_heuristic_0",
                    name = "calculate",
                    arguments = buildJsonObject { put("expression", expr) }
                )
                return out
            } else {
                // Try to extract any math-like content after the trigger phrase
                val fallbackExpr = Regex("""([\d\.\s\+\-\*\/\(\)\^%xX×÷]+)""").find(userMessage)?.groupValues?.get(1)?.trim()?.replace(Regex("""\s+"""), "")
                if (!fallbackExpr.isNullOrBlank() && fallbackExpr.any { it.isDigit() } && fallbackExpr.length >= 2) {
                    val normalized = fallbackExpr.replace(Regex("""[xX×]"""), "*").replace("÷", "/")
                    out += ToolCall(id = "call_calculate_heuristic_0", name = "calculate", arguments = buildJsonObject { put("expression", normalized) })
                    return out
                }
            }
        }

        // Web search
        if (specsByName.containsKey("search_web") && (lower.contains("search") || lower.contains("look up") || lower.contains("google"))) {
            val q = extractSearchQueryFromUser(userMessage)
            if (!q.isNullOrBlank()) {
                out += ToolCall(id = "call_search_web_heuristic_0", name = "search_web", arguments = buildJsonObject { put("query", q) })
                return out
            }
        }

        // Weather
        if (specsByName.containsKey("get_weather") && (lower.contains("weather") || lower.contains("forecast"))) {
            val loc = extractWeatherLocationFromUser(userMessage)
            out += ToolCall(id = "call_get_weather_heuristic_0", name = "get_weather", arguments = buildJsonObject { put("location", loc ?: "Current") })
            return out
        }

        // General keyword fallback: pick the highest-confidence routed tool and emit a minimal call
        // This covers sms, phone, calendar, contacts, location, etc. — validation will request missing args
        val best = specs.maxByOrNull { spec ->
            val haystack = (spec.name + " " + spec.description + " " + spec.supportedTasks.joinToString(" ")).lowercase()
            var hits = 0
            for (tok in userMessage.lowercase().split(Regex("""\s+"""))) {
                if (tok.length >= 3 && tok in haystack) hits++
            }
            hits
        }
        if (best != null) {
            val hasSignal = best.supportedTasks.any { it.lowercase() in lower } || lower.contains(best.name) || lower.contains(best.name.replace("_", " "))
            if (hasSignal) {
                out += ToolCall(id = "call_${best.name}_heuristic_0", name = best.name, arguments = buildJsonObject { })
                return out
            }
        }

        return out
    }

    private fun extractMathExpressionFromUser(text: String): String? {
        val afterKeyword = Regex("""(?:evaluate|calculate|computed?|solve|what\s+is|is)\s*[:\-]?\s*([0-9][0-9\s\.\+\-\*\/\(\)\^%xX×÷]*[0-9\)])""", RegexOption.IGNORE_CASE).find(text)
        if (afterKeyword != null) {
            var expr = afterKeyword.groupValues[1].trim().trimEnd('.', ',', '!', '?')
            expr = expr.replace(Regex("""[xX×]"""), "*").replace("÷", "/").replace(Regex("""\s+"""), "")
            if (expr.isNotBlank() && expr.any { it.isDigit() }) return expr
        }
        val m = Regex("""(\d[\d\s\.\+\-\*\/\(\)\^%]*\d)""").find(text)
        if (m != null) {
            var expr = m.groupValues[1].trim().replace(Regex("""\s+"""), "")
            expr = expr.replace(Regex("""[xX×]"""), "*").replace("÷", "/")
            if (expr.length >= 1) return expr
        }
        return null
    }

    private fun extractSearchQueryFromUser(text: String): String? {
        val patterns = listOf(
            Regex("""search\s+for\s+(.+?)(?:\.|$|and|then)""", RegexOption.IGNORE_CASE),
            Regex("""look\s+up\s+(.+?)(?:\.|$|and|then)""", RegexOption.IGNORE_CASE),
            Regex("""google\s+(.+?)(?:\.|$|and|then)""", RegexOption.IGNORE_CASE),
            Regex("""search\s+(.+?)(?:\.|$|and|then)""", RegexOption.IGNORE_CASE)
        )
        for (pat in patterns) {
            val mm = pat.find(text)
            if (mm != null) {
                var q = mm.groupValues[1].trim().trimEnd('.', ',', '!', '?', '"', '\'').trim()
                q = q.replace(Regex("""^["']|["']$"""), "").trim()
                if (q.length >= 2) return q
            }
        }
        return null
    }

    private fun extractWeatherLocationFromUser(text: String): String? {
        val pat = Regex("""weather\s+(?:in|for|at)\s+([A-Za-z][A-Za-z\s\-]+)""", RegexOption.IGNORE_CASE).find(text)
        if (pat != null) {
            var loc = pat.groupValues[1].trim().trimEnd('.', ',', '!', '?')
            loc = loc.split(Regex("""\b(and|then|today|tomorrow)\b""", RegexOption.IGNORE_CASE))[0].trim()
            if (loc.length in 2..40) return loc
        }
        return null
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
    /**
     * Dedicated local-model tool-calling instruction template (requirement 8).
     * Tells the local model exactly how/when to request tools, the JSON format,
     * and forbids leaking raw tool syntax to the user.
     */
    const val LOCAL_TOOL_INSTRUCTION_HEADER = """
You are the tool planner of an on-device AI assistant. Your ONLY job is to decide which tools to call for the user's LATEST request.

HOW TO REQUEST TOOLS:
- If the request needs a device action, network lookup, or communication (search, weather, calculator, opening apps, messaging, calendar, contacts, files, etc.), you MUST request the matching tool via JSON.
- If the request is small talk, creative writing, or can be answered from your own knowledge (explain a concept, write a poem, summarize without tools), output {"calls": []} — no tool.

WHEN TO REQUEST TOOLS:
- Only when the tool matches the user's intent and the required arguments are present in the conversation, the context block, or previous tool results.
- Never invent argument values. If a required argument is missing, omit the call (return {"calls": []}) and the assistant will ask the user for the missing info.

HOW TO FORMAT TOOL INTENTS (JSON ONLY):
- Output EXACTLY one JSON object: {"calls": [{"name": "tool_name", "arguments": { ... }}]}
- Use EXACTLY the tool names and argument names listed under AVAILABLE TOOLS.
- CORRECT examples:
  {"calls": [{"name": "calculate", "arguments": {"expression": "2+2"}}]}
  {"calls": [{"name": "search_web", "arguments": {"query": "android ai"}}]}
  {"calls": [{"name": "get_weather", "arguments": {"location": "Delhi"}}]}
  {"calls": []}
- INCORRECT — NEVER output these (they will be rejected and stripped):
  <tool_call>{"name":"web_search",...}</tool_call>
  {"tool":"calculator","args":{"expression":"2+2"}}
  Use the calculator to evaluate 2+2.
  Any XML tags, markdown, prose, or <tool_call> markup.

CRITICAL SAFETY:
- NEVER leak raw tool syntax, JSON blobs, XML tags, or internal reasoning to the user. The UI strips them and the user must see ONLY your final natural-language answer.
- NEVER answer with tool markup unless the system explicitly expects JSON (this planner turn). For normal answer turns, output plain text only.
- If you are the planner, output ONLY the JSON object — no prose, no markdown, no explanation, no tool_call tags.
"""

    fun system(specs: List<ToolSpec>, contextBlock: String = "", maxChars: Int = 0): String {
        val sb = StringBuilder()
        sb.append(LOCAL_TOOL_INSTRUCTION_HEADER.trimIndent())
        sb.append("\n\nRules:\n")
        sb.append(
            """
            - Output {"calls": []} when no tool is needed (small talk, questions that the assistant can answer from its own knowledge, requests to write text, summarize, translate, explain, or review code).
            - Use exactly the tool names and argument names listed below.
            - Supply only arguments that appear in the conversation, the context below, or the results of tools you already ran; never invent values. If a required argument is missing, omit the call.
            - Prefer dedicated tools (send_sms, set_alarm, get_weather, maps, launcher) over ui_* UI-automation tools whenever one exists. Use ui_run / ui_click / ui_type only for apps that have NO native tool (e.g. WhatsApp, Uber, YouTube).
            - For "find the nearest X" or navigation requests, prefer the maps tools.
            - You may emit MULTIPLE calls when a task needs several steps. Emit them in execution order — the results of earlier calls are available to later calls in the NEXT round via variable_get and the context block.
            - Conditional workflows: after running a tool, branch on its result in the next round (IF/ELSE). For lists, iterate item by item (FOR EACH) using variable_set to remember the current index; stop when done (WHILE) — never emit more than a few iterations per round.
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
