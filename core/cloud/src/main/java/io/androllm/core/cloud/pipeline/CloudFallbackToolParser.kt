package io.androllm.core.cloud.pipeline

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * One tool call recovered from a plain-text model response.
 * [argumentsJson] is always a valid JSON object string ("{}" when empty).
 */
data class FallbackToolCall(
    val name: String,
    val argumentsJson: String,
    /** The exact text span that was parsed away (for stripping). */
    val rawSpan: String
)

/**
 * Fallback tool-call extraction for providers/models that do NOT emit
 * native OpenAI-style `tool_calls` and instead write the call into the
 * answer text (common with self-hosted proxies, older models, and some
 * OpenAI-compatible routers).
 *
 * Recognized shapes (all case-insensitive):
 * - XML-ish tags: `<tool_call>{...}</tool_call>`, `<tool_call>`, `<function_call>`,
 *   `<invoke>`, including pipe-closed `<tool_call|>` variants
 * - Markdown-fenced JSON blocks carrying a `name`/`tool` key
 * - Bare JSON envelopes: `{"calls":[...]}`, `{"tool_calls":[...]}`,
 *   or a single `{"name": "...", "arguments": {...}}` object
 * - `arguments` as either a JSON object or an embedded JSON string
 *
 * The parser never throws — unparseable text simply yields no calls — and
 * [stripToolSyntax] removes every recognized span so raw tool JSON never
 * leaks into the chat UI.
 */
object CloudFallbackToolParser {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val NAME_KEYS = listOf("name", "tool", "function_name", "tool_name", "function")
    private val ARGS_KEYS = listOf("arguments", "args", "parameters", "input")

    // <tool_call>...</tool_call> / <tool_call> / <function_call> / <invoke> (dot-matches-all)
    private val xmlTagRegex = Regex(
        """<\s*(tool_call|function_call|tool_calls|function_calls|invoke|tool|function)\b[^>]*>(.*?)<\s*/\s*\1\s*>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )

    // Pipe-closed variant: <|tool_call|>... or <tool_call|>...
    private val pipeTagRegex = Regex(
        """<\|?\s*(tool_call|function_call)\s*\|?>(.*?)(?:<\s*/?\s*\|?\s*(tool_call|function_call)\s*\|?>|$)""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )

