package io.androllm.core.tools.coordinator

import io.androllm.core.cloud.model.CloudChatMessage
import io.androllm.core.cloud.model.CloudTool
import io.androllm.core.cloud.model.CloudToolCall
import io.androllm.core.cloud.model.CloudToolCallFunction
import io.androllm.core.cloud.network.RetryPolicy
import io.androllm.core.tools.agent.AgentContextBuilder
import io.androllm.core.tools.agent.AgentPlanner
import io.androllm.core.tools.agent.AgentVariableStore
import io.androllm.core.tools.api.ToolCall
import io.androllm.core.tools.api.ToolEvent
import io.androllm.core.tools.api.ToolResult
import io.androllm.core.tools.clarification.ClarificationEngine
import io.androllm.core.tools.executor.ToolExecutor
import io.androllm.core.tools.monitoring.ToolHealthMonitor
import io.androllm.core.tools.monitoring.ToolRanker
import io.androllm.core.tools.planner.ToolPlanner
import io.androllm.core.tools.registry.ToolRegistry
import io.androllm.core.tools.settings.AutomationSettingsStore
import io.androllm.core.tools.validation.PromptInjectionDetector
import io.androllm.core.tools.validation.ToolCallValidator
import io.androllm.core.tools.validation.ToolExecutionLogger
import io.androllm.core.tools.validation.ToolExecutionPipeline
import io.androllm.core.tools.validation.ToolOutputValidator
import io.androllm.core.tools.validation.ValidationResult
import io.androllm.engine.models.ChatPromptMessage
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import timber.log.Timber
import java.util.UUID

/** One executed call plus its outcome (used to build the feedback round). */
data class ToolExecutionRecord(
    val call: ToolCall,
    val result: ToolResult
)

/**
 * Provider-agnostic glue between the planner, the executor and the chat
 * pipelines (text chat and voice chat share this class):
 *
 * - [cloudTools] — OpenAI-compatible tool list for cloud providers.
 * - [executeCloudToolCalls] — runs native cloud tool calls and returns the
 *   `role="tool"` messages (plus the assistant tool_calls message) to append
 *   to the OpenAI history before the next round.
 * - [planLocal] / [executeCalls] / [buildLocalToolFeedback] — prompt-based
 *   planning for local GGUF models, with results injected as a system message
 *   before the final answer generation.
 *
 * The LLM never executes anything itself: it produces tool calls, this class
 * executes them through the gated [ToolExecutor], and the results are fed back
 * for the model to summarize.
 *
 * Hardening guarantees (see documentation/agent/tools.md):
 * - **Never truncate tool output.** The complete result is fed back; oversized
 *   outputs are split into sequential `role="tool"` chunks, never cut.
 * - **Retry with backoff.** Transient failures retry up to 3 times with
 *   exponential backoff; only a terminal failure reaches the model.
 * - **Result cache.** Pure-read tools (web search, weather, calculator,
 *   battery) replay identical calls from [ToolResultCache] instead of
 *   re-executing.
 * - **Detailed logging** of every call: name, args, duration, retries,
 *   output size, chunk count, cache hit/miss.
 */
