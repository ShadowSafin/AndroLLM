package io.androllm.core.tools.api

/**
 * One streaming lifecycle event for a single tool call, emitted by
 * [io.androllm.core.tools.coordinator.ToolRunCoordinator] as the call moves
 * through start → terminal state. The chat layer renders these as live tool
 * cards ("🔍 Searching files…" → "✓ done"), so the UI never has to guess
 * what the planner is doing.
 */
sealed interface ToolEvent {

    /** The tool's registry name (e.g. "get_battery"). */
    val name: String

    /** The tool is about to execute with the rendered arguments. */
    data class Started(
        override val name: String,
        /** Rendered arguments, e.g. `app=Discord`. */
        val arguments: String
    ) : ToolEvent

    /** The tool finished successfully; [summary] is what the LLM receives. */
    data class Succeeded(
        override val name: String,
        val summary: String
    ) : ToolEvent

    /** The tool failed; [error] carries the user-visible reason. */
    data class Failed(
        override val name: String,
        val error: String
    ) : ToolEvent

    /** The user declined the confirmation for a high-risk tool. */
    data class Declined(
        override val name: String
    ) : ToolEvent
}