    // ```json ... ``` fenced blocks
    private val fenceRegex = Regex(
        """```(?:json|tool|tool_call)?\s*\n?(.*?)```""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )

    /** Cheap pre-check: could this text contain embedded tool syntax? */
    fun containsToolSyntax(text: String): Boolean {
        if (text.length < 8) return false
        val lower = text.lowercase()
        return lower.contains("tool_call") ||
            lower.contains("function_call") ||
            lower.contains("\"name\"") && lower.contains("\"arguments\"") ||
            lower.contains("\"tool\"") && lower.contains("\"args\"")
    }

    /**
     * Extracts every recoverable tool call from [text]. Returns calls in
     * order of appearance; duplicates (same name + arguments) are dropped.
     */
    fun parse(text: String): List<FallbackToolCall> {
        if (text.isBlank() || !containsToolSyntax(text)) return emptyList()
        val calls = mutableListOf<FallbackToolCall>()

        // 1) XML-ish tags first — they are the most explicit signal.
        for (match in xmlTagRegex.findAll(text)) {
            val inner = match.groupValues[2].trim()
            parseInner(inner)?.forEach { calls += it.copy(rawSpan = match.value) }
        }
        if (calls.isEmpty()) {
            for (match in pipeTagRegex.findAll(text)) {
                val inner = match.groupValues[2].trim()
                if (inner.isNotBlank()) {
                    parseInner(inner)?.forEach { calls += it.copy(rawSpan = match.value) }
                }
            }
        }

        // 2) Fenced JSON blocks.
        if (calls.isEmpty()) {
            for (match in fenceRegex.findAll(text)) {
                val inner = match.groupValues[1].trim()
                if (looksLikeToolJson(inner)) {
                    parseInner(inner)?.forEach { calls += it.copy(rawSpan = match.value) }
                }
            }
        }

        // 3) Bare JSON: scan balanced objects that carry a tool-name key.
        if (calls.isEmpty()) {
            calls += scanBareJson(text)
        }

        return calls.distinctBy { it.name to it.argumentsJson }
    }

    /**
     * Removes every recognized tool-call span from [text] so raw tool syntax
     * never reaches the chat UI. Returns the cleaned text (may be blank).
     */
    fun stripToolSyntax(text: String): String {
        if (text.isBlank() || !containsToolSyntax(text)) return text
        val parsed = parse(text)
        if (parsed.isEmpty()) return text
        var cleaned = text
        for (call in parsed) {
            if (call.rawSpan.isNotBlank()) {
                cleaned = cleaned.replace(call.rawSpan, "")
            }
        }
        // Sweep any leftover fenced blocks that parsed as tool envelopes.
        cleaned = fenceRegex.replace(cleaned) { match ->
            val inner = match.groupValues[1].trim()
            if (looksLikeToolJson(inner)) "" else match.value
        }
        // Collapse the whitespace scars left by removals.
        return cleaned
            .replace(Regex("""\n{3,}"""), "\n\n")
            .replace(Regex("[ \t]+\n"), "\n")
            .trim()
    }

    // ── Internals ─────────────────────────────────────────────────────────

    private fun looksLikeToolJson(text: String): Boolean {
        val trimmed = text.trim()
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) return false
        val lower = trimmed.lowercase()
        return NAME_KEYS.any { "\"$it\"" in lower }
    }

    /** Parses one inner payload (may be an envelope, object, or array). */
    private fun parseInner(inner: String): List<FallbackToolCall>? {
        if (inner.isBlank()) return null
        val candidate = inner.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val element = runCatching { json.parseToJsonElement(candidate) }.getOrNull() ?: return null
        val objects = when (element) {
            is JsonArray -> element.mapNotNull { runCatching { it.jsonObject }.getOrNull() }
            is JsonObject -> {
                // Envelope forms: {"calls":[...]}, {"tool_calls":[...]}, ...
                val array = (element["calls"] as? JsonArray)
                    ?: (element["tool_calls"] as? JsonArray)
                    ?: (element["toolCalls"] as? JsonArray)
                    ?: (element["functions"] as? JsonArray)
                if (array != null) array.mapNotNull { runCatching { it.jsonObject }.getOrNull() }
                else if (hasNameKey(element)) listOf(element)
                else emptyList()
            }
            else -> emptyList()
        }
        val calls = objects.mapIndexedNotNull { index, obj -> toCall(obj, index) }
        return calls.ifEmpty { null }
    }

    /** Scans free text for balanced JSON objects carrying a tool-name key. */
    private fun scanBareJson(text: String): List<FallbackToolCall> {
        val out = mutableListOf<FallbackToolCall>()
        var searchFrom = 0
        while (searchFrom < text.length) {
            var nextKeyIdx = -1
            var matchedKey: String? = null
            for (key in NAME_KEYS.map { "\"$it\"" }) {
                val idx = text.indexOf(key, searchFrom)
                if (idx >= 0 && (nextKeyIdx < 0 || idx < nextKeyIdx)) {
                    nextKeyIdx = idx
                    matchedKey = key
                }
            }
            if (nextKeyIdx < 0 || matchedKey == null) break
            val start = text.lastIndexOf('{', nextKeyIdx)
            if (start < 0) break
            val end = matchClosingBrace(text, start)
            if (end <= start) {
                searchFrom = nextKeyIdx + matchedKey.length
                continue
            }
            val span = text.substring(start, end + 1)
            parseInner(span)?.forEach { out += it.copy(rawSpan = span) }
            searchFrom = end + 1
        }
        return out
    }

    private fun hasNameKey(obj: JsonObject): Boolean = NAME_KEYS.any { obj.containsKey(it) }

    private fun toCall(obj: JsonObject, index: Int): FallbackToolCall? {
        // Resolve the tool name (direct key or one-level nested).
        var name: String? = null
        for (key in NAME_KEYS) {
            val el = obj[key] ?: continue
            name = (el as? JsonPrimitive)?.contentOrNull?.trim()
                ?: (el as? JsonObject)?.let { inner ->
                    (inner["name"] as? JsonPrimitive)?.contentOrNull?.trim()
                        ?: inner.entries.firstOrNull()?.value?.let {
                            (it as? JsonPrimitive)?.contentOrNull?.trim()
                        }
                }
            if (!name.isNullOrBlank()) break
        }
        if (name.isNullOrBlank()) return null
        val normalizedName = normalizeName(name)
        if (normalizedName.isBlank()) return null

        // Resolve arguments: object form or embedded-JSON-string form.
        var argsObject: JsonObject? = null
        for (key in ARGS_KEYS) {
            val el = obj[key] ?: continue
            argsObject = (el as? JsonObject)
                ?: (el as? JsonPrimitive)?.contentOrNull?.let { raw ->
                    runCatching { json.parseToJsonElement(raw.trim()).jsonObject }.getOrNull()
                }
            if (argsObject != null) break
        }
        // Nested {"function":{"name":...,"arguments":...}} provider shape.
        if (argsObject == null) {
            val fn = obj["function"] as? JsonObject
            if (fn != null) {
                argsObject = (fn["arguments"] as? JsonObject)
                    ?: (fn["arguments"] as? JsonPrimitive)?.contentOrNull?.let { raw ->
                        runCatching { json.parseToJsonElement(raw.trim()).jsonObject }.getOrNull()
                    }
            }
        }
        val argsJson = argsObject?.toString() ?: "{}"
        // Final safety: arguments must serialize as a JSON object.
        if (!argsJson.startsWith("{")) return null
        return FallbackToolCall(name = normalizedName, argumentsJson = argsJson, rawSpan = "")
    }

    private fun normalizeName(raw: String): String {
        var s = raw.trim().lowercase()
        s = s.removePrefix("functions.").removePrefix("tools.").removePrefix("call:").trim()
        s = s.replace(Regex("[^a-z0-9_]+"), "_").replace(Regex("_+"), "_").trim('_')
        return s
    }

    /** Index of the closing brace for the object opening at [start], or -1. */
    private fun matchClosingBrace(text: String, start: Int): Int {
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
                '{', '[' -> depth++
                '}', ']' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return -1
    }
}
