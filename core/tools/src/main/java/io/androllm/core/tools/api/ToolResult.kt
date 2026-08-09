package io.androllm.core.tools.api

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

/**
 * Outcome of a single tool execution. Both variants carry a human-readable
 * [summary] that is fed back to the LLM; [Success.data] holds structured
 * values (e.g. the parsed weather report) that richer surfaces can render.
 */
sealed interface ToolResult {

    val summary: String

    data class Success(
        override val summary: String,
        val data: JsonObject = buildJsonObject { }
    ) : ToolResult

    data class Failure(
        override val summary: String,
        /** Structured diagnostics (e.g. candidate apps, status codes). */
        val data: JsonObject = buildJsonObject { },
        /**
         * Whether the workflow may retry this call once. False for outcomes
         * retrying cannot fix (user declined, blocked by settings).
         */
        val retryable: Boolean = true
    ) : ToolResult

    val isSuccess: Boolean get() = this is Success

    /** Short stable status used for logging and the tool-activity chip. */
    val statusLabel: String
        get() = when (this) {
            is Success -> "ok"
            is Failure -> "failed"
        }
}