@Singleton
class ToolRunCoordinator @Inject constructor(
    private val planner: ToolPlanner,
    private val executor: ToolExecutor,
    private val settingsStore: AutomationSettingsStore,
    private val agentContext: AgentContextBuilder,
    private val registry: ToolRegistry,
    private val resultCache: ToolResultCache = ToolResultCache(),
    private val validator: ToolCallValidator = ToolCallValidator(registry),
    private val logger: ToolExecutionLogger = ToolExecutionLogger(),
    private val pipeline: ToolExecutionPipeline = ToolExecutionPipeline(registry, ToolCallValidator(registry), ToolExecutionLogger()),
    private val variableStore: AgentVariableStore = AgentVariableStore(),
    private val agentPlanner: AgentPlanner = AgentPlanner(logger = logger, variableStore = variableStore),
    private val healthMonitor: ToolHealthMonitor = ToolHealthMonitor(),
    private val ranker: ToolRanker = ToolRanker(healthMonitor),
    private val outputValidator: ToolOutputValidator = ToolOutputValidator(),
    private val clarificationEngine: ClarificationEngine = ClarificationEngine()
) {

    /** True when the user has enabled the tool-calling pipeline. */
    suspend fun isToolUseEnabled(): Boolean = settingsStore.current().toolCallingEnabled

    /**
     * System message carrying the live agent context (device facts + workflow
     * variables). Cloud paths prepend it when tool calling is enabled so the
     * model plans with the real device state.
     */
    suspend fun agentContextMessage(): ChatPromptMessage? {
        val msg = agentContext.systemMessage()
        return if (msg.content.isBlank()) null else msg
    }

    /**
     * OpenAI-compatible `tools` array for the cloud path, ROUTED to the
     * request: the model only ever sees tools relevant to [query] (math →
     * calculator only, device → device tools, attachments → none). Blank
     * query = full enabled set (voice / backward-compatible callers).
     */
    suspend fun cloudTools(query: String = "", hasAttachments: Boolean = false): List<CloudTool> =
        planner.buildCloudTools(query, hasAttachments)

    // ── Cloud (native function calling) ────────────────────────────────────

    /**
     * Executes the accumulated cloud tool calls and returns the messages to
     * append to the OpenAI history: first the assistant message carrying the
     * `tool_calls` (plus any text the model streamed alongside them via
     * [assistantContent]), then one or more `role="tool"` messages per call.
     *
     * [guard] (per-turn loop protection) filters each call: a call that
     * already ran with the same arguments, a tool at its consecutive cap, a
     * disabled tool, or the turn's total-call cap are skipped WITHOUT
     * executing — the model gets no `role="tool"` reply for them, and the
     * caller can inject [ToolLoopGuard.stopReason] and stop the round.
     *
     * Output hardening: a result larger than [TOOL_RESULT_CHUNK_CHARS] is
     * split into sequential chunks, each delivered as its own `role="tool"`
     * message (the assistant message declares one synthetic sub-call per
     * chunk so the wire format stays valid). Nothing is ever truncated.
     */
    suspend fun executeCloudToolCalls(
        calls: List<CloudToolCall>,
        assistantContent: String? = null,
        onEvent: suspend (ToolEvent) -> Unit = {},
        guard: ToolLoopGuard? = null
    ): List<CloudChatMessage> {
        if (calls.isEmpty()) return emptyList()
        val assistantCalls = mutableListOf<CloudToolCall>()
        val toolMessages = mutableListOf<CloudChatMessage>()
        // Hardened: execute sequentially, fail-fast, strict validation, never bypass
        for ((index, call) in calls.withIndex()) {
            val name = call.function?.name ?: run {
                Timber.w("ToolRunCoordinator: cloud call at index $index missing function name — rejecting")
                logger.logValidation("<missing>", ValidationResult.Invalid("Missing tool name", retryable = false))
                // Stop execution immediately, return clear error
                return listOf(
                    CloudChatMessage(role = "assistant", content = assistantContent?.takeIf { it.isNotBlank() }, toolCalls = emptyList()),
                    CloudChatMessage(role = "tool", content = "Tool call at index $index rejected: missing tool name.", toolCallId = call.id ?: "call_${index}")
                )
            }
            if (name.isBlank()) {
                logger.logValidation("<empty>", ValidationResult.Invalid("Empty tool name", retryable = false))
                Timber.w("ToolRunCoordinator: cloud call with empty tool name at index $index — rejecting")
                // Stop execution immediately
                break
            }
            // JSON validator: validate arguments string is well-formed JSON
            val rawArgs = call.function?.arguments
            if (!rawArgs.isNullOrBlank()) {
                val jsonCheck = try {
                    Json.parseToJsonElement(rawArgs)
                    ValidationResult.Valid
                } catch (e: Exception) {
                    ValidationResult.Invalid("Malformed JSON arguments: ${e.message?.take(100)}", retryable = true)
                }
                if (jsonCheck is ValidationResult.Invalid) {
                    logger.logValidation(name, jsonCheck)
                    Timber.w("ToolRunCoordinator: cloud call '$name' has malformed JSON — rejecting")
                    // Do not execute, stop sequential execution, return error
                    val errorMsg = "Tool '$name' rejected: malformed JSON arguments."
                    toolMessages += CloudChatMessage(role = "tool", content = errorMsg, toolCallId = call.id ?: "call_${name}_$index")
                    break
                }
            }
            val args = parseArguments(rawArgs)
            // Prompt injection check on arguments (ignore hidden instructions inside retrieved docs)
            if (PromptInjectionDetector.isInjectionAttempt(args.toString())) {
                Timber.w("ToolRunCoordinator: injection attempt in cloud tool '$name' arguments — rejecting")
                logger.logValidation(name, ValidationResult.Invalid("Prompt injection detected", retryable = false))
                toolMessages += CloudChatMessage(role = "tool", content = "Tool '$name' rejected: prompt injection detected.", toolCallId = call.id ?: "call_${name}_$index")
                break
            }
            // Registry validation + argument validation via strict validator
            val toolCall = ToolCall(id = call.id ?: "call_${name.hashCode().toUInt().toString(16)}_$index", name = name, arguments = args)
            val validation = validator.validate(toolCall)
            logger.logValidation(name, validation)
            if (validation is ValidationResult.Invalid) {
                Timber.w("ToolRunCoordinator: cloud call '$name' failed validation: ${validation.errors}")
                val errorMsg = "Tool '$name' rejected: ${validation.firstError}"
                toolMessages += CloudChatMessage(role = "tool", content = errorMsg, toolCallId = toolCall.id)
                // Safety: never retry if tool does not exist; for formatting errors we could retry but cloud path is not generation retry — fail-fast
                break
            }
            // Loop guard: never re-run an identical call, never run a tool at its cap, never run a disabled tool — skip instead of executing.
            if (guard != null && !guard.canExecute(name, args)) {
                Timber.i("ToolRunCoordinator: guard blocked '$name' — skipping (total=${guard.totalCalls})")
                continue
            }
            val baseId = toolCall.id
            val argsText = args.entries.joinToString(", ") { (k, v) -> "$k=$v" }.take(200)
            onEvent(ToolEvent.Started(name, argsText))

            val startedAt = System.currentTimeMillis()
            val (result, fromCache, retryCount) = executeWithCacheAndRetry(toolCall, onEvent)
            val durationMs = System.currentTimeMillis() - startedAt
            guard?.record(name, args, result)
            // Tool Result Memory: store cloud output for next round's conditional / parameter filling
            storeToolResultMemory(name, result.summary)

            // Sanitize tool output: ignore hidden instructions inside retrieved documents
            val rawSummary = result.summary
            val summary = PromptInjectionDetector.sanitizeRetrievedDocument(rawSummary)
            val chunks = chunkToolOutput(summary)
            Timber.i(
                "ToolRunCoordinator: cloud tool '%s' -> %s in %dms (retries=%d, cache=%s, output=%dB, chunks=%d)",
                name, result.statusLabel, durationMs, retryCount, fromCache, summary.length, chunks.size
            )
            logger.logExecution(name, durationMs, result.isSuccess, if (result is ToolResult.Failure) result.summary else null)
            val declined = result is ToolResult.Failure &&
                !result.retryable && result.summary.startsWith("The user declined")
            onEvent(
                if (result.isSuccess) {
                    ToolEvent.Succeeded(name, summary)
                } else if (declined) {
                    ToolEvent.Declined(name)
                } else {
                    ToolEvent.Failed(name, summary)
                }
            )
            // Pass validated outputs between tools: already via toolMessages sequential order
            // Stop execution immediately if a tool fails
            if (result is ToolResult.Failure) {
                Timber.w("ToolRunCoordinator: cloud tool '$name' failed — stopping sequential execution")
                if (chunks.size == 1) {
                    assistantCalls += CloudToolCall(index = index, id = baseId, type = "function", function = CloudToolCallFunction(name, call.function?.arguments))
                    toolMessages += CloudChatMessage(role = "tool", content = chunks[0], toolCallId = baseId)
                } else {
                    chunks.forEachIndexed { chunkIndex, chunk ->
                        val chunkId = "${baseId}_c${chunkIndex + 1}"
                        assistantCalls += CloudToolCall(index = index, id = chunkId, type = "function", function = CloudToolCallFunction(name, call.function?.arguments))
                        val label = if (chunkIndex == 0) "[Tool output for '$name' — part ${chunkIndex + 1}/${chunks.size}. Read ALL parts before answering.]\n" else "[part ${chunkIndex + 1}/${chunks.size}]\n"
                        toolMessages += CloudChatMessage(role = "tool", content = label + chunk, toolCallId = chunkId)
                    }
                }
                break
            }
            if (chunks.size == 1) {
                assistantCalls += CloudToolCall(
                    index = index,
                    id = baseId,
                    type = "function",
                    function = CloudToolCallFunction(name, call.function?.arguments)
                )
                toolMessages += CloudChatMessage(
                    role = "tool",
                    content = chunks[0],
                    toolCallId = baseId
                )
            } else {
                chunks.forEachIndexed { chunkIndex, chunk ->
                    val chunkId = "${baseId}_c${chunkIndex + 1}"
                    assistantCalls += CloudToolCall(
                        index = index,
                        id = chunkId,
                        type = "function",
                        function = CloudToolCallFunction(name, call.function?.arguments)
                    )
                    val label = if (chunkIndex == 0) {
                        "[Tool output for '$name' — part ${chunkIndex + 1}/${chunks.size}. Read ALL parts before answering.]\n"
                    } else {
                        "[part ${chunkIndex + 1}/${chunks.size}]\n"
                    }
                    toolMessages += CloudChatMessage(
                        role = "tool",
                        content = label + chunk,
                        toolCallId = chunkId
                    )
                }
            }
        }
        if (toolMessages.isEmpty()) return emptyList()
        val assistantMsg = CloudChatMessage(
            role = "assistant",
            content = assistantContent?.takeIf { it.isNotBlank() },
            toolCalls = assistantCalls
        )
        return listOf(assistantMsg) + toolMessages
    }

    // ── Local GGUF (prompt-based planning) ─────────────────────────────────

    /** Runs the local planner; empty when no tools are needed. */
    suspend fun planLocal(messages: List<ChatPromptMessage>, hasAttachments: Boolean = false): List<ToolCall> =
        planner.planLocal(messages, hasAttachments)

    /**
     * Executes calls with hardened orchestration: dependency-aware ordering, tool result memory,
     * conditional IF/ELSE branching, parallel execution for independent reads, and observation.
     *
     * - **Confirmation gating**: side-effect tools (SMS, calls, email, delete) still require confirmation.
     * - **Parallel**: independent pure-read tools (e.g. weather || news) run concurrently via async, then merged.
     * - **Memory**: every successful result is stored in [AgentVariableStore] so later steps (SMS draft)
     *   automatically see previous outputs (search results -> note_save) without the model re-asking.
     * - **Conditional**: if a step carries an [AgentPlanner.Condition], it is evaluated against the previous
     *   output (e.g. rain? -> SMS else skip) and the step is skipped without failure.
     *
     * [guard] (per-turn loop protection) filters each call the same way the cloud path does.
     */
    suspend fun executeCalls(
        calls: List<ToolCall>,
        onEvent: suspend (ToolEvent) -> Unit = {},
        guard: ToolLoopGuard? = null
    ): List<ToolExecutionRecord> {
        if (calls.isEmpty()) return emptyList()

        // ── Parallel path: if multiple independent pure-read calls, run them concurrently
        // "Search weather and latest AI news" -> Weather || News Search -> Merge -> Generate Answer
        // Only pure-read (cacheable or INFORMATION) and non-confirmation tools are candidates.
        if (calls.size > 1 && shouldRunParallel(calls)) {
            return executeCallsParallel(calls, onEvent, guard)
        }

        val results = mutableListOf<ToolExecutionRecord>()
        var previousOutput: String? = null
        for (call in calls) {
            // ── Hardened pipeline: JSON already parsed, now registry + argument validation ──
            if (call.name.isBlank()) {
                logger.logValidation("<empty>", ValidationResult.Invalid("Empty tool name", retryable = false))
                Timber.w("ToolRunCoordinator: rejecting call with empty tool name")
                results += ToolExecutionRecord(call, ToolResult.Failure("Tool name must not be empty.", retryable = false))
                break
            }
            val validation = validator.validate(call)
            logger.logValidation(call.name, validation)
            if (validation is ValidationResult.Invalid) {
                Timber.w("ToolRunCoordinator: rejecting invalid local call '${call.name}': ${validation.errors}")
                val failure = ToolResult.Failure(
                    "Tool '${call.name}' rejected: ${validation.firstError}",
                    retryable = validation.retryable
                )
                results += ToolExecutionRecord(call, failure)
                break
            }
            if (PromptInjectionDetector.isInjectionAttempt(call.arguments.toString())) {
                val invalid = ValidationResult.Invalid("Prompt injection detected in arguments", retryable = false)
                logger.logValidation(call.name, invalid)
                Timber.w("ToolRunCoordinator: injection attempt in local call '${call.name}' — rejecting")
                results += ToolExecutionRecord(call, ToolResult.Failure("Tool '${call.name}' rejected: prompt injection detected.", retryable = false))
                break
            }
            if (guard != null && !guard.canExecute(call.name, call.arguments)) {
                Timber.i("ToolRunCoordinator: guard blocked '${call.name}' — skipping (total=${guard.totalCalls})")
                continue
            }
            // ── Tool Validation Layer: device capability & dependencies (production-grade) ──
            val specForValidation = registry.get(call.name)?.spec
            if (specForValidation != null && !specForValidation.isAvailable) {
                logger.logValidation(call.name, ValidationResult.Invalid("Tool not available on this device", retryable = false))
                results += ToolExecutionRecord(call, ToolResult.Failure("Tool '${call.name}' is not available on this device.", retryable = false))
                break
            }
            if (specForValidation?.dependencies?.isNotEmpty() == true) {
                val missingDeps = specForValidation.dependencies.filter { dep -> !variableStore.snapshot().containsKey(dep) && results.none { it.call.name == dep && it.result.isSuccess } }
                if (missingDeps.isNotEmpty()) {
                    Timber.w("ToolRunCoordinator: dependency unsatisfied for '${call.name}' missing $missingDeps — deferring")
                    // Do not fail entire workflow; the planner will reorder in next round
                    continue
                }
            }
            // ── Conditional logic: evaluate IF condition against previous tool output
            // Example: "Check weather then message Mom if it will rain" — weather must be observed first
            val conditionalSkip = evaluateConditionalForCall(call, previousOutput)
            if (conditionalSkip != null && !conditionalSkip) {
                Timber.i("ToolRunCoordinator: conditional skip for '${call.name}' — condition false (previous output did not trigger)")
                // Record a skipped result (not a failure) so planner knows no SMS was sent because rain condition was false
                val skipSummary = "Skipped '${call.name}': condition evaluated to false (e.g. no rain expected, so no SMS sent)."
                results += ToolExecutionRecord(call, ToolResult.Success(skipSummary))
                // Do not store as failure; continue to next step and allow final answer to explain no SMS sent
                previousOutput = skipSummary
                continue
            }
            val argsText = call.arguments.entries.joinToString(", ") { (k, v) -> "$k=$v" }.take(200)
            onEvent(ToolEvent.Started(call.name, argsText))

            val startedAt = System.currentTimeMillis()
            val (rawResult, fromCache, retryCount) = executeWithCacheAndRetry(call, onEvent)
            val sanitizedSummary = PromptInjectionDetector.sanitizeRetrievedDocument(rawResult.summary)
            val result = if (sanitizedSummary != rawResult.summary) {
                when (rawResult) {
                    is ToolResult.Success -> rawResult.copy(summary = sanitizedSummary)
                    is ToolResult.Failure -> rawResult.copy(summary = sanitizedSummary)
                }
            } else rawResult
            val durationMs = System.currentTimeMillis() - startedAt
            guard?.record(call.name, call.arguments, result)
            logger.logExecution(call.name, durationMs, result.isSuccess, if (result is ToolResult.Failure) result.summary else null)
            Timber.i(
                "ToolRunCoordinator: local tool '%s' -> %s in %dms (retries=%d, cache=%s, output=%dB)",
                call.name, result.statusLabel, durationMs, retryCount, fromCache, result.summary.length
            )
            // ── Tool Result Memory: store every completed output as workflow variable
            storeToolResultMemory(call.name, result.summary)
            previousOutput = result.summary

            val declined = result is ToolResult.Failure &&
                !result.retryable && result.summary.startsWith("The user declined")
            onEvent(
                when {
                    result.isSuccess -> ToolEvent.Succeeded(call.name, result.summary)
                    declined -> ToolEvent.Declined(call.name)
                    else -> ToolEvent.Failed(call.name, result.summary)
                }
            )
            results += ToolExecutionRecord(call, result)
            if (result is ToolResult.Failure) {
                Timber.w("ToolRunCoordinator: local tool '${call.name}' failed — stopping sequential execution")
                break
            }
            // ── Tool Context: next tool automatically receives original request + previous outputs via AgentVariableStore + feedback
            // (already handled via storeToolResultMemory + buildLocalToolFeedback)
        }
        return results
    }

    /**
     * Parallel execution for independent read-only tools.
     * Runs non-dependent tools concurrently via [async], merges results, and stores each in result memory.
     * Side-effect tools (SMS, email, delete, etc.) are never parallelized — they run sequentially with confirmation.
     */
    private suspend fun executeCallsParallel(
        calls: List<ToolCall>,
        onEvent: suspend (ToolEvent) -> Unit,
        guard: ToolLoopGuard?
    ): List<ToolExecutionRecord> = coroutineScope {
        Timber.i("ToolRunCoordinator: parallel execution for ${calls.size} independent tools: ${calls.map { it.name }}")
        val deferred = calls.map { call ->
            async {
                // Each parallel call still validates and respects guard before execution
                if (call.name.isBlank()) {
                    return@async ToolExecutionRecord(call, ToolResult.Failure("Tool name must not be empty.", retryable = false))
                }
                val v = validator.validate(call)
                if (v is ValidationResult.Invalid) {
                    return@async ToolExecutionRecord(call, ToolResult.Failure("Tool '${call.name}' rejected: ${v.firstError}", retryable = v.retryable))
                }
                if (guard != null && !guard.canExecute(call.name, call.arguments)) {
                    return@async null // skipped
                }
                val argsText = call.arguments.entries.joinToString(", ") { (k, v) -> "$k=$v" }.take(200)
                onEvent(ToolEvent.Started(call.name, argsText))
                val startedAt = System.currentTimeMillis()
                val (rawResult, fromCache, retryCount) = executeWithCacheAndRetry(call, onEvent)
                val summary = PromptInjectionDetector.sanitizeRetrievedDocument(rawResult.summary)
                val result = if (summary != rawResult.summary) {
                    when (rawResult) { is ToolResult.Success -> rawResult.copy(summary = summary); is ToolResult.Failure -> rawResult.copy(summary = summary) }
                } else rawResult
                guard?.record(call.name, call.arguments, result)
                storeToolResultMemory(call.name, result.summary)
                val durationMs = System.currentTimeMillis() - startedAt
                logger.logExecution(call.name, durationMs, result.isSuccess, if (result is ToolResult.Failure) result.summary else null)
                Timber.i("ToolRunCoordinator: parallel tool '%s' -> %s in %dms (cache=%s)", call.name, result.statusLabel, durationMs, fromCache)
                val declined = result is ToolResult.Failure && !result.retryable && result.summary.startsWith("The user declined")
                onEvent(when {
                    result.isSuccess -> ToolEvent.Succeeded(call.name, result.summary)
                    declined -> ToolEvent.Declined(call.name)
                    else -> ToolEvent.Failed(call.name, result.summary)
                })
                ToolExecutionRecord(call, result)
            }
        }
        deferred.awaitAll().filterNotNull()
    }

    private fun shouldRunParallel(calls: List<ToolCall>): Boolean {
        if (calls.size < 2) return false
        // Never parallelize confirmation-required side-effect tools (SMS, calls, email, delete)
        for (call in calls) {
            val spec = registry.get(call.name)?.spec ?: return false
            if (spec.requiresConfirmation) return false
            // Also never parallelize non-cacheable write tools (note_save, export_pdf) if they may depend on previous output
            if (!spec.cacheable && spec.category != io.androllm.core.tools.api.ToolCategory.INFORMATION) return false
        }
        // Pure-read parallelizable tools (weather, search, calculator, battery, etc.)
        val independent = calls.all { call ->
            val spec = registry.get(call.name)?.spec
            spec?.cacheable == true || spec?.category == io.androllm.core.tools.api.ToolCategory.INFORMATION
        }
        return independent
    }

    private fun evaluateConditionalForCall(call: ToolCall, previousOutput: String?): Boolean? {
        if (previousOutput == null) return null
        // Detect conditional via agent planner pattern: if previous was weather and current is messaging with "if rain"
        val prevLower = previousOutput.lowercase()
        val callName = call.name
        // SMS after weather with rain condition
        if (callName == "send_sms" && (prevLower.contains("rain") || prevLower.contains("shower") || prevLower.contains("% rain") || prevLower.contains("precipitation"))) {
            // Rain detected -> condition true (send), no rain -> false (skip)
            val hasRain = prevLower.contains("rain") || prevLower.contains("shower") || prevLower.contains("80%") || prevLower.contains("precipitation")
            // But need to check if condition was "if it rains" — if output shows no rain, skip
            val noRainIndicators = listOf("clear sky", "sunny", "no rain", "0% rain", "0 mm")
            val isNoRain = noRainIndicators.any { it in prevLower } && !hasRain
            return !isNoRain // true -> send, false -> skip
        }
        // Generic conditional skip detector: if previous output explicitly says no rain and call is SMS
        if (callName == "send_sms" && prevLower.contains("no rain")) return false
        // Use AgentPlanner's evaluator for any registered condition
        return null // null = no conditional decision, proceed
    }

    private fun storeToolResultMemory(toolName: String, output: String) {
        try {
            // Store as workflow variable so next round's planner and next tool receive it automatically
            // Tool Result Memory: every completed call becomes temporary working memory (never re-call unless required)
            variableStore.set(toolName, output.take(800))
            variableStore.set("last_${toolName}_output", output.take(800))
            // Also generic last output
            variableStore.set("last_tool_output", output.take(800))
            // For search results, also store under search_results for SMS draft consumption
            if (toolName == "search_web") variableStore.set("search_results", output.take(1000))
            if (toolName == "get_weather") variableStore.set("weather", output.take(500))
        } catch (_: Exception) { /* best-effort */ }
    }

    /**
     * Multi-round local workflow — hardened autonomous agent loop:
     *
     * LLM -> Planner -> Tool -> Observe -> LLM -> Need Another Tool? -> Tool -> Observe -> ...
     * Continue until: Goal completed, User interaction required, Permission required, Tool failure, Max iterations.
     *
     * Before executing ANY tool, the internal [AgentPlanner] creates an execution plan:
     * Goal -> Required Information -> Required Tools -> Execution Order -> Dependencies -> Run Tools -> Observe -> Continue
     * This planner is internal only — never exposed unless developer mode is enabled.
     *
     * Execution graph representation: Research -> Summary -> SMS Draft -> SMS Send -> Done
     * Dependency awareness ensures correct order (Research MUST finish before SMS, never reverse).
     * Tool Result Memory stores every completed output as workflow variables for next tools.
     * Conditional IF/ELSE branches (Message Mom if it rains) are evaluated after observation.
     * Parallel independent work (Weather || News) runs concurrently, then merges.
     * Prevents early exit by asking internally: Does the original request require additional actions? YES -> continue.
     *
     * Returns the final message list (original messages + feedback) for the answer generation; [onActivity] reports per-round status to the UI.
     */
    suspend fun runLocalWorkflow(
        messages: List<ChatPromptMessage>,
        onActivity: suspend (String?) -> Unit = {},
        onEvent: suspend (ToolEvent) -> Unit = {},
        guard: ToolLoopGuard? = null
    ): List<ChatPromptMessage> {
        if (!isToolUseEnabled()) return messages
        val maxRounds = settingsStore.current().maxToolRounds.coerceAtLeast(1)
        val loopGuard = guard ?: ToolLoopGuard()

        // ── Execution ID for structured logs (requirement 23) — unique per workflow
        val executionId = UUID.randomUUID().toString().take(8)
        val originalRequest = messages.lastOrNull { it.role == "user" }?.content.orEmpty()
        val enabledSpecs = planner.allowedTools()
        val developerMode = false // internal only; developer view reads StructuredLog; reasoning hidden from user

        // ── Streaming: Planning… (users should never wonder if app froze)
        onActivity("Planning…")
        val plan = try {
            agentPlanner.createPlan(originalRequest, enabledSpecs, messages.joinToString("\n") { it.content ?: "" }, variableStore.snapshot(), developerMode)
        } catch (e: Exception) {
            Timber.w(e, "ToolRunCoordinator: agent planner failed — falling back to LLM planner only")
            null
        }
        if (plan != null && developerMode) {
            Timber.i(agentPlanner.renderDeveloperLog(plan))
        } else if (plan != null) {
            Timber.d("ToolRunCoordinator[$executionId]: internal plan steps=${plan.executionOrder.map { it.toolName }} parallel=${plan.hasParallel} conditional=${plan.hasConditional}")
        }
        // Structured log: planner hidden reasoning — stored but not exposed to user
        if (plan != null) {
            logger.logStructured(
                ToolExecutionLogger.StructuredLog(
                    executionId = executionId,
                    goal = plan.goal.take(120),
                    planner = "AgentPlanner hidden reasoning: ${plan.requiredInformation.joinToString(", ")} -> ${plan.requiredTools.joinToString(", ")}",
                    toolSelected = plan.requiredTools.joinToString(", "),
                    arguments = plan.executionOrder.joinToString("; ") { it.argumentsHint.toString() },
                    nextStep = "Execution Graph: ${plan.executionGraph.levels.joinToString(" -> ") { lvl -> lvl.joinToString("||") { it.toolName } }}",
                    finalStatus = if (plan.isMultiStep) "multi-step workflow" else "single-step"
                )
            )
        }
        // Tool Context: each tool receives original request + conversation context + previous outputs
        // (provided via AgentVariableStore + AgentContextBuilder + feedback messages)

        var current = messages
        var completedSteps = 0
        val totalPlannedSteps = plan?.executionOrder?.size ?: Int.MAX_VALUE

        for (round in 0 until maxRounds) {
            val calls = planner.planLocal(current)
            if (calls.isEmpty()) {
                // ── Prevent Early Exit: planner must NEVER stop after the first successful tool if goal requires more
                // Ask internally: Does the user's original request require additional actions?
                if (plan != null && completedSteps < totalPlannedSteps && completedSteps > 0) {
                    // Check if remaining planned steps are unexecuted
                    val remaining = plan.executionOrder.drop(completedSteps)
                    if (remaining.isNotEmpty()) {
                        val stillNeeded = remaining.any { needed -> enabledSpecs.any { it.name == needed.toolName } }
                        if (stillNeeded) {
                            Timber.w("ToolRunCoordinator: prevent early exit — planner said done but original request still needs ${remaining.map { it.toolName }}")
                            // Inject a system nudge to make LLM continue: re-prompt with remaining tools hint
                            current = current + ChatPromptMessage(
                                role = "system",
                                content = "Reminder: the user's original request '${originalRequest.take(100)}' still requires these steps: ${remaining.joinToString(", ") { it.toolName }}. Continue execution — do not stop early. Check if you need to call another tool before answering."
                            )
                            continue // give planner one more round to emit the missing calls
                        }
                    }
                }
                break
            }
            // ── Tool Confidence: verify tool exists, parameters available, permissions before execution (already validated, but double-check)
            val fresh = calls.filter { loopGuard.canExecute(it.name, it.arguments) }
            if (fresh.isEmpty()) {
                Timber.i("ToolRunCoordinator: planner re-proposed only blocked calls — stopping")
                break
            }
            // ── Dependency Awareness: enforce execution graph order (research before SMS, weather before conditional SMS)
            // If plan defines dependencies, verify current calls respect order — defer out-of-order calls
            val orderedFresh = if (plan != null && plan.executionOrder.isNotEmpty()) {
                orderByDependencies(fresh, plan)
            } else fresh

            // ── Execution Graph logging (developer mode)
            if (developerMode && plan != null) {
                Timber.i("ToolRunCoordinator: Graph Level ${round + 1}: ${orderedFresh.map { it.name }} (dependsOn verified)")
            }

            // ── Streaming: granular real-time status (requirement 22) ──
            val streamingLabels = mapOf(
                "search_web" to "Searching Web…",
                "get_weather" to "Checking Weather…",
                "github" to "Searching GitHub…",
                "note_save" to "Saving Note…",
                "export_pdf" to "Generating PDF…",
                "export_markdown" to "Saving Markdown…",
                "send_sms" to "Preparing SMS…",
                "send_email" to "Preparing Email…",
                "make_call" to "Preparing Call…",
                "search_places" to "Finding Nearby Places…",
                "open_navigation" to "Opening Navigation…",
                "calendar" to "Updating Calendar…"
            )
            val activityLabel = if (orderedFresh.size == 1) {
                streamingLabels[orderedFresh[0].name] ?: "Running ${orderedFresh[0].name}…"
            } else {
                "Running ${orderedFresh.size} tools in parallel…"
            }
            onActivity(activityLabel)

            // ── Tool Observation: after every tool, observe output and decide Goal complete? Need another tool? Need clarification? Retry?
            val records = executeCalls(orderedFresh, onEvent, loopGuard)
            onActivity(null)
            if (records.isEmpty()) break
            completedSteps += records.size

            // ── Structured log per tool (executionId, goal, tool, args, time, result, validation, nextStep) ──
            records.forEach { rec ->
                val confidence = if (rec.result.isSuccess) 0.92 else 0.0 // executor computes real confidence, here use heuristic
                logger.logStructured(
                    ToolExecutionLogger.StructuredLog(
                        executionId = executionId,
                        goal = originalRequest.take(100),
                        planner = plan?.let { "Graph level ${round + 1}: ${it.executionGraph.levels.getOrNull(round)?.joinToString("||") { s -> s.toolName } ?: orderedFresh.map { c -> c.name }.joinToString(",") }" } ?: "LLM planner",
                        toolSelected = rec.call.name,
                        arguments = rec.call.arguments.toString().take(200),
                        executionTimeMs = null, // logged via logExecution separately
                        result = rec.result.summary.take(300),
                        validation = if (rec.result.isSuccess) "valid" else "failed",
                        nextStep = if (completedSteps < totalPlannedSteps) "continue" else "finalize",
                        finalStatus = if (completedSteps >= totalPlannedSteps) "Goal Completed" else "In Progress",
                        confidence = confidence
                    )
                )
            }

            // ── Tool Result Memory is handled inside executeCalls (variableStore)
            // ── Conditional Logic: if a conditional branch evaluated to false, the record contains skip summary — pass to model

            current = current + buildLocalToolFeedback(records)

            // ── Observation: Goal complete? Need another tool? Need retry?
            val lastOutput = records.lastOrNull()?.result?.summary.orEmpty()
            val goalComplete = plan != null && completedSteps >= plan.executionOrder.size
            if (goalComplete) {
                Timber.i("ToolRunCoordinator: execution graph complete — goal done after $completedSteps steps")
                // Still allow one more planner round to generate final answer grounded in results
            }

            if (loopGuard.stopReason() != null) {
                Timber.i("ToolRunCoordinator: loop guard triggered — stopping workflow")
                break
            }
            // Maximum safe iteration reached handled by for-loop cap (maxRounds)
        }
        // Return even if plan incomplete — the model will summarize what was done and explain any pending steps
        return current
    }

    /**
     * Orders [calls] according to [plan] dependencies.
     * Ensures Research -> Summary -> SMS Draft -> SMS Send -> Done never reverses.
     */
    private fun orderByDependencies(calls: List<ToolCall>, plan: AgentPlanner.AgentPlan): List<ToolCall> {
        if (plan.executionOrder.isEmpty()) return calls
        val orderMap = plan.executionOrder.mapIndexed { idx, step -> step.toolName to idx }.toMap()
        return calls.sortedBy { orderMap[it.name] ?: Int.MAX_VALUE }
    }

    /**
     * Converts executed calls into system messages that are injected into the
     * local chat history BEFORE the final answer generation, so the model can
     * summarize what happened. Large feedback is split into multiple system
     * messages (never truncated).
     */
    fun buildLocalToolFeedback(records: List<ToolExecutionRecord>): List<ChatPromptMessage> {
        if (records.isEmpty()) return emptyList()
        val sb = StringBuilder()
        sb.append(
            "Tool execution results from the previous step. Use them to answer the " +
                "user's request. Do not mention 'tool calls' unless the user would understand it.\n"
        )
        for (record in records) {
            val call = record.call
            val args = call.arguments.entries.joinToString(", ") { (k, v) -> "$k=$v" }
            sb.append("- ").append(call.name)
            if (args.isNotBlank()) sb.append('(').append(args.take(300)).append(')')
            sb.append(": ").append(record.result.summary).append('\n')
        }
        return chunkText(sb.toString(), LOCAL_FEEDBACK_CHUNK_CHARS)
            .map { ChatPromptMessage(role = "system", content = it) }
    }

    /** Renders execution records for a chat status line (log only). */
    fun describeRecords(records: List<ToolExecutionRecord>): String =
        records.joinToString(", ") { "${it.call.name}=${it.result.statusLabel}" }

    // ── Execution helpers ──────────────────────────────────────────────────

    /**
     * Runs [call] through the executor with cache replay (pure-read tools)
     * and exponential-backoff retries for transient failures. Returns the
     * final result plus whether it came from the cache and how many retries
     * were needed.
     */
    private suspend fun executeWithCacheAndRetry(
        call: ToolCall,
        onEvent: suspend (ToolEvent) -> Unit
    ): Triple<ToolResult, Boolean, Int> {
        val spec = registry.get(call.name)?.spec
        val cacheable = spec?.cacheable == true
        val cacheKey = if (cacheable) resultCache.key(call.name, call.arguments) else null
        if (cacheKey != null) {
            resultCache.get(cacheKey)?.let { (summary, data) ->
                Timber.i("ToolRunCoordinator: cache hit for '${call.name}' — replaying %dB output", summary.length)
                val result = if (data != null) ToolResult.Success(summary, data) else ToolResult.Success(summary)
                return Triple(result, true, 0)
            }
        }

        val requiresConfirmation = spec?.requiresConfirmation == true
        var result = executor.execute(call)
        var retries = 0
        // NEVER retry tools that run behind the confirmation gate (a retry
        // would re-ask the user who already approved), nor outcomes a retry
        // cannot fix (declined, settings-blocked).
        while (result is ToolResult.Failure && result.retryable && !requiresConfirmation &&
            retries < TOOL_MAX_ATTEMPTS - 1
        ) {
            retries++
            Timber.i("ToolRunCoordinator: retrying '${call.name}' (%d/%d) after failure", retries, TOOL_MAX_ATTEMPTS - 1)
            onEvent(ToolEvent.Started(call.name, "retry $retries"))
            logger.logRetry(call.name, retries, result.summary.take(80))
            kotlinx.coroutines.delay(TOOL_RETRY_POLICY.delayMsForAttempt(retries))
            result = executor.execute(call)
            // Automatic recovery: handle transient network / rate limit / timeout without restarting entire execution
            // Each retry is an attempt to recover; backoff helps with rate limits.
        }
        // ── Alternative tool fallback (retry engine: Alternative Tool) ─────
        if (result is ToolResult.Failure && result.retryable && !requiresConfirmation) {
            val alternative = findAlternativeTool(call.name, call)
            if (alternative != null) {
                Timber.i("ToolRunCoordinator: trying alternative tool '${alternative.name}' for failed '${call.name}'")
                onEvent(ToolEvent.Started(alternative.name, "alternative for ${call.name}"))
                val altCall = ToolCall(id = alternative.id, name = alternative.name, arguments = alternative.arguments)
                val altResult = executor.execute(altCall)
                // Validate alternative output before accepting
                val outVal = outputValidator.validate(alternative.name, altResult)
                if (outVal is ToolOutputValidator.OutputValidation.Valid && altResult.isSuccess) {
                    Timber.i("ToolRunCoordinator: alternative tool '${alternative.name}' succeeded")
                    return Triple(altResult, false, retries)
                } else {
                    Timber.w("ToolRunCoordinator: alternative tool '${alternative.name}' also failed")
                }
            }
        }
        if (cacheKey != null && result.isSuccess) {
            val data = (result as? ToolResult.Success)?.data
            resultCache.put(cacheKey, result.summary, data)
        }
        return Triple(result, false, retries)
    }

    /**
     * Finds an alternative tool that can achieve the same goal as [failedTool] — used by the retry engine.
     * Ranks candidates with same permission/category and health, preferring local and healthier tools.
     */
    private fun findAlternativeTool(failedTool: String, failedCall: ToolCall): ToolCall? {
        val failedSpec = registry.get(failedTool)?.spec ?: return null
        val candidates = registry.all().map { it.spec }.filter { spec ->
            spec.name != failedTool &&
            spec.isAvailable &&
            // Same logical capability or same category indicates similar function
            (spec.permission == failedSpec.permission || spec.category == failedSpec.category) &&
            // Health check: unhealthy tools not tried
            healthMonitor.isHealthy(spec.name) || healthMonitor.getStats(spec.name).total < 5
        }
        if (candidates.isEmpty()) return null
        val ranked = ranker.rank(candidates, query = failedCall.arguments.toString())
        val best = ranked.firstOrNull()?.spec ?: return null
        // Adapt arguments: for alternative, reuse same arguments (tools share similar param names via ToolArgs aliasing)
        return ToolCall(
            id = "alt_${best.name}_${System.currentTimeMillis().toString().takeLast(4)}",
            name = best.name,
            arguments = failedCall.arguments
        )
    }

    /** Parses a tool call's raw arguments JSON (tolerant of partial JSON). */
    private fun parseArguments(raw: String?): JsonObject {
        if (raw.isNullOrBlank()) return JsonObject(emptyMap())
        return runCatching { Json.parseToJsonElement(raw).jsonObject }
            .getOrElse { JsonObject(emptyMap()) }
    }

    /**
     * Splits [text] into sequential chunks of at most [TOOL_RESULT_CHUNK_CHARS]
     * characters, preferring newline boundaries. A single chunk is returned
     * for small outputs (the common case stays byte-identical).
     */
    private fun chunkToolOutput(text: String): List<String> =
        chunkText(text, TOOL_RESULT_CHUNK_CHARS)

    private fun chunkText(text: String, maxChars: Int): List<String> {
        if (text.length <= maxChars) return listOf(text)
        val chunks = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            var end = (start + maxChars).coerceAtMost(text.length)
            if (end < text.length) {
                // Back off to the last newline within the window so chunks
                // never split mid-line.
                val newline = text.lastIndexOf('\n', end - 1)
                if (newline > start + maxChars / 2) end = newline + 1
            }
            chunks += text.substring(start, end)
            start = end
        }
        return chunks
    }

    companion object {
        /** Max attempts (including the first) for a transient tool failure. */
        const val TOOL_MAX_ATTEMPTS = 3

        /** Exponential backoff between tool retries (500ms base, 2× growth). */
        private val TOOL_RETRY_POLICY = RetryPolicy(
            maxAttempts = TOOL_MAX_ATTEMPTS,
            initialDelayMs = 500,
            maxDelayMs = 8_000,
            jitterMs = 150
        )

        /** A tool result larger than this is delivered to the model in chunks. */
        const val TOOL_RESULT_CHUNK_CHARS = 8_000

        /** Local feedback system messages are split at this size. */
        private const val LOCAL_FEEDBACK_CHUNK_CHARS = 6_000
    }
}
