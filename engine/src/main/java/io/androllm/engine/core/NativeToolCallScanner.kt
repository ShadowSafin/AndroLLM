package io.androllm.engine.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * One tool call the model emitted natively as `<|tool_call|>` markers in its
 * output. The engine never executes these — it extracts them and hands them
 * to the chat layer, which runs them through the gated tool executor (exactly
 * like a cloud provider's `tool_calls`).
 */
data class NativeToolCall(
    val name: String,
    /** Raw JSON arguments object (may be empty `{}`). */
    val argumentsJson: String
)

/**
 * Parses the native function-calling markers some LiteRT model repacks emit
 * instead of the JSON-object planner shape:
 *
 *   `<|tool_call>call: get_battery{}<tool_call|>`            (Gemma 4 style)
 *   `<|tool_call>call: get_battery{"location":"x"}<tool_call|>`
 *   `<|tool_call|>{"function_name":"f","arguments":{...}}<|tool_call_end|>`  (Gemma 3 style)
 *   `<|tool_call|>{"name":"f","arguments":{...}}<|tool_call_end|>`
 *
 * Tolerant by design: marker spelling (with/without `|`) and argument
 * separators vary between repacks, so the scanner accepts the common variants
 * and never throws — a malformed block is dropped, not fatal.
 */
object NativeToolCallScanner {

    private val json = Json { ignoreUnknownKeys = true }

    // Opening markers, longest first (regex alternation order matters).
    private val OPEN_MARKERS = listOf("<|tool_call_start|>", "<|tool_call|>", "<|tool_call>")
    // Closing markers.
    private val CLOSE_MARKERS = listOf("<|tool_call_end|>", "<tool_call|>", "<|tool_call|>", "<|tool_call>")

    /**
     * Extracts every complete native tool-call block from [raw] model output.
     */
    fun scan(raw: String): List<NativeToolCall> {
        if (raw.isBlank()) return emptyList()
        val calls = mutableListOf<NativeToolCall>()
        var index = 0
        while (index < raw.length) {
            val open = findMarker(raw, index, OPEN_MARKERS) ?: break
            val close = findMarker(raw, open.end, CLOSE_MARKERS)
                ?: break // unterminated block — nothing more can be extracted
            val body = raw.substring(open.end, close.start).trim()
            parseBlock(body)?.let { calls.add(it) }
            index = close.end
        }
        return calls
    }

    /** Removes all native tool-call markers from [raw] (for display/persist). */
    fun strip(raw: String): String {
        if (raw.isBlank()) return raw
        var result = raw
        // Remove the block content too, not just the markers — the assistant
        // message must never contain "call: get_battery{}".
        val markerOpen = "|tool_call"
        var cleaned = StringBuilder()
        var index = 0
        var inBlock = false
        while (index < result.length) {
            if (!inBlock) {
                val open = findMarker(result, index, OPEN_MARKERS)
                if (open != null) {
                    cleaned.append(result, index, open.start)
                    inBlock = true
                    index = open.end
                } else {
                    cleaned.append(result, index, result.length)
                    break
                }
            } else {
                val close = findMarker(result, index, CLOSE_MARKERS)
                if (close != null) {
                    inBlock = false
                    index = close.end
                } else {
                    // Unterminated block — drop the tail (never show half a call).
                    break
                }
            }
        }
        // Trim any dangling whitespace the markers left behind.
        return cleaned.toString().trim()
    }

    /** True when [raw] contains any native tool-call marker. */
    fun containsMarker(raw: String): Boolean =
        OPEN_MARKERS.any { raw.contains(it) } || CLOSE_MARKERS.any { raw.contains(it) }

    private fun parseBlock(body: String): NativeToolCall? {
        if (body.isBlank()) return null

        // JSON-object style: {"function_name":"x","arguments":{...}} or
        // {"name":"x","arguments":{...}}.
        val trimmed = body.trim()
        if (trimmed.startsWith("{")) {
            val obj = runCatching { json.parseToJsonElement(trimmed).jsonObject }.getOrNull()
                ?: return null
            val name = (obj["function_name"] ?: obj["name"])?.jsonPrimitive?.contentOrNull
            val args = obj["arguments"]
            val argsJson = when (args) {
                is JsonObject -> args.toString()
                else -> (args as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
                    ?: runCatching { args?.toString() }.getOrNull()
                    ?: "{}"
            }
            return if (name.isNullOrBlank()) null else NativeToolCall(name.trim(), argsJson)
        }

        // "call: name{json}" / "name{json}" / "name(json)" / "name" style.
        var body2 = trimmed.removePrefix("call:").trim()
        val openBrace = body2.indexOfFirst { it == '{' || it == '(' }
        if (openBrace < 0) {
            return NativeToolCall(body2.trim(), "{}")
        }
        val name = body2.substring(0, openBrace).trim()
        if (name.isBlank()) return null
        val closeBrace = if (body2[openBrace] == '{') body2.lastIndexOf('}') else body2.lastIndexOf(')')
        val argsJson = if (closeBrace > openBrace) {
            body2.substring(openBrace, closeBrace + 1).let { candidate ->
                // Validate it parses as JSON; otherwise return "{}" (the model
                // often leaves the object empty or truncated).
                if (candidate.startsWith("{") &&
                    runCatching { json.parseToJsonElement(candidate) }.isSuccess
                ) candidate else "{}"
            }
        } else {
            "{}"
        }
        return NativeToolCall(name, argsJson)
    }

    private class MarkerMatch(val start: Int, val end: Int)

    private fun findMarker(text: String, from: Int, markers: List<String>): MarkerMatch? {
        var best: MarkerMatch? = null
        for (marker in markers) {
            val idx = text.indexOf(marker, from)
            if (idx >= 0 && (best == null || idx < best.start)) {
                best = MarkerMatch(idx, idx + marker.length)
            }
        }
        return best
    }

}
