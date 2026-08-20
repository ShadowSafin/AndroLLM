package io.androllm.core.tools.coordinator

import io.androllm.core.tools.agent.AgentContextBuilder
import io.androllm.core.cloud.model.CloudChatMessage
import io.androllm.core.cloud.model.CloudTool
import io.androllm.core.cloud.model.CloudToolCall
import io.androllm.core.cloud.model.CloudToolCallFunction
import io.androllm.core.cloud.network.RetryPolicy
import io.androllm.core.tools.api.ToolCall
import io.androllm.core.tools.api.ToolEvent
import io.androllm.core.tools.api.ToolResult
import io.androllm.core.tools.executor.ToolExecutor
import io.androllm.core.tools.planner.ToolPlanner
import io.androllm.core.tools.registry.ToolRegistry
import io.androllm.core.tools.settings.AutomationSettingsStore
import io.androllm.core.tools.validation.PromptInjectionDetector
import io.androllm.core.tools.validation.ToolCallValidator
import io.androllm.core.tools.validation.ToolExecutionLogger
import io.androllm.core.tools.validation.ToolExecutionPipeline
import io.androllm.core.tools.validation.ValidationResult
import io.androllm.engine.models.ChatPromptMessage
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import timber.log.Timber

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
    private val pipeline: ToolExecutionPipeline = ToolExecutionPipeline(registry, ToolCallValidator(registry), ToolExecutionLogger())
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
     * Executes calls sequentially (each may await a user confirmation), in the
     * order the planner produced them. Transient failures retry with
     * exponential backoff (up to [TOOL_MAX_ATTEMPTS] attempts); a terminal
     * failure is reported after every retry is exhausted. Pure-read tools
     * reuse the result cache for identical calls.
     *
     * [guard] (per-turn loop protection) filters each call the same way the
     * cloud path does: identical re-calls, capped tools and disabled tools
     * are skipped without executing.
     */
    suspend fun executeCalls(
        calls: List<ToolCall>,
        onEvent: suspend (ToolEvent) -> Unit = {},
        guard: ToolLoopGuard? = null
    ): List<ToolExecutionRecord> {
        if (calls.isEmpty()) return emptyList()
        val results = mutableListOf<ToolExecutionRecord>()
        for (call in calls) {
            // ── Hardened pipeline: JSON already parsed, now registry + argument validation ──
            // Empty tool name -> reject immediately
            if (call.name.isBlank()) {
                logger.logValidation("<empty>", ValidationResult.Invalid("Empty tool name", retryable = false))
                Timber.w("ToolRunCoordinator: rejecting call with empty tool name")
                results += ToolExecutionRecord(call, ToolResult.Failure("Tool name must not be empty.", retryable = false))
                break // Stop execution immediately if a tool fails
            }
            // Registry + argument validation via strict validator
            val validation = validator.validate(call)
            logger.logValidation(call.name, validation)
            if (validation is ValidationResult.Invalid) {
                Timber.w("ToolRunCoordinator: rejecting invalid local call '${call.name}': ${validation.errors}")
                // Recovery: do not execute, remove invalid call, return clear error
                val failure = ToolResult.Failure(
                    "Tool '${call.name}' rejected: ${validation.firstError}",
                    retryable = validation.retryable
                )
                results += ToolExecutionRecord(call, failure)
                break // Stop sequential execution immediately if a tool fails
            }
            // Prompt injection protection on arguments
            if (PromptInjectionDetector.isInjectionAttempt(call.arguments.toString())) {
                val invalid = ValidationResult.Invalid("Prompt injection detected in arguments", retryable = false)
                logger.logValidation(call.name, invalid)
                Timber.w("ToolRunCoordinator: injection attempt in local call '${call.name}' — rejecting")
                results += ToolExecutionRecord(call, ToolResult.Failure("Tool '${call.name}' rejected: prompt injection detected.", retryable = false))
                break
            }
            // Loop guard — skip without executing, not a failure, continue to next
            if (guard != null && !guard.canExecute(call.name, call.arguments)) {
                Timber.i("ToolRunCoordinator: guard blocked '${call.name}' — skipping (total=${guard.totalCalls})")
                continue
            }
            val argsText = call.arguments.entries.joinToString(", ") { (k, v) -> "$k=$v" }.take(200)
            onEvent(ToolEvent.Started(call.name, argsText))
            val startedAt = System.currentTimeMillis()
            val (rawResult, fromCache, retryCount) = executeWithCacheAndRetry(call, onEvent)
            // Sanitize tool output: ignore hidden instructions inside retrieved documents
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
            // Multi-tool: stop execution immediately if a tool fails, pass validated outputs between tools
            if (result is ToolResult.Failure) {
                Timber.w("ToolRunCoordinator: local tool '${call.name}' failed — stopping sequential execution")
                break
            }
        }
        return results
    }

    /**
     * Multi-round local workflow (the engine behind multi-step requests):
     *
     * plan → execute (with retry + confirmations) → feed results back →
     * re-plan → … until the planner emits no calls or [AutomationSettingsStore.maxToolRounds]
     * rounds are used. Each round re-injects the agent context, so the model
     * sees the device facts plus variables written by earlier tools and can
     * branch on them (IF/ELSE), loop (WHILE/FOR-EACH via variable_set) and
     * chain any number of tools.
     *
     * Returns the final message list (original messages + feedback) for the
     * answer generation; [onActivity] reports per-round status to the UI.
     */
    suspend fun runLocalWorkflow(
        messages: List<ChatPromptMessage>,
        onActivity: suspend (String?) -> Unit = {},
        onEvent: suspend (ToolEvent) -> Unit = {},
        guard: ToolLoopGuard? = null
    ): List<ChatPromptMessage> {
        if (!isToolUseEnabled()) return messages
        val maxRounds = settingsStore.current().maxToolRounds.coerceAtLeast(1)
        // Per-turn loop guard (created here when the caller did not pass one,
        // e.g. the voice pipeline): total-call cap, consecutive-same-tool cap
        // and (name, arguments) dedupe — a confused model that re-plans the
        // SAME call every round (e.g. get_battery after its result was fed
        // back) can never burn the whole turn re-executing it.
        val loopGuard = guard ?: ToolLoopGuard()
        var current = messages
        for (round in 0 until maxRounds) {
            val calls = planner.planLocal(current)
            if (calls.isEmpty()) break
            val fresh = calls.filter { loopGuard.canExecute(it.name, it.arguments) }
            if (fresh.isEmpty()) {
                Timber.i("ToolRunCoordinator: planner re-proposed only blocked calls — stopping")
                break
            }
            onActivity(
                "Step ${round + 1}: running ${fresh.size} tool call${if (fresh.size == 1) "" else "s"}…"
            )
            val records = executeCalls(fresh, onEvent, loopGuard)
            onActivity(null)
            if (records.isEmpty()) break
            current = current + buildLocalToolFeedback(records)
            if (loopGuard.stopReason() != null) {
                Timber.i("ToolRunCoordinator: loop guard triggered — stopping workflow")
                break
            }
        }
        return current
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
            kotlinx.coroutines.delay(TOOL_RETRY_POLICY.delayMsForAttempt(retries))
            result = executor.execute(call)
        }
        if (cacheKey != null && result.isSuccess) {
            val data = (result as? ToolResult.Success)?.data
            resultCache.put(cacheKey, result.summary, data)
        }
        return Triple(result, false, retries)
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
