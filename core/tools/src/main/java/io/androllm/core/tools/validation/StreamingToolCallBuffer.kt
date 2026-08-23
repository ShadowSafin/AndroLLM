package io.androllm.core.tools.validation

import io.androllm.core.cloud.model.CloudStreamEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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
     *
     * Streaming safety (requirement 10): buffers partial tokens, never exposes
     * incomplete tool markup, never executes until full call is parsed and
     * validated, never renders raw partial JSON or tags in the UI.
     */
    fun accumulate(event: CloudStreamEvent.ToolCallDelta): BufferedResult? {
        val fragment = fragments.getOrPut(event.index) { Fragment() }
        event.id?.let { fragment.id = it }
        event.name?.let { fragment.name = it }
        // Append chunk — may be partial JSON or partial tag
        fragment.argumentsBuilder.append(event.arguments)

        val argsString = fragment.argumentsBuilder.toString()

        // Requirement 10: buffer partial tool markup — never expose incomplete tags
        if (containsPartialToolMarkup(argsString)) {
            Timber.d("StreamingToolCallBuffer: buffering partial tool markup for index ${event.index}")
            return null
        }

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

        return null // Always buffer until finish signal — streaming never emits tool calls early
    }

    /**
     * Called when streaming finishes (finish_reason received). Returns all
     * buffered, validated tool calls. Invalid ones are discarded with logging.
     * Never exposes partial or malformed tool-call markup to the UI.
     */
    fun flushOnFinish(): List<BufferedToolCall> {
        val result = mutableListOf<BufferedToolCall>()
        for ((index, fragment) in fragments) {
            val name = fragment.name
            val argsString = fragment.argumentsBuilder.toString()
            if (name.isNullOrBlank()) {
                Timber.w("StreamingToolCallBuffer: discarding fragment at index $index — missing tool name (streaming safety)")
                continue
            }
            // Streaming safety: discard any fragment that still contains partial markup
            if (containsPartialToolMarkup(argsString) || argsString.contains("<tool_call", ignoreCase = true) || argsString.contains("<function_call", ignoreCase = true)) {
                Timber.w("StreamingToolCallBuffer: discarding fragment at index $index — contains partial tool markup, not validated")
                continue
            }
            if (argsString.isBlank()) {
                // Empty args may be valid for tools with no required params
                result += BufferedToolCall(index, fragment.id, name, "{}")
                continue
            }
            // Validate JSON is complete — partial JSON must never be executed
            if (!isCompleteJsonObject(argsString)) {
                Timber.w("StreamingToolCallBuffer: discarding incomplete JSON for '$name' at index $index: $argsString")
                continue
            }
            // Validate syntax — malformed JSON never reaches execution
            try {
                json.parseToJsonElement(argsString)
            } catch (e: Exception) {
                Timber.w("StreamingToolCallBuffer: discarding malformed JSON for '$name' at index $index: ${e.message}")
                continue
            }
            // Optional strict validation against schema if validator present
            if (validator != null) {
                // Build a synthetic ToolCall for validation (args as JsonObject)
                val syntheticArgs = try { json.parseToJsonElement(argsString).let { it as? kotlinx.serialization.json.JsonObject } ?: kotlinx.serialization.json.JsonObject(emptyMap()) } catch (_: Exception) { kotlinx.serialization.json.JsonObject(emptyMap()) }
                val syntheticCall = io.androllm.core.tools.api.ToolCall(id = fragment.id ?: "call_${name}_$index", name = name, arguments = syntheticArgs)
                val validation = validator.validate(syntheticCall)
                if (validation is ValidationResult.Invalid) {
                    Timber.w("StreamingToolCallBuffer: discarding invalid tool call '$name' after validation: ${validation.firstError}")
                    continue
                }
            }
            result += BufferedToolCall(index, fragment.id, name, argsString)
        }
        fragments.clear()
        Timber.i("StreamingToolCallBuffer: flushed ${result.size} validated tool calls (streaming safety)")
        return result
    }

    /**
     * Clears all buffered fragments (e.g., on error or new turn).
     */
    fun clear() {
        fragments.clear()
        Timber.d("StreamingToolCallBuffer: cleared")
    }

    /**
     * Detects partial/incomplete tool markup that must never be streamed
     * (e.g. "<tool_call", "<|tool_call|", "<function_call" without closing ">").
     * Also catches raw partial JSON like "{\"query\":" without closing.
     */
    private fun containsPartialToolMarkup(text: String): Boolean {
        val lower = text.lowercase()
        // Partial XML-like opening tag without closing '>'
        val partialTag = Regex("""<\s*(tool_call|function_call|tool|function)[^>]*$""", RegexOption.IGNORE_CASE).containsMatchIn(text)
        if (partialTag) return true
        // Partial native marker "<|tool_call" without closing
        if (lower.contains("<|tool") && !lower.contains(">") && lower.length < 30) return true
        // Partial JSON: starts with "{" but unbalanced and contains tool keys without closure
        if (text.trim().startsWith("{") && !isCompleteJsonObject(text) && (lower.contains("\"name\"") || lower.contains("\"tool\""))) {
            // If we have an opening brace but no closing, and we see tool keys, it's partial
            // We already handle via isCompleteJsonObject, but also treat as partial markup
            return true
        }
        return false
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
