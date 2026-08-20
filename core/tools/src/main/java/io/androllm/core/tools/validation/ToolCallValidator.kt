package io.androllm.core.tools.validation

import io.androllm.core.tools.api.ToolCall
import io.androllm.core.tools.registry.ToolRegistry
import kotlinx.serialization.json.Json
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Strict validator for tool calls — enforces the execution pipeline:
 * Assistant -> Generate response -> Tool detector -> JSON validator -> Registry validation -> Argument validation -> Execute
 *
 * Never bypasses validation. Every tool call must be:
 * - well-formed JSON
 * - with a known registry name
 * - with non-empty name
 * - with valid arguments per JSON schema (types, required, enums, no extra fields)
 */
@Singleton
class ToolCallValidator @Inject constructor(
    private val registry: ToolRegistry
) {

    private val strictJson = Json {
        ignoreUnknownKeys = false
        isLenient = false
        allowStructuredMapKeys = false
    }

    /**
     * Validates a single [ToolCall] against the registry and its schema.
     * Returns Valid or Invalid with detailed errors.
     */
    fun validate(call: ToolCall): ValidationResult {
        // Empty tool name -> reject
        if (call.name.isBlank()) {
            return ValidationResult.Invalid("Tool name must not be empty", retryable = false)
        }
        // Registry validation — unknown tool -> reject, not retryable
        val tool = registry.get(call.name)
        if (tool == null) {
            Timber.w("ToolCallValidator: unknown tool '${call.name}' — not in registry")
            return ValidationResult.Invalid("Unknown tool '${call.name}' — not in registry", retryable = false)
        }
        // Argument validation against JSON schema
        val schema = tool.spec.parameters
        val result = JsonSchemaValidator.validate(call.arguments, schema)
        if (result is ValidationResult.Invalid) {
            Timber.w("ToolCallValidator: validation failed for '${call.name}': ${result.errors}")
        }
        return result
    }

    /**
     * Validates raw JSON string before parsing into ToolCall.
     * Checks malformed JSON, empty tool name.
     */
    fun validateRawJson(raw: String): ValidationResult {
        if (raw.isBlank()) return ValidationResult.Invalid("Empty tool call JSON", retryable = true)
        return try {
            strictJson.parseToJsonElement(raw)
            ValidationResult.Valid
        } catch (e: Exception) {
            ValidationResult.Invalid("Malformed JSON: ${e.message?.take(200) ?: "invalid"}", retryable = true)
        }
    }

    /**
     * Validates a batch of calls. Returns map of call to result.
     * Used for multi-tool validation.
     */
    fun validateAll(calls: List<ToolCall>): Map<ToolCall, ValidationResult> {
        return calls.associateWith { validate(it) }
    }

    /**
     * Filters valid calls, returns invalid ones for recovery logging.
     */
    fun partitionValid(calls: List<ToolCall>): Pair<List<ToolCall>, List<Pair<ToolCall, ValidationResult.Invalid>>> {
        val valid = mutableListOf<ToolCall>()
        val invalid = mutableListOf<Pair<ToolCall, ValidationResult.Invalid>>()
        for (call in calls) {
            when (val result = validate(call)) {
                is ValidationResult.Valid -> valid += call
                is ValidationResult.Invalid -> invalid += call to result
            }
        }
        return valid to invalid
    }
}
