package io.androllm.core.tools.coordinator

import io.androllm.core.tools.agent.AgentContextBuilder
import io.androllm.core.cloud.model.CloudChatMessage
import io.androllm.core.cloud.model.CloudTool
import io.androllm.core.cloud.model.CloudToolCall
import io.androllm.core.tools.api.ToolCall
import io.androllm.core.tools.api.ToolResult
import io.androllm.core.tools.executor.ToolExecutor
import io.androllm.core.tools.planner.ToolPlanner
import io.androllm.core.tools.registry.ToolRegistry
import io.androllm.core.tools.settings.AutomationSettingsStore
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
 */
@Singleton
class ToolRunCoordinator @Inject constructor(
    private val planner: ToolPlanner,
    private val executor: ToolExecutor,
    private val settingsStore: AutomationSettingsStore,
    private val agentContext: AgentContextBuilder,
    private val registry: ToolRegistry
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

    /** OpenAI-compatible `tools` array for the cloud path. */
    suspend fun cloudTools(): List<CloudTool> = planner.buildCloudTools()

    // ── Cloud (native function calling) ────────────────────────────────────

    /**
     * Executes the accumulated cloud tool calls and returns the messages to
     * append to the OpenAI history: first the assistant message carrying the
     * `tool_calls` (plus any text the model streamed alongside them via
     * [assistantContent]), then one `role="tool"` message per call.
     */
    suspend fun executeCloudToolCalls(
        calls: List<CloudToolCall>,
        assistantContent: String? = null
    ): List<CloudChatMessage> {
        if (calls.isEmpty()) return emptyList()
        val assistantMsg = CloudChatMessage(
            role = "assistant",
            content = assistantContent?.takeIf { it.isNotBlank() },
            toolCalls = calls
        )
        val results = calls.mapIndexedNotNull { index, call ->
            val name = call.function?.name ?: return@mapIndexedNotNull null
            if (name.isBlank()) return@mapIndexedNotNull null
            val args = parseArguments(call.function?.arguments)
            val toolCall = ToolCall(
                // Index suffix keeps same-name calls distinct when the provider
                // omits ids — shared ids would collide in the confirmation map.
                id = call.id ?: "call_${name.hashCode().toUInt().toString(16)}_$index",
                name = name,
                arguments = args
            )
            val result = executor.execute(toolCall)
            Timber.i("ToolRunCoordinator: cloud tool '$name' -> ${result.statusLabel}")
            CloudChatMessage(
                role = "tool",
                content = result.summary,
                toolCallId = call.id ?: toolCall.id
            )
        }
        if (results.isEmpty()) return emptyList()
        return listOf(assistantMsg) + results
    }

    // ── Local GGUF (prompt-based planning) ─────────────────────────────────

    /** Runs the local planner; empty when no tools are needed. */
    suspend fun planLocal(messages: List<ChatPromptMessage>): List<ToolCall> =
        planner.planLocal(messages)

    /**
     * Executes calls sequentially (each may await a user confirmation), in the
     * order the planner produced them. A transient failure is retried ONCE
     * with the same arguments before it is reported (never for outcomes a
     * retry cannot fix: user-declined, settings-blocked).
     */
    suspend fun executeCalls(calls: List<ToolCall>): List<ToolExecutionRecord> =
        calls.map { call ->
            var result = executor.execute(call)
            // Retry once for transient failures — but NEVER for tools that run
            // behind the confirmation gate (a retry would re-ask the user who
            // already approved), nor for outcomes a retry cannot fix.
            val requiresConfirmation = registry.get(call.name)?.spec?.requiresConfirmation == true
            if (result is ToolResult.Failure && result.retryable && !requiresConfirmation) {
                Timber.i("ToolRunCoordinator: retrying '${call.name}' once after failure")
                val retried = executor.execute(call)
                if (retried.isSuccess) result = retried
            }
            Timber.i("ToolRunCoordinator: local tool '${call.name}' -> ${result.statusLabel}")
            ToolExecutionRecord(call, result)
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
        onActivity: suspend (String?) -> Unit = {}
    ): List<ChatPromptMessage> {
        if (!isToolUseEnabled()) return messages
        val maxRounds = settingsStore.current().maxToolRounds.coerceAtLeast(1)
        var current = messages
        for (round in 0 until maxRounds) {
            val calls = planner.planLocal(current)
            if (calls.isEmpty()) break
            onActivity(
                "Step ${round + 1}: running ${calls.size} tool call${if (calls.size == 1) "" else "s"}…"
            )
            val records = executeCalls(calls)
            onActivity(null)
            if (records.isEmpty()) break
            current = current + buildLocalToolFeedback(records)
        }
        return current
    }

    /**
     * Converts executed calls into a system message that is injected into the
     * local chat history BEFORE the final answer generation, so the model can
     * summarize what happened.
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
            sb.append(": ").append(record.result.summary.take(600)).append('\n')
        }
        return listOf(ChatPromptMessage(role = "system", content = sb.toString()))
    }

    /** Renders execution records for a chat status line (log only). */
    fun describeRecords(records: List<ToolExecutionRecord>): String =
        records.joinToString(", ") { "${it.call.name}=${it.result.statusLabel}" }

    private fun parseArguments(raw: String?): JsonObject {
        if (raw.isNullOrBlank()) return JsonObject(emptyMap())
        return runCatching { Json.parseToJsonElement(raw).jsonObject }
            .getOrElse { JsonObject(emptyMap()) }
    }
}
