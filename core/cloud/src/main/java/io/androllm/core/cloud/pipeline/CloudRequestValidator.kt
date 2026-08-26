package io.androllm.core.cloud.pipeline

import io.androllm.core.cloud.model.CloudChatRequest

/** Result of validating a cloud request before it leaves the device. */
data class CloudRequestValidation(
    val valid: Boolean,
    val errors: List<String> = emptyList(),
    val warnings: List<String> = emptyList()
) {
    companion object {
        val OK = CloudRequestValidation(valid = true)
        fun invalid(vararg errors: String) =
            CloudRequestValidation(valid = false, errors = errors.toList())
    }
}

/**
 * Validates a cloud chat request before it is sent. Catches the failure
 * modes that otherwise surface as opaque provider 400s — blank model, empty
 * message list, malformed role ordering, oversized payloads, and broken tool
 * schemas — and turns them into actionable [CloudRequestValidation] results.
 *
 * Pure JVM logic; unit-testable in isolation.
 */
object CloudRequestValidator {

    private val KNOWN_ROLES = setOf("system", "user", "assistant", "tool", "function", "developer")
    private val TOOL_NAME_REGEX = Regex("^[a-zA-Z0-9_-]{1,64}$")

    /** Hard ceiling on serialized request size (guards against runaway history). */
    const val MAX_REQUEST_CHARS = 1_500_000

    /** Soft ceiling that produces a warning (context-window pressure). */
    const val WARN_REQUEST_CHARS = 400_000

    /** Max messages before we warn (most providers cap far below this). */
    const val WARN_MESSAGE_COUNT = 400

    fun validate(request: CloudChatRequest): CloudRequestValidation {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        if (request.model.isBlank()) {
            errors += "Model id is blank — no cloud model selected"
        }
        if (request.messages.isEmpty()) {
            errors += "Request has no messages"
        }

        var totalChars = 0
        var toolMessageWithoutId = 0
        var assistantToolCallsWithoutName = 0
        for ((index, message) in request.messages.withIndex()) {
            if (message.role !in KNOWN_ROLES) {
                errors += "Message $index has unknown role '${message.role}'"
            }
            totalChars += message.content?.length ?: 0
            message.contentParts?.forEach { part ->
                totalChars += when (part) {
                    is io.androllm.core.cloud.model.CloudContentPart.Text -> part.text.length
                    is io.androllm.core.cloud.model.CloudContentPart.Image -> part.url.length
                }
            }
            if (message.role == "tool" && message.toolCallId.isNullOrBlank()) {
                toolMessageWithoutId++
            }
            message.toolCalls?.forEach { call ->
                if (call.function?.name.isNullOrBlank()) assistantToolCallsWithoutName++
            }
        }
        if (toolMessageWithoutId > 0) {
            errors += "$toolMessageWithoutId tool message(s) missing tool_call_id"
        }
        if (assistantToolCallsWithoutName > 0) {
            errors += "$assistantToolCallsWithoutName tool call(s) missing a function name"
        }

        // The final message should normally be a user turn (or a tool result
        // mid-loop). An assistant-final message usually means the caller
        // forgot the user prompt — warn, don't block (continuations do this).
        request.messages.lastOrNull()?.let { last ->
            if (last.role != "user" && last.role != "tool") {
                warnings += "Last message role is '${last.role}' (expected user/tool)"
            }
        }

        if (request.messages.size > WARN_MESSAGE_COUNT) {
            warnings += "Very long conversation (${request.messages.size} messages) — may exceed the model's context window"
        }
        if (totalChars > MAX_REQUEST_CHARS) {
            errors += "Request too large (~$totalChars chars) — trim the conversation"
        } else if (totalChars > WARN_REQUEST_CHARS) {
            warnings += "Large request (~$totalChars chars) — close to typical context limits"
        }

        // Tool schema sanity: unique, well-formed names.
        val toolNames = request.tools.map { it.function.name }
        val duplicates = toolNames.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        if (duplicates.isNotEmpty()) {
            errors += "Duplicate tool names: ${duplicates.joinToString(", ")}"
        }
        for (name in toolNames) {
            if (!TOOL_NAME_REGEX.matches(name)) {
                errors += "Tool name '$name' is not valid (letters, digits, _ and - only, max 64 chars)"
            }
        }

        if (request.temperature.isNaN() || request.temperature < 0.0 || request.temperature > 2.0) {
            warnings += "Temperature ${request.temperature} is outside the usual 0.0–2.0 range"
        }
        request.max_tokens?.let { max ->
            if (max <= 0) errors += "max_tokens must be positive (got $max)"
        }

        return CloudRequestValidation(
            valid = errors.isEmpty(),
            errors = errors,
            warnings = warnings
        )
    }
}
