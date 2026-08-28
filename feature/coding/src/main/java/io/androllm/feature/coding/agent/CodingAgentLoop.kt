package io.androllm.feature.coding.agent

import io.androllm.core.cloud.model.CloudChatMessage
import io.androllm.core.cloud.model.CloudStreamEvent
import io.androllm.core.cloud.model.CloudToolCall
import io.androllm.core.cloud.model.CloudToolCallFunction
import io.androllm.core.cloud.pipeline.CloudFallbackToolParser
import io.androllm.core.tools.validation.StreamingToolCallBuffer
import io.androllm.feature.coding.tools.CodingToolContext
import io.androllm.feature.coding.tools.CodingToolExecutor
import io.androllm.feature.coding.tools.CodingToolRegistry
import io.androllm.feature.coding.tools.CodingToolResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** Raised when the agent loop cannot continue (cloud error, no workspace). */
class CodingAgentException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** UI hooks fired while the loop runs (all optional). */
class CodingAgentCallbacks(
    val onDelta: (String) -> Unit = {},
    /**
     * Fired the moment a tool call's NAME first appears in the stream — long
     * before its (potentially huge) arguments finish streaming. Lets the UI
     * show "Writing file…" / "Reading file…" immediately instead of looking
     * stuck while a large write_file payload streams in.
     */
    val onToolAnnounced: (name: String) -> Unit = {},
    val onToolStart: (name: String, argsJson: String) -> Unit = { _, _ -> },
    val onToolResult: (name: String, result: CodingToolResult) -> Unit = { _, _ -> },
    val onStatus: (String) -> Unit = {},
    val onMissingAddon: (addonId: String, command: String) -> Unit = { _, _ -> }
)

/**
 * Asked to install a missing runtime addon when a command needs it. Returns true
 * when the addon was installed (so the loop tells the model to retry), false when
 * the user declined or the install failed. Production wires this to the
 * marketplace install flow (with a user prompt); tests use a scripted handler.
 */
fun interface MissingAddonHandler {
    suspend fun installAddon(addonId: String, failedCommand: String): Boolean
}

/**
 * The coding agent's execution engine: a multi-round cloud tool-calling loop,
 * modeled on the chat pipeline but scoped to the workspace coding tools.
 *
 * Each round streams a completion from [CodingCloudClient] with the coding tools
 * advertised. When the model emits tool calls they are executed through
 * [CodingToolExecutor] and the RAW results are fed back as `role="tool"` messages;
 * when it answers without tools the loop ends. Special behaviors:
 *
 *  - **Missing addon → install → retry**: a tool failure carrying a
 *    [CodingToolResult.Failure.missingAddonId] triggers [MissingAddonHandler];
 *    on success the model is told to retry the command automatically.
 *  - **Continuations**: a `length` finish reason requests a continuation instead
 *    of accepting a truncated answer.
 *  - **Loop guard**: bounded rounds + total tool-call cap prevent runaway loops.
 *  - **Raw output**: tool results are fed back verbatim (capped only to protect
 *    the context window); the terminal panel separately shows the full output.
 */
