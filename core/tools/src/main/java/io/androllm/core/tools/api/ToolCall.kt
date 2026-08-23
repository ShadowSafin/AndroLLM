package io.androllm.core.tools.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

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
 *
 * Hardened to normalize ALL tool-call output formats into one internal
 * representation:
 * - native structured tool calls
 * - JSON tool call objects with alternative keys (tool/args, function_name, etc.)
 * - XML-like tool tags (<tool_call>...</tool_call>, <function_call>, provider tags)
 * - provider-specific formats (tool + args, function + parameters)
 * - partial / malformed JSON via brace scanning
 * - plain-text intent phrases via heuristic fallback
 */
object ToolCallParser {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    // XML tag extraction — case-insensitive, dot-matches-all, handles provider variations
    private val xmlToolTagRegex = Regex(
        """<\s*(tool_call|function_call|tool_calls|function_calls|tool|function|invoke)\b[^>]*>(.*?)</\s*\1\s*>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val xmlPipeCloseRegex = Regex(
        """<\s*(tool_call|function_call)\b[^>]*>(.*?)<\s*/?\s*\1\s*\|>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )

    fun parse(raw: String): List<ToolCall> {
        if (raw.isBlank()) return emptyList()
        val cleaned = raw.trim().removePrefix("```json").removePrefix("```").trim()
        if (cleaned.isBlank()) return emptyList()

        // 1) XML-like tags: <tool_call>...</tool_call>, <function_call> etc.
        // Normalize by extracting inner JSON and feeding through normal parser.
        val xmlExtracted = extractXmlBlocks(cleaned)
        if (xmlExtracted.isNotEmpty()) {
            val xmlCalls = xmlExtracted.mapNotNull { inner ->
                parseJsonObjectLenient(inner)
            }.mapIndexedNotNull { idx, obj -> toCall(obj, idx) }
            if (xmlCalls.isNotEmpty()) return xmlCalls.distinctBy { it.name to it.arguments.toString() }
            // If XML inner was not valid JSON, try scanning each block for brace-balanced objects
            val scannedFromXml = xmlExtracted.flatMap { fallbackScan(it) }
            if (scannedFromXml.isNotEmpty()) return scannedFromXml
        }

        val block = extractJsonBlock(cleaned) ?: cleaned

        val primary = runCatching {
            val element = json.parseToJsonElement(block)
            val array = when (element) {
                is JsonArray -> element
                is JsonObject -> {
                    // Canonical and provider-specific envelopes
                    val calls = (element["calls"] as? JsonArray)
                        ?: (element["tool_calls"] as? JsonArray)
                        ?: (element["toolCalls"] as? JsonArray)
                        ?: (element["functions"] as? JsonArray)
                        ?: (element["function_calls"] as? JsonArray)
                    if (calls != null) calls
                    // A bare call object ({"name":...,"arguments":...} or {"tool":...,"args":...}).
                    else if (hasToolNameKey(element)) JsonArray(listOf(element))
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
        // object that carries a "name"/"tool" key and parse each as one call.
        val scanned = fallbackScan(block)
        if (scanned.isNotEmpty()) return scanned

        // 2) Plain-text fallback: model emitted natural language without structured syntax
        // Detect intent heuristically and synthesize normalized ToolCalls
        val fallbackPlain = parsePlainTextFallback(cleaned)
        if (fallbackPlain.isNotEmpty()) return fallbackPlain

        return emptyList()
    }

    private fun hasToolNameKey(obj: JsonObject): Boolean =
        obj.containsKey("name") || obj.containsKey("tool") || obj.containsKey("function_name") || obj.containsKey("function") || obj.containsKey("tool_name")

    private fun extractXmlBlocks(raw: String): List<String> {
        val results = mutableListOf<String>()
        // Standard XML close </tool_call>
        xmlToolTagRegex.findAll(raw).forEach { match ->
            val inner = match.groupValues[2].trim()
            if (inner.isNotBlank()) results += inner
        }
        // Pipe close <tool_call|> (Gemma4 style)
        xmlPipeCloseRegex.findAll(raw).forEach { match ->
            val inner = match.groupValues[2].trim()
            if (inner.isNotBlank()) results += inner
        }
        // Also handle generic provider tags that may be malformed (e.g. <tool_call>{"name":...}</tool_call> with extra spaces)
        // The regex above already covers most; we also look for any <tool_call ...>... without close but with JSON inside
        // via extractJsonBlock fallback, so no need for extra handling here.
        return results
    }

    private fun parseJsonObjectLenient(text: String): JsonObject? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        return runCatching {
            when (val el = json.parseToJsonElement(trimmed)) {
                is JsonObject -> el
                else -> null
            }
        }.getOrNull() ?: runCatching {
            // Try extracting JSON block from within the XML inner text
            val block = extractJsonBlock(trimmed) ?: return@runCatching null
            json.parseToJsonElement(block).jsonObject
        }.getOrNull()
    }

    private fun toCall(obj: JsonObject, index: Int = 0): ToolCall? {
        // Support alternative name keys: name, tool, function_name, function, tool_name, id-name variants
        val nameEl = obj["name"] ?: obj["tool"] ?: obj["function_name"] ?: obj["function"] ?: obj["tool_name"] ?: return null
        // Some small models emit {"name":{"name":"x"}}; unwrap one level.
        // Also handle {"tool":{"name":"x"}} etc.
        val name = (nameEl as? JsonPrimitive)?.contentOrNull?.trim()
            ?: (nameEl as? JsonObject)?.get("name")?.let { (it as? JsonPrimitive)?.contentOrNull?.trim() }
            ?: (nameEl as? JsonObject)?.get("tool")?.let { (it as? JsonPrimitive)?.contentOrNull?.trim() }
            ?: (nameEl as? JsonObject)?.let { inner ->
                // If name is an object like {"tool":"x"}, try to extract string
                inner.entries.firstOrNull()?.value?.let { (it as? JsonPrimitive)?.contentOrNull }
            }
            ?: return null
        if (name.isBlank()) return null
        // Normalize name: provider may emit "functions.calculator" or "Tool_calculator" -> snake_case
        val normalizedName = normalizeToolName(name)
        if (normalizedName.isBlank()) return null

        // Support alternative argument keys: arguments, args, parameters, input, kwargs, data
        val argsRaw: JsonObject? = (obj["arguments"] as? JsonObject)
            ?: (obj["args"] as? JsonObject)
            ?: (obj["parameters"] as? JsonObject)
            ?: (obj["input"] as? JsonObject)
            ?: (obj["kwargs"] as? JsonObject)
            ?: (obj["data"] as? JsonObject)
            ?: (obj["arguments"] as? JsonPrimitive)?.contentOrNull?.let { rawArgs ->
                runCatching { json.parseToJsonElement(rawArgs.trim()).jsonObject }.getOrNull()
            }
            ?: (obj["args"] as? JsonPrimitive)?.contentOrNull?.let { rawArgs ->
                runCatching { json.parseToJsonElement(rawArgs.trim()).jsonObject }.getOrNull()
            }
            ?: (obj["parameters"] as? JsonPrimitive)?.contentOrNull?.let { rawArgs ->
                runCatching { json.parseToJsonElement(rawArgs.trim()).jsonObject }.getOrNull()
            }

        // Also handle provider-specific nested function object: {"function":{"name":"...","arguments":"{...}"}}
        val args = argsRaw ?: run {
            val fnObj = obj["function"] as? JsonObject
            if (fnObj != null && (obj.containsKey("function"))) {
                // Avoid treating the function object itself as name extraction duplicate;
                // try to extract arguments from nested function object
                (fnObj["arguments"] as? JsonObject)
                    ?: (fnObj["args"] as? JsonObject)
                    ?: (fnObj["arguments"] as? JsonPrimitive)?.contentOrNull?.let { ra ->
                        runCatching { json.parseToJsonElement(ra.trim()).jsonObject }.getOrNull()
                    }
            } else null
        } ?: JsonObject(emptyMap())

        // The fallback id appends the call index so two same-name calls in one
        // plan can never share an id — shared ids would make a later
        // confirmation overwrite an earlier one's deferred (first action
        // hanging until timeout).
        val id = (obj["id"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
            ?: "call_${normalizedName.hashCode().toUInt().toString(16)}_$index"
        return ToolCall(id = id, name = normalizedName, arguments = args)
    }

    private fun normalizeToolName(raw: String): String {
        var s = raw.trim().lowercase()
        // Remove provider prefixes like "functions.", "tools.", "call:"
        s = s.removePrefix("functions.").removePrefix("tools.").removePrefix("call:").trim()
        // Replace hyphens and spaces with underscores, keep alphanumeric and underscore
        s = s.replace(Regex("[^a-z0-9_]+"), "_").replace(Regex("_+"), "_").trim('_')
        // Alias normalization: map common model/provider synonyms to canonical registry names
        return when (s) {
            "calculator" -> "calculate"
            "calc", "math", "maths" -> "calculate"
            "web_search" -> "search_web"
            "websearch", "search" -> "search_web"
            "device_info" -> "get_device_info"
            "deviceinfo" -> "get_device_info"
            "location" -> "get_location"
            "current_location", "my_location" -> "get_location"
            "translation", "translate" -> "open_translation"
            "notifications", "notification" -> "read_notifications"
            "contacts", "contact" -> "find_contacts"
            "phone_call", "call", "phone", "dial", "make_call" -> "make_call"
            "sms", "text_message", "send_text", "send_sms" -> "send_sms"
            "voice_recorder", "voice_record", "recorder", "record" -> "record_voice"
            "weather" -> "get_weather"
            "battery" -> "get_battery"
            "calendar", "calender" -> "calendar"
            "github" -> "github"
            "variable_get" -> "variable_get"
            "variable_set" -> "variable_set"
            else -> s
        }
    }

    // ── Plain-text fallback heuristic (requirement 9) ────────────────────────
    private fun parsePlainTextFallback(raw: String): List<ToolCall> {
        val lower = raw.lowercase()
        val trimmed = raw.trim()
        if (trimmed.length < 4) return emptyList()

        // Heuristic 1: Calculator — "Use the calculator to evaluate 2+2." or "calculate 2+2"
        if (lower.contains("calculat")) {
            extractMathExpression(raw)?.let { expr ->
                if (expr.isNotBlank()) {
                    return listOf(
                        ToolCall(
                            id = "call_calculate_fallback_0",
                            name = "calculate",
                            arguments = buildJsonObject { put("expression", expr) }
                        )
                    )
                }
            }
        }
        // Generic math expression detection even without explicit "calculator" keyword
        // e.g. "evaluate 2+2", "what is 2+2", "solve 2+2"
        if (Regex("""\b(evaluate|solve|compute|calculate|what\s+is)\b.*[0-9]""", RegexOption.IGNORE_CASE).containsMatchIn(raw)) {
            extractMathExpression(raw)?.let { expr ->
                if (expr.isNotBlank() && expr.length >= 2) {
                    return listOf(
                        ToolCall(
                            id = "call_calculate_fallback_0",
                            name = "calculate",
                            arguments = buildJsonObject { put("expression", expr) }
                        )
                    )
                }
            }
        }

        // Heuristic 2: Web search — "search for android ai", "look up android"
        if (lower.contains("search") || lower.contains("look up") || lower.contains("google")) {
            val query = extractSearchQuery(raw)
            if (!query.isNullOrBlank() && query.length >= 2) {
                return listOf(
                    ToolCall(
                        id = "call_search_web_fallback_0",
                        name = "search_web",
                        arguments = buildJsonObject { put("query", query) }
                    )
                )
            }
        }

        // Heuristic 3: Weather — "what's the weather in Delhi"
        if (lower.contains("weather") || lower.contains("forecast")) {
            val loc = extractWeatherLocation(raw)
            if (!loc.isNullOrBlank()) {
                return listOf(
                    ToolCall(
                        id = "call_get_weather_fallback_0",
                        name = "get_weather",
                        arguments = buildJsonObject { put("location", loc) }
                    )
                )
            } else {
                return listOf(
                    ToolCall(
                        id = "call_get_weather_fallback_0",
                        name = "get_weather",
                        arguments = buildJsonObject { put("location", "Current") }
                    )
                )
            }
        }

        // Heuristic 4: Explicit tool name mention — generic fallback for any known tool alias
        // If the plain text directly names a tool, synthesize a minimal call so the pipeline can validate/ask for args
        val knownTools = listOf(
            "calculate", "search_web", "get_weather", "get_device_info", "get_location",
            "open_translation", "read_notifications", "github", "variable_get", "variable_set",
            "send_sms", "make_call", "calendar", "find_contacts", "record_voice"
        )
        for (tool in knownTools) {
            val alias = tool.replace("_", " ")
            if (lower.contains(tool) || lower.contains(alias) || lower.contains(tool.replace("get_", ""))) {
                // For tools that require no args, emit empty args to allow validation to pass
                // For tools requiring args, emit minimal heuristic (will trigger missing-arg recovery)
                return listOf(
                    ToolCall(
                        id = "call_${tool}_fallback_0",
                        name = tool,
                        arguments = buildJsonObject { }
                    )
                )
            }
        }

        return emptyList()
    }

    private fun extractMathExpression(text: String): String? {
        // Try to find expression after keywords like "evaluate", "calculate", "solve", "is", "to"
        val afterKeyword = Regex("""(?:evaluate|calculate|computed?|solve|what\s+is|is)\s*[:\-]?\s*([0-9][0-9\s\.\+\-\*\/\(\)\^%xX×÷]*[0-9\)])""", RegexOption.IGNORE_CASE).find(text)
        if (afterKeyword != null) {
            var expr = afterKeyword.groupValues[1].trim()
            expr = expr.replace(Regex("""[xX×]"""), "*").replace("÷", "/")
            expr = expr.trim().trimEnd('.', ',', '!', '?')
            if (expr.isNotBlank() && expr.any { it.isDigit() }) return expr
        }
        // Fallback: find any math-like substring with digits and operators
        val mathPattern = Regex("""(\d[\d\s\.\+\-\*\/\(\)\^%]*\d)""")
        val match = mathPattern.find(text)
        if (match != null) {
            var expr = match.groupValues[1].trim()
            expr = expr.replace(Regex("""[xX×]"""), "*").replace("÷", "/")
            expr = expr.replace(Regex("""\s+"""), "")
            if (expr.length >= 1 && expr.any { it.isDigit() }) return expr
        }
        return null
    }

    private fun extractSearchQuery(text: String): String? {
        // Patterns like "search for X", "search X", "look up X", "google X"
        val patterns = listOf(
            Regex("""search\s+for\s+(.+?)(?:\.|$|and|then)""", RegexOption.IGNORE_CASE),
            Regex("""look\s+up\s+(.+?)(?:\.|$|and|then)""", RegexOption.IGNORE_CASE),
            Regex("""google\s+(.+?)(?:\.|$|and|then)""", RegexOption.IGNORE_CASE),
            Regex("""search\s+(.+?)(?:\.|$|and|then)""", RegexOption.IGNORE_CASE)
        )
        for (pat in patterns) {
            val m = pat.find(text)
            if (m != null) {
                var q = m.groupValues[1].trim().trimEnd('.', ',', '!', '?', '"', '\'').trim()
                q = q.replace(Regex("""^["']|["']$"""), "").trim()
                if (q.length >= 2) return q
            }
        }
        // If no pattern, return last 3-4 words as query heuristic
        val words = text.trim().split(Regex("""\s+""")).filter { it.length >= 2 }
        if (words.size >= 2) return words.takeLast(4).joinToString(" ").trim()
        return null
    }

    private fun extractWeatherLocation(text: String): String? {
        val pat = Regex("""weather\s+(?:in|for|at)\s+([A-Za-z][A-Za-z\s\-]+)""", RegexOption.IGNORE_CASE).find(text)
        if (pat != null) {
            var loc = pat.groupValues[1].trim().trimEnd('.', ',', '!', '?')
            loc = loc.split(Regex("""\b(and|then|today|tomorrow)\b""", RegexOption.IGNORE_CASE))[0].trim()
            if (loc.length in 2..40) return loc
        }
        // Try "in Delhi" generic
        val inPat = Regex("""\bin\s+([A-Z][A-Za-z\s]{2,20})(?:\b|$)""").find(text)
        if (inPat != null) {
            val loc = inPat.groupValues[1].trim().trimEnd('.', ',', '!', '?')
            if (loc.length in 2..40) return loc
        }
        return null
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

    /** Salvages individual call objects from truncated or prose-wrapped text, including truncated JSON. */
    private fun fallbackScan(block: String): List<ToolCall> {
        val out = mutableListOf<ToolCall>()
        var searchFrom = 0
        val nameKeys = listOf("\"name\"", "\"tool\"", "\"function_name\"", "\"function\"", "\"tool_name\"")
        while (searchFrom < block.length) {
            var nextIdx = -1
            var matchedKey: String? = null
            for (key in nameKeys) {
                val idx = block.indexOf(key, searchFrom)
                if (idx >= 0 && (nextIdx < 0 || idx < nextIdx)) {
                    nextIdx = idx
                    matchedKey = key
                }
            }
            if (nextIdx < 0 || matchedKey == null) break
            val start = block.lastIndexOf('{', nextIdx)
            if (start < 0) break
            var end = matchBrace(block, start)
            var candidate: String? = null
            if (end > start) {
                candidate = block.substring(start, end + 1)
            } else {
                // Truncated — try to repair by balancing braces
                val tail = block.substring(start)
                var depth = 0
                var inStr = false
                var esc = false
                for (c in tail) {
                    if (inStr) {
                        when {
                            esc -> esc = false
                            c == '\\' -> esc = true
                            c == '"' -> inStr = false
                        }
                        continue
                    }
                    when (c) {
                        '"' -> inStr = true
                        '{' -> depth++
                        '}' -> depth--
                        '[' -> depth++
                        ']' -> depth--
                    }
                }
                if (depth > 0) {
                    // Append missing closing braces/brackets heuristically
                    val repaired = tail + "}".repeat(depth.coerceAtLeast(1))
                    // Try to parse repaired version; also try with "}}" for nested objects
                    candidate = repaired
                    // Quick validation: try to parse, if fails try alternative with "}}"
                    runCatching { json.parseToJsonElement(candidate!!).jsonObject }.getOrNull()?.let { out ->
                        // repaired candidate is valid, use it
                    } ?: run {
                        // Try adding extra "}"
                        candidate = tail + "}".repeat(depth + 1)
                    }
                } else {
                    // No depth but still unbalanced due to missing quote etc — take tail as-is and try
                    candidate = tail
                }
            }
            if (candidate != null) {
                runCatching {
                    toCall(json.parseToJsonElement(candidate).jsonObject, out.size)
                }.getOrNull()?.let { out += it }
                    ?: run {
                        // Last resort: try to extract via lenient JSON block parsing
                        extractJsonBlock(candidate)?.let { block2 ->
                            runCatching { toCall(json.parseToJsonElement(block2).jsonObject, out.size) }.getOrNull()?.let { out += it }
                        }
                    }
            }
            searchFrom = nextIdx + matchedKey.length
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
