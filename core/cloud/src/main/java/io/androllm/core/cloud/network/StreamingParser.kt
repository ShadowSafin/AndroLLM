package io.androllm.core.cloud.network

import io.androllm.core.cloud.model.CloudChatChunk
import io.androllm.core.cloud.model.CloudStreamEvent
import io.androllm.core.cloud.model.CloudUsage
import kotlinx.serialization.json.Json

/**
 * Parses OpenAI-compatible Server-Sent Events (SSE) payloads produced by a
 * LiteLLM proxy stream:
 *
 * ```
 * data: {"choices":[{"delta":{"role":"assistant"}}]}
 * data: {"choices":[{"delta":{"content":"Hello"}}]}
 * data: {"choices":[{"delta":{"reasoning_content":"thinking..."}}]}
 * data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"name":"f","arguments":"{\"a\":"}}]}}]}
 * data: {"usage":{"prompt_tokens":9,"completion_tokens":2,"total_tokens":11}}
 * data: [DONE]
 * ```
 *
 * Pure JVM logic (no network stack dependency) so it is unit-testable in
 * isolation.
 */
object StreamingParser {

    /** Result of consuming one accumulated SSE data payload. */
    sealed interface Parsed {
        /** A content delta from `choices[0].delta.content`. */
        data class Content(val text: String) : Parsed

        /** A reasoning/thinking delta (DeepSeek-R1, Gemini thinking, ...). */
        data class Reasoning(val text: String) : Parsed

        /** A streaming tool-call fragment (id/name/arguments arrive in pieces). */
        data class ToolCall(
            val index: Int,
            val id: String?,
            val name: String?,
            val arguments: String
        ) : Parsed

        /** Token usage reported on the final chunk (or via `usage`). */
        data class UsageInfo(val usage: CloudUsage) : Parsed

        /** The `data: [DONE]` terminal marker. */
        data object Done : Parsed

        /** Payload carried no usable content (role-only deltas, keep-alives, ...). */
        data object Ignored : Parsed
    }

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Consumes raw SSE lines from [lineProvider] and invokes [onPayload] for
     * every accumulated `data:` payload. Handles multi-line `data:` fields,
     * comments, event/retry fields, and blank-line event boundaries. Returns
     * null when the provider signals end-of-stream.
     *
     * @return true while the stream should continue, false after `[DONE]`.
     */
    suspend fun consumeLines(
        lineProvider: suspend () -> String?,
        onPayload: suspend (String) -> Parsed
    ): Boolean {
        val dataLines = StringBuilder()
        var keepGoing = true
        while (keepGoing) {
            val line = lineProvider() ?: break
            when {
                line.startsWith(":") -> Unit // comment / keep-alive
                line.startsWith("data:") -> {
                    val payload = line.removePrefix("data:").removePrefix(" ")
                    if (dataLines.isNotEmpty()) dataLines.append('\n')
                    dataLines.append(payload)
                }
                line.isBlank() -> {
                    if (dataLines.isNotEmpty()) {
                        val parsed = onPayload(dataLines.toString())
                        dataLines.clear()
                        if (parsed == Parsed.Done) keepGoing = false
                    }
                }
                else -> Unit // event:, id:, retry: and other fields are ignored
            }
        }
        // Flush a trailing event without a closing blank line (robustness).
        if (keepGoing && dataLines.isNotEmpty()) {
            val parsed = onPayload(dataLines.toString())
            if (parsed == Parsed.Done) keepGoing = false
        }
        return keepGoing
    }

    /** Parses a single `data:` payload into a [Parsed] result. */
    fun parsePayload(payload: String): Parsed {
        val trimmed = payload.trim()
        if (trimmed.isEmpty()) return Parsed.Ignored
        if (trimmed == "[DONE]") return Parsed.Done
        val chunk = runCatching { json.decodeFromString(CloudChatChunk.serializer(), trimmed) }
            .getOrElse { return Parsed.Ignored } // partial/truncated JSON → skip
        chunk.usage?.let { return Parsed.UsageInfo(it) }
        val delta = chunk.choices.firstOrNull()?.delta ?: return Parsed.Ignored
        val content = delta.content
        if (!content.isNullOrEmpty()) return Parsed.Content(content)
        val reasoning = delta.reasoning_content
        if (!reasoning.isNullOrEmpty()) return Parsed.Reasoning(reasoning)
        val toolCall = delta.tool_calls?.firstOrNull()
        if (toolCall != null) {
            return Parsed.ToolCall(
                index = toolCall.index,
                id = toolCall.id,
                name = toolCall.function?.name,
                arguments = toolCall.function?.arguments.orEmpty()
            )
        }
        return Parsed.Ignored
    }

    /** Convenience: folds a payload into a [CloudStreamEvent] (null for ignored). */
    fun toStreamEvent(payload: String): CloudStreamEvent? = when (val parsed = parsePayload(payload)) {
        is Parsed.Content -> CloudStreamEvent.Delta(parsed.text)
        is Parsed.Reasoning -> CloudStreamEvent.Reasoning(parsed.text)
        is Parsed.ToolCall -> CloudStreamEvent.ToolCallDelta(
            index = parsed.index,
            id = parsed.id,
            name = parsed.name,
            arguments = parsed.arguments
        )
        is Parsed.UsageInfo -> CloudStreamEvent.Usage(
            promptTokens = parsed.usage.prompt_tokens,
            completionTokens = parsed.usage.completion_tokens,
            totalTokens = parsed.usage.total_tokens
        )
        Parsed.Done -> CloudStreamEvent.Done
        Parsed.Ignored -> null
    }
}
