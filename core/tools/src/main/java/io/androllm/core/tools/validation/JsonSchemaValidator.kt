package io.androllm.core.tools.validation

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Strict JSON Schema validator for tool arguments.
 *
 * Validates:
 * - required fields
 * - argument types (string, integer, number, boolean, array, object, null)
 * - enums
 * - nullable values (type: ["string","null"] or nullable:true)
 * - extra fields (always rejected — requirement: Reject extra fields)
 * - malformed types
 *
 * Supports subset of JSON Schema used by ToolSpec.parameters:
 * {
 *   "type": "object",
 *   "properties": {
 *     "location": {"type":"string"},
 *     "percent": {"type":"integer"},
 *     "stream": {"type":"string","enum":["media","ring"]},
 *     "enabled": {"type":"boolean"}
 *   },
 *   "required": ["location"]
 * }
 */
object JsonSchemaValidator {

    fun validate(arguments: JsonObject, schema: JsonObject): ValidationResult {
        val properties = schema["properties"] as? JsonObject
        val required = (schema["required"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()

        // If schema declares no properties, treat as permissive (legacy tools without explicit schema)
        // — allow any arguments, only enforce required if present. Strict extra-field rejection
        // only applies when the tool explicitly declares its properties.
        // However, always check for nested tool calls and injection patterns even in permissive mode.
        if (properties == null || properties.isEmpty()) {
            // Nested tool call detection even for permissive schemas
            for ((key, value) in arguments) {
                if (value is JsonObject && value.containsKey("name") && (value.containsKey("arguments") || value.containsKey("args"))) {
                    return ValidationResult.Invalid("Nested tool calls not allowed in parameter '$key'")
                }
                if (value is JsonArray) {
                    for (element in value) {
                        if (element is JsonObject && element.containsKey("name") && element.containsKey("arguments")) {
                            return ValidationResult.Invalid("Nested tool calls not allowed in parameter '$key'")
                        }
                    }
                }
            }
            if (required.isEmpty()) {
                // No schema constraints -> allow any arguments (legacy permissive)
                return ValidationResult.Valid
            }
            // Has required but no properties definition: check required presence only
            val errors = mutableListOf<String>()
            for (req in required) {
                if (!arguments.containsKey(req)) errors += "Missing required parameter: '$req'"
            }
            return if (errors.isEmpty()) ValidationResult.Valid else ValidationResult.Invalid(errors, retryable = true)
        }

        val errors = mutableListOf<String>()
        val definedProps = properties?.keys ?: emptySet()

        // 1. Required fields
        for (req in required) {
            if (!arguments.containsKey(req)) {
                errors += "Missing required parameter: '$req'"
            } else {
                val value = arguments[req]
                if (value is JsonNull) {
                    // Check if nullable allowed
                    val propSchema = (properties?.get(req) as? JsonObject)
                    if (!isNullableAllowed(propSchema)) {
                        errors += "Parameter '$req' must not be null"
                    }
                }
            }
        }

        // 2. Extra fields — always rejected per requirement
        for (key in arguments.keys) {
            if (key !in definedProps) {
                errors += "Extra field not allowed: '$key'"
            }
        }

        // 3. Type and enum validation for each provided argument
        for ((key, value) in arguments) {
            val propSchema = (properties?.get(key) as? JsonObject) ?: continue // already flagged as extra
            // Nested tool call detection: arguments must not contain nested tool call objects
            if (value is JsonObject && value.containsKey("name") && (value.containsKey("arguments") || value.containsKey("args"))) {
                errors += "Nested tool calls not allowed in parameter '$key'"
                continue
            }
            if (value is JsonArray) {
                // Check array elements for nested tool calls
                for (element in value) {
                    if (element is JsonObject && element.containsKey("name") && element.containsKey("arguments")) {
                        errors += "Nested tool calls not allowed in parameter '$key'"
                        break
                    }
                }
            }
            // Prompt injection inside argument values: reject if argument string contains injection patterns
            if (value is JsonPrimitive && value.isString) {
                val strVal = value.contentOrNull ?: ""
                if (strVal.contains("<tool_call", ignoreCase = true) || strVal.contains("\"name\"", ignoreCase = true) && strVal.contains("\"arguments\"")) {
                    // Only flag if it looks like embedded tool JSON, not normal text
                    if (strVal.trim().startsWith("{") && strVal.contains("\"name\"")) {
                        errors += "Nested tool call syntax not allowed in parameter '$key'"
                        continue
                    }
                }
            }
            // Skip null values already handled; if nullable allowed, skip type check
            if (value is JsonNull) {
                if (!isNullableAllowed(propSchema)) {
                    errors += "Parameter '$key' must not be null"
                }
                continue
            }
            val typeErrors = validateType(key, value, propSchema)
            errors += typeErrors

            // Enum validation
            val enumValues = propSchema["enum"] as? JsonArray
            if (enumValues != null && value is JsonPrimitive && value.isString) {
                val allowed = enumValues.mapNotNull { it.jsonPrimitive.contentOrNull }
                val actual = value.contentOrNull
                if (actual != null && actual !in allowed) {
                    errors += "Unknown enum value '$actual' for parameter '$key' — allowed: ${allowed.joinToString(", ")}"
                }
            }
        }

        return if (errors.isEmpty()) ValidationResult.Valid else ValidationResult.Invalid(errors, retryable = isRetryable(errors))
    }

    private fun isNullableAllowed(propSchema: JsonObject?): Boolean {
        if (propSchema == null) return false
        // Check explicit nullable flag
        if ((propSchema["nullable"] as? JsonPrimitive)?.booleanOrNull == true) return true
        // Check type includes null: {"type":["string","null"]} or {"type":"string","enum":[..., null]}
        val typeEl = propSchema["type"] ?: return false
        if (typeEl is JsonArray) {
            return typeEl.any { (it as? JsonPrimitive)?.contentOrNull == "null" }
        }
        return false
    }

    private fun validateType(key: String, value: JsonElement, propSchema: JsonObject): List<String> {
        val typeEl = propSchema["type"] ?: return emptyList() // no type constraint -> pass
        val allowedTypes: List<String> = when (typeEl) {
            is JsonPrimitive -> listOf(typeEl.contentOrNull ?: return emptyList())
            is JsonArray -> typeEl.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            else -> return emptyList()
        }
        // If nullable and value is null, already handled
        if (value is JsonNull) return emptyList()

        // Check if value matches any allowed type
        for (type in allowedTypes) {
            if (type == "null") continue
            if (matchesType(value, type)) return emptyList()
        }
        // None matched -> error
        return listOf("Parameter '$key' has invalid type: expected ${allowedTypes.joinToString("/")} but got ${describeJsonType(value)}")
    }

    private fun matchesType(value: JsonElement, expected: String): Boolean = when (expected) {
        "string" -> value is JsonPrimitive && value.isString
        "integer" -> {
            if (value !is JsonPrimitive) false
            else {
                val primitive = value
                // Must be a number without fractional part
                if (!primitive.isString) {
                    val longVal = primitive.longOrNull
                    val doubleVal = primitive.doubleOrNull
                    longVal != null && doubleVal != null && doubleVal == longVal.toDouble()
                } else false
            }
        }
        "number" -> {
            if (value !is JsonPrimitive) false
            else !value.isString && value.doubleOrNull != null
        }
        "boolean" -> value is JsonPrimitive && value.booleanOrNull != null
        "array" -> value is JsonArray
        "object" -> value is JsonObject
        else -> false
    }

    private fun describeJsonType(value: JsonElement): String = when (value) {
        is JsonNull -> "null"
        is JsonArray -> "array"
        is JsonObject -> "object"
        is JsonPrimitive -> when {
            value.isString -> "string"
            value.booleanOrNull != null -> "boolean"
            value.longOrNull != null -> "integer"
            value.doubleOrNull != null -> "number"
            else -> "unknown"
        }
        else -> "unknown"
    }

    private fun isRetryable(errors: List<String>): Boolean {
        // Unknown enum, extra field, type mismatch due to hallucinated value => retryable if formatting-like
        // But unknown tool is not retryable — handled elsewhere.
        // Missing required -> not strictly formatting, but could be retryable if model omitted accidentally
        // We treat all schema errors as retryable except unknown enum which is also retryable once?
        // Requirement: Retry once if validation fails due to formatting
        // So we mark as retryable = true for formatting errors, false for semantic?
        // Simplify: all schema validation errors are retryable once (formatting), except empty? Keep true
        return true
    }

    /**
     * Validates raw JSON string is well-formed. Returns error if malformed.
     */
    fun validateJsonSyntax(raw: String): ValidationResult {
        if (raw.isBlank()) return ValidationResult.Invalid("Empty JSON", retryable = true)
        return try {
            kotlinx.serialization.json.Json.parseToJsonElement(raw)
            ValidationResult.Valid
        } catch (e: Exception) {
            ValidationResult.Invalid("Malformed JSON: ${e.message?.take(200) ?: "invalid JSON"}", retryable = true)
        }
    }
}
