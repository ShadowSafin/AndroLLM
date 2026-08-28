package io.androllm.feature.coding.ui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Formats a tool invocation as a short human-readable label for the live tool
 * card in the chat transcript (shown the moment the tool STARTS, then the card
 * updates in place with streaming output / the final result — the
 * opencode/claude-code style). Pure + tolerant of malformed args JSON.
 */
object ToolCallLabels {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Instant label shown the moment a tool call is ANNOUNCED (its name has
     * appeared in the stream but the arguments — e.g. a whole file's content
     * for write_file — may still be streaming). Verb-only on purpose, so the
     * user immediately sees "Writing file…" / "Reading file…" instead of the
     * UI looking stuck.
     */
    fun announcing(name: String): String = when (name) {
        "write_file" -> "Writing file…"
        "edit_file" -> "Editing file…"
        "replace_text" -> "Editing file…"
        "read_file" -> "Reading file…"
        "run_command" -> "Running command…"
        "grep" -> "Searching codebase…"
        "list_dir" -> "Listing directory…"
        "file_tree" -> "Loading file tree…"
        "git_status" -> "Checking git status…"
        "workspace_summary" -> "Summarizing workspace…"
        "list_background_services" -> "Checking background services…"
        "stop_background_service" -> "Stopping service…"
        else -> "Preparing $name…"
    }

    fun describe(name: String, argsJson: String): String {
        val args = runCatching {
            json.parseToJsonElement(argsJson.trim().ifEmpty { "{}" }) as? JsonObject
        }.getOrNull()

        fun str(key: String): String? =
            (args?.get(key) as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }

        return when (name) {
            "run_command" -> buildString {
                val cmd = str("command") ?: "?"
                // Precise status first ("Installing dependencies..."), command second.
                commandIntent(cmd)?.let { append(it).append('\n') }
                append("$ ").append(cmd)
                str("working_dir")?.let { append("   (in ").append(it).append(")") }
                val bg = (args?.get("background") as? JsonPrimitive)?.content
                if (bg == "true") append("   [background]")
            }
            "read_file" -> "Reading ${str("path") ?: "?"}"
            "write_file" -> buildString {
                append("Writing ").append(str("path") ?: "?")
                str("content")?.let { append(" (").append(it.length).append(" chars)") }
            }
            "edit_file" -> "Editing ${str("path") ?: "?"}"
            "replace_text" -> "Replacing text in ${str("path") ?: "?"}"
            "grep" -> buildString {
                append("Searching \"").append(str("pattern") ?: "?").append("\"")
                str("path")?.let { append(" in ").append(it) }
            }
            "list_dir" -> "Listing ${str("path") ?: "."}"
            "file_tree" -> "File tree${str("path")?.let { " ($it)" } ?: ""}"
            "git_status" -> "git status"
            "workspace_summary" -> "Workspace summary"
            "list_background_services" -> buildString {
                append("Background services")
                str("id")?.let { append(" → ").append(it) }
            }
            "stop_background_service" -> "Stopping ${str("id") ?: "?"}"
            else -> name
        }
    }

    // Command intents, checked in priority order (first match wins).
    private val INSTALL_RE = Regex("(?i)\\b(install|i|add|update|upgrade)\\b")
    private val PKG_MGR_RE = Regex("(?i)\\b(npm|pnpm|yarn|bun|pip3?|apt(-get)?|apk|gem|composer|cargo|go)\\b")
    private val SERVER_RE = Regex("(?i)(http\\.server|runserver|\\bdev\\b|\\bserve\\b|\\bstart\\b|nodemon|uvicorn|gunicorn|flask run|php -S|rails s)")

    /**
     * Precise, human-readable status for what a shell command is actually doing —
     * "Installing dependencies...", "Building project...", "Running tests...",
     * "Starting local server..." — instead of a generic "Running command...".
     * Returns null when no clear intent is detected.
     */
    fun commandIntent(command: String): String? {
        val c = command.trim()
        if (c.isEmpty()) return null
        return when {
            // Installing dependencies (npm install, pip install -r ..., apt-get install ...)
            INSTALL_RE.containsMatchIn(c) && PKG_MGR_RE.containsMatchIn(c) -> "Installing dependencies..."
            // Starting a local / dev server
            SERVER_RE.containsMatchIn(c) -> "Starting local server..."
            // Running tests
            c.contains("test", ignoreCase = true) -> "Running tests..."
            // Lint / format checks
            c.contains("lint", ignoreCase = true) || c.contains("eslint", ignoreCase = true) ||
                c.contains("prettier", ignoreCase = true) || Regex("(?i)\\bformat\\b").containsMatchIn(c) ->
                "Checking code quality..."
            // Building / compiling
            c.contains("build", ignoreCase = true) || c.contains("compile", ignoreCase = true) ||
                c.contains("assemble", ignoreCase = true) || c.contains("tsc", ignoreCase = true) ->
                "Building project..."
            else -> null
        }
    }
}
