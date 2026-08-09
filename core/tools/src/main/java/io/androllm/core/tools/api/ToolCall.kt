package io.androllm.core.tools.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * A single tool invocation produced by the planner. [id] is a stable id used
 * to correlate the call with its result in the OpenAI-style protocol;
 * [arguments] is the parsed parameter object.
 */
data class ToolCall(
    val id: String,
    val name: String,
    val arguments: JsonObject
) {
    /** Compact one-line rendering used in prompts and logs. */
    fun render(): String = "$name(${arguments.keys.joinToString(",") { "$it=..." }})"
}

/**
 * Parses the JSON output of the LOCAL tool planner into [ToolCall]s.
 *
 * Tolerant on purpose (mirrors the memory extractor's parser): accepts the
 * canonical `{"calls":[...]}` envelope, a bare array, a single call object,
 * markdown fences, leading prose and truncated JSON. Never throws — an
 * unparseable plan simply yields no calls.
 */
object ToolCallParser {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun parse(raw: String): List<ToolCall> {
        if (raw.isBlank()) return emptyList()
        val cleaned = raw.trim().removePrefix("```json").removePrefix("```").trim()
        if (cleaned.isBlank()) return emptyList()

        val block = extractJsonBlock(cleaned) ?: cleaned

        val primary = runCatching {
            val element = json.parseToJsonElement(block)
            val array = when (element) {
                is JsonArray -> element
                is JsonObject -> {
                    val calls = element["calls"] as? JsonArray
                    if (calls != null) calls
                    // A bare call object ({"name":...,"arguments":...}).
                    else if (element.containsKey("name")) JsonArray(listOf(element))
                    else JsonArray(emptyList())
                }
                else -> JsonArray(emptyList())
            }
            array.mapIndexedNotNull { index, el ->
                (el as? JsonObject)?.let { toCall(it, index) }
            }
        }.getOrNull()

        if (primary != null && primary.isNotEmpty()) return primary

        // Fallback for truncated/invalid JSON: scan every brace-balanced
        // object that carries a "name" key and parse each as one call.
        return fallbackScan(block)
    }

    private fun toCall(obj: JsonObject, index: Int = 0): ToolCall? {
        val nameEl = obj["name"] ?: return null
        // Some small models emit {"name":{"name":"x"}}; unwrap one level.
        val name = (nameEl as? JsonPrimitive)?.contentOrNull
            ?: (nameEl as? JsonObject)?.get("name")?.let { (it as? JsonPrimitive)?.contentOrNull }
            ?: return null
        if (name.isBlank()) return null
        val args = (obj["arguments"] as? JsonObject)
            ?: (obj["arguments"] as? JsonPrimitive)?.contentOrNull?.let { rawArgs ->
                runCatching { json.parseToJsonElement(rawArgs).jsonObject }.getOrNull()
            }
            ?: JsonObject(emptyMap())
        // The fallback id appends the call index so two same-name calls in one
        // plan can never share an id — shared ids would make a later
        // confirmation overwrite an earlier one's deferred (first action
        // hanging until timeout).
        val id = (obj["id"] as? JsonPrimitive)?.contentOrNull
            ?: "call_${name.hashCode().toUInt().toString(16)}_$index"
        return ToolCall(id = id, name = name, arguments = args)
    }

    /**
     * Extracts the first balanced `{...}` or `[...]` block from the raw text.
     * When the JSON is truncated (never closes), returns the tail from the
     * opening brace so the fallback scan can still salvage it.
     */
    private fun extractJsonBlock(raw: String): String? {
        val text = raw.trim().removePrefix("```json").removePrefix("```")
        val start = text.indexOfFirst { it == '{' || it == '[' }
        if (start < 0) return null
        val open = text[start]
        val close = if (open == '{') '}' else ']'

        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until text.length) {
            val c = text[i]
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
                open -> depth++
                close -> {
                    depth--
                    if (depth == 0) return text.substring(start, i + 1)
                }
            }
        }
        // Unbalanced → truncated output: hand back the tail as-is.
        return text.substring(start)
    }

    /** Salvages individual call objects from truncated or prose-wrapped text. */
    private fun fallbackScan(block: String): List<ToolCall> {
        val out = mutableListOf<ToolCall>()
        var searchFrom = 0
        while (searchFrom < block.length) {
            val nameIdx = block.indexOf("\"name\"", searchFrom)
            if (nameIdx < 0) break
            val start = block.lastIndexOf('{', nameIdx)
            if (start < 0) break
            val end = matchBrace(block, start)
            if (end > start) {
                runCatching {
                    toCall(json.parseToJsonElement(block.substring(start, end + 1)).jsonObject, out.size)
                }.getOrNull()?.let { out += it }
            }
            searchFrom = nameIdx + "\"name\"".length
        }
        return out.distinctBy { it.name to it.arguments.toString() }
    }

    /** Returns the index of the matching closing brace, or -1 when unbalanced. */
    private fun matchBrace(text: String, start: Int): Int {
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until text.length) {
            val c = text[i]
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
                    if (depth == 0) return i
                }
            }
        }
        return -1
    }
}
