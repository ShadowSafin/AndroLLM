package io.androllm.core.memory.extraction

import io.androllm.core.memory.MemoryCategory
import io.androllm.core.memory.model.ExtractedMemory
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Wire format returned by the extraction LLM call.
 */
@Serializable
data class ExtractionResponse(
    val memories: List<ExtractionItem> = emptyList()
)

@Serializable
data class ExtractionItem(
    val content: String = "",
    val category: String = "CUSTOM",
    val importance: Int = 1,
    val tags: List<String> = emptyList(),
    val project: String? = null
)

/**
 * JSON schema passed to the native grammar generator so the model emits
 * valid extraction JSON (fields are constrained to real categories).
 */
object ExtractionSchema {
    const val JSON_SCHEMA: String = """
    {
      "type": "object",
      "properties": {
        "memories": {
          "type": "array",
          "items": {
            "type": "object",
            "properties": {
              "content": { "type": "string" },
              "category": {
                "type": "string",
                "enum": ["IDENTITY","PREFERENCES","PROJECTS","GOALS","SKILLS","PROGRAMMING_LANGUAGES","FRAMEWORKS","DEVICES","PINNED_FACTS","DEVELOPER_NOTES","CUSTOM"]
              },
              "importance": { "type": "integer" },
              "tags": { "type": "array", "items": { "type": "string" } },
              "project": { "type": "string" }
            },
            "required": ["content"]
          }
        }
      },
      "required": ["memories"]
    }
    """
}

/**
 * Parses raw LLM output into extracted memories. Lenient on purpose:
 * tolerant of markdown code fences, leading prose, truncated JSON and
 * casing variants of category names. Never throws; returns an empty list
 * when nothing usable is found.
 */
object ExtractionJsonParser {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun parse(raw: String): List<ExtractedMemory> {
        if (raw.isBlank()) return emptyList()
        val cleaned = raw.trim().removePrefix("```json").removePrefix("```")
        // Prefer the first balanced {...} block; when the output is truncated
        // (no closing brace) fall back to scanning the whole text.
        val block = extractJsonBlock(cleaned) ?: cleaned

        val response = try {
            json.decodeFromString(ExtractionResponse.serializer(), block)
        } catch (_: Exception) {
            // Fallback: single object form or partial content lines.
            parseFallback(block)
        }

        return response.memories
            .asSequence()
            .mapNotNull { item ->
                val content = item.content.trim()
                if (content.isEmpty() || content.length > 800) return@mapNotNull null
                ExtractedMemory(
                    content = content,
                    category = MemoryCategory.fromName(item.category),
                    importance = item.importance.coerceIn(1, 5),
                    tags = item.tags.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(10),
                    projectName = item.project?.trim()?.takeIf { it.isNotEmpty() }
                )
            }
            .distinctBy { it.content.lowercase() }
            .toList()
    }

    /**
     * Extracts the first balanced {...} object from the raw text, skipping
     * ```json fences and any prose before/after the object.
     */
    private fun extractJsonBlock(raw: String): String? {
        val text = raw.trim().removePrefix("```json").removePrefix("```")
        val start = text.indexOf('{')
        if (start < 0) return null

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
                    if (depth == 0) return text.substring(start, i + 1)
                }
            }
        }
        return null
    }

    /**
     * Very tolerant fallback: tries the raw block as a JSON object directly,
     * then as a JSON array, and finally scans for "content" string values.
     */
    private fun parseFallback(block: String): ExtractionResponse {
        // Try a plain object.
        runCatching {
            val obj = json.parseToJsonElement(block).jsonObject
            val items = parseItems(obj["memories"])
            if (items != null) return ExtractionResponse(items)
        }

        // Try an array of objects.
        runCatching {
            val arr = json.parseToJsonElement(block).jsonArray
            val items = arr.mapNotNull { el ->
                val obj = (el as? JsonObject) ?: return@mapNotNull null
                toItem(obj)
            }
            if (items.isNotEmpty()) return ExtractionResponse(items)
        }

        // Scan lines for `"content": "..."` pairs.
        val items = mutableListOf<ExtractionItem>()
        val regex = Regex("\"content\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
        for (match in regex.findAll(block)) {
            items.add(ExtractionItem(content = match.groupValues[1]))
        }
        return ExtractionResponse(items)
    }

    private fun parseItems(element: kotlinx.serialization.json.JsonElement?): List<ExtractionItem>? {
        val arr = (element as? JsonArray) ?: return null
        return arr.mapNotNull { el ->
            val obj = (el as? JsonObject) ?: return@mapNotNull null
            toItem(obj)
        }
    }

    private fun toItem(obj: JsonObject): ExtractionItem {
        fun str(key: String): String = (obj[key] as? JsonPrimitive)?.content?.trim().orEmpty()
        fun strList(key: String): List<String> =
            (obj[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }?.filter { it.isNotBlank() }
                ?: emptyList()
        return ExtractionItem(
            content = str("content"),
            category = str("category").ifEmpty { "CUSTOM" },
            importance = (obj["importance"] as? JsonPrimitive)?.intOrNull ?: 1,
            tags = strList("tags"),
            project = str("project").ifEmpty { null }
        )
    }
}
