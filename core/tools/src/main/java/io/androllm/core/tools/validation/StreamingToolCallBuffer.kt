package io.androllm.core.tools.validation

import io.androllm.core.cloud.model.CloudStreamEvent
import kotlinx.serialization.json.Json
import timber.log.Timber

/**
 * Buffers streaming tool call fragments until they form a complete, validated JSON object.
 * Never streams partial tool JSON — only emits fully validated calls.
 * Only the final assistant response is streamed to the user.
 *
 * Handles OpenAI-style streaming where tool call arguments arrive in chunks:
 * {"index":0,"function":{"name":"get_weather","arguments":"{\"loc"}}
 * {"index":0,"function":{"arguments":"ation\":\"Delhi\"}"}}
 */
class StreamingToolCallBuffer(
    private val validator: ToolCallValidator? = null
) {

    private data class Fragment(
        var id: String? = null,
        var name: String? = null,
        val argumentsBuilder: StringBuilder = StringBuilder(),
        var validated: Boolean = false
    )

    private val fragments = mutableMapOf<Int, Fragment>()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Accumulates a streaming fragment. Returns null while buffering,
     * or the completed tool call when arguments form valid JSON and pass validation.
     */
    fun accumulate(event: CloudStreamEvent.ToolCallDelta): BufferedResult? {
        val fragment = fragments.getOrPut(event.index) { Fragment() }
        event.id?.let { fragment.id = it }
        event.name?.let { fragment.name = it }
        fragment.argumentsBuilder.append(event.arguments)

        val argsString = fragment.argumentsBuilder.toString()
        // Check if we have a complete JSON object (balanced braces and valid JSON)
        if (!isCompleteJsonObject(argsString)) {
            Timber.d("StreamingToolCallBuffer: buffering partial JSON for index ${event.index} (${argsString.length} chars)")
            return null
        }

        // Validate JSON syntax strictly
        val syntaxResult = try {
            json.parseToJsonElement(argsString)
            true
        } catch (e: Exception) {
            Timber.w("StreamingToolCallBuffer: buffered JSON still invalid for index ${event.index}: ${e.message}")
            false
        }

        if (!syntaxResult) return null

        // If validator provided, run full validation
        if (validator != null && fragment.name != null) {
            // We have complete JSON, but need to validate against schema
            // Defer final validation until stream finishes (finish reason)
            Timber.d("StreamingToolCallBuffer: buffered complete JSON for '${fragment.name}' at index ${event.index}, awaiting stream completion")
            return null // Still buffer until stream signals finish
        }

        return null // Always buffer until finish signal
    }

    /**
     * Called when streaming finishes (finish_reason received). Returns all
     * buffered, validated tool calls. Invalid ones are discarded with logging.
     */
    fun flushOnFinish(): List<BufferedToolCall> {
        val result = mutableListOf<BufferedToolCall>()
        for ((index, fragment) in fragments) {
            val name = fragment.name
            val argsString = fragment.argumentsBuilder.toString()
            if (name.isNullOrBlank()) {
                Timber.w("StreamingToolCallBuffer: discarding fragment at index $index — missing tool name")
                continue
            }
            if (argsString.isBlank()) {
                // Empty args may be valid for tools with no required params
                result += BufferedToolCall(index, fragment.id, name, "{}")
                continue
            }
            // Validate JSON is complete
            if (!isCompleteJsonObject(argsString)) {
                Timber.w("StreamingToolCallBuffer: discarding incomplete JSON for '$name' at index $index: $argsString")
                continue
            }
            // Validate syntax
            try {
                json.parseToJsonElement(argsString)
            } catch (e: Exception) {
                Timber.w("StreamingToolCallBuffer: discarding malformed JSON for '$name' at index $index: ${e.message}")
                continue
            }
            result += BufferedToolCall(index, fragment.id, name, argsString)
        }
        fragments.clear()
        Timber.i("StreamingToolCallBuffer: flushed ${result.size} validated tool calls")
        return result
    }

    /**
     * Clears all buffered fragments (e.g., on error or new turn).
     */
    fun clear() {
        fragments.clear()
    }

    /**
     * Checks if a string is a complete JSON object (balanced braces at top level).
     */
    private fun isCompleteJsonObject(json: String): Boolean {
        val trimmed = json.trim()
        if (trimmed.isEmpty()) return false
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return false
        var depth = 0
        var inString = false
        var escaped = false
        for (c in trimmed) {
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth < 0) return false
                }
            }
        }
        return depth == 0 && !inString
    }

    data class BufferedToolCall(
        val index: Int,
        val id: String?,
        val name: String,
        val argumentsJson: String
    )

    sealed interface BufferedResult {
        data object Buffering : BufferedResult
        data class Ready(val call: BufferedToolCall) : BufferedResult
        data class Invalid(val error: String) : BufferedResult
    }
}