class CodingAgentLoop(
    private val cloud: CodingCloudClient,
    private val toolRegistry: CodingToolRegistry,
    private val toolExecutor: CodingToolExecutor,
    private val contextProvider: () -> CodingToolContext,
    private val missingAddonHandler: MissingAddonHandler = MissingAddonHandler { _, _ -> false },
    private val maxRounds: Int = DEFAULT_MAX_ROUNDS,
    private val maxToolCalls: Int = DEFAULT_MAX_TOOL_CALLS,
    private val maxContinuations: Int = 3
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Runs the loop over [history] (system + prior turns + latest user message).
     * Appends assistant/tool messages to [history] as it goes and returns the
     * final assistant answer text.
     */
    suspend fun run(
        history: MutableList<CloudChatMessage>,
        sessionId: String?,
        callbacks: CodingAgentCallbacks = CodingAgentCallbacks()
    ): String {
        val tools = toolRegistry.toCloudTools()
        val maxTokens = runCatching { cloud.maxOutputTokens() }.getOrNull()
            ?.toInt()?.coerceIn(1, MAX_OUTPUT_TOKEN_CEILING)

        val context = runCatching { contextProvider() }.getOrElse {
            throw CodingAgentException("No active workspace — choose a workspace folder first.", it)
        }

        val answer = StringBuilder()
        var lastText = ""
        var toolCallsExecuted = 0
        var continuations = 0

        round@ for (round in 0 until maxRounds) {
            callbacks.onStatus(if (round == 0) "Thinking…" else "Working… (round ${round + 1})")
            val roundBuffer = StringBuilder()
            val toolBuffer = StreamingToolCallBuffer()
            val announcedToolIndices = mutableSetOf<Int>()
            var finishReason: String? = null

            try {
                cloud.stream(history, tools, sessionId, maxTokens).collect { event ->
                    when (event) {
                        is CloudStreamEvent.Delta -> {
                            roundBuffer.append(event.text)
                            callbacks.onDelta(event.text)
                        }
                        is CloudStreamEvent.ToolCallDelta -> {
                            // Announce the tool as soon as its name appears in the
                            // stream (arguments may still be streaming for a long
                            // time — e.g. write_file carries the whole file).
                            val announcedName = event.name
                            if (event.index !in announcedToolIndices && !announcedName.isNullOrBlank()) {
                                announcedToolIndices += event.index
                                callbacks.onToolAnnounced(announcedName)
                            }
                            toolBuffer.accumulate(event)
                        }
                        is CloudStreamEvent.Finish -> finishReason = event.reason
                        is CloudStreamEvent.Reasoning -> Unit
                        is CloudStreamEvent.Usage -> Unit
                        CloudStreamEvent.Done -> Unit
                    }
                }
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (t: Throwable) {
                throw CodingAgentException("Cloud request failed: ${t.message}", t)
            }

            var calls = toolBuffer.flushOnFinish()

            // Fallback: recover tool calls written into plain text by providers
            // that do not emit native tool_calls.
            if (calls.isEmpty()) {
                val text = roundBuffer.toString()
                val fallback = CloudFallbackToolParser.parse(text)
                if (fallback.isNotEmpty()) {
                    val cleaned = CloudFallbackToolParser.stripToolSyntax(text)
                    roundBuffer.setLength(0)
                    roundBuffer.append(cleaned)
                    calls = fallback.mapIndexed { i, c ->
                        StreamingToolCallBuffer.BufferedToolCall(i, null, c.name, c.argumentsJson)
                    }
                }
            }

            if (calls.isNotEmpty()) {
                val interim = roundBuffer.toString()
                if (interim.isNotBlank()) {
                    answer.append(interim)
                    lastText = interim
                }

                val cloudCalls = calls.mapIndexed { i, c ->
                    CloudToolCall(
                        index = c.index,
                        id = c.id ?: "call_${round}_$i",
                        type = "function",
                        function = CloudToolCallFunction(c.name, c.argumentsJson)
                    )
                }
                history += CloudChatMessage(
                    role = "assistant",
                    content = interim.takeIf { it.isNotBlank() },
                    toolCalls = cloudCalls
                )

                for (call in cloudCalls) {
                    if (toolCallsExecuted >= maxToolCalls) {
                        history += toolMessage(call.id, "Tool budget exhausted for this turn. Answer with what you have.")
                        break@round
                    }
                    toolCallsExecuted++
                    val name = call.function?.name.orEmpty()
                    val argsJson = call.function?.arguments ?: "{}"
                    callbacks.onToolStart(name, argsJson)
                    callbacks.onStatus("Running $name…")

                    val result = toolExecutor.execute(name, argsJson, context)
                    callbacks.onToolResult(name, result)

                    val feedback = handleResultFeedback(result, argsJson, callbacks)
                    history += toolMessage(call.id, cap(feedback))
                }
                continue@round
            }

            // No tool calls → final answer round.
            val finalText = roundBuffer.toString()
            answer.append(finalText)
            lastText = finalText
            val truncated = finishReason == "length" || finishReason == "max_tokens"
            if (truncated && finalText.isNotBlank() && continuations < maxContinuations) {
                continuations++
                history += CloudChatMessage(role = "assistant", content = finalText)
                history += CloudChatMessage(role = "user", content = "Continue exactly where you stopped.")
                continue@round
            }
            // Record the final answer in history exactly once (the caller must
            // NOT re-append it — interim tool-round texts were already recorded).
            if (finalText.isNotBlank()) {
                history += CloudChatMessage(role = "assistant", content = finalText)
            }
            break@round
        }

        return answer.toString().ifBlank { lastText }
    }

    /** Builds tool feedback, auto-installing a missing addon and requesting retry. */
    private suspend fun handleResultFeedback(
        result: CodingToolResult,
        argsJson: String,
        callbacks: CodingAgentCallbacks
    ): String {
        if (result is CodingToolResult.Failure && result.missingAddonId != null) {
            val command = extractCommand(argsJson)
            callbacks.onMissingAddon(result.missingAddonId, command)
            callbacks.onStatus("Installing ${result.missingAddonId}…")
            val installed = runCatching {
                missingAddonHandler.installAddon(result.missingAddonId, command)
            }.getOrDefault(false)
            return if (installed) {
                "The '${result.missingAddonId}' addon is now installed. Retry the command."
            } else {
                "The '${result.missingAddonId}' addon was not installed (declined or failed). " +
                    "Explain what is needed and ask the user how to proceed."
            }
        }
        return result.summary
    }

    private fun toolMessage(id: String?, content: String): CloudChatMessage =
        CloudChatMessage(role = "tool", toolCallId = id.orEmpty(), content = content)

    private fun extractCommand(argsJson: String): String = runCatching {
        val obj = json.parseToJsonElement(argsJson) as? JsonObject
        (obj?.get("command") as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
    }.getOrDefault("")

    private fun cap(text: String): String =
        if (text.length > MAX_TOOL_FEEDBACK_CHARS) {
            text.substring(0, MAX_TOOL_FEEDBACK_CHARS) +
                "\n…[output truncated for the model context — full output is in the terminal panel]"
        } else text

    companion object {
        const val DEFAULT_MAX_ROUNDS = 8
        const val DEFAULT_MAX_TOOL_CALLS = 24
        const val MAX_OUTPUT_TOKEN_CEILING = 16384
        const val MAX_TOOL_FEEDBACK_CHARS = 16_000
    }
}
