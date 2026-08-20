package io.androllm.core.tools.validation

import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Internal logger for tool calling — records:
 * - selected tool
 * - validation result
 * - execution time
 * - execution success/failure
 * - validation errors
 *
 * Never exposed to the user — internal diagnostics only.
 * Uses Timber internally; could be extended to persist to file or analytics.
 */
@Singleton
class ToolExecutionLogger @Inject constructor() {

    data class LogEntry(
        val timestamp: Long = System.currentTimeMillis(),
        val toolName: String,
        val validationResult: String,
        val executionTimeMs: Long? = null,
        val success: Boolean? = null,
        val validationErrors: List<String>? = null,
        val error: String? = null
    )

    private val entries = mutableListOf<LogEntry>()
    private val maxEntries = 500
    private val lock = Any()

    fun logValidation(toolName: String, result: ValidationResult) {
        val entry = LogEntry(
            toolName = toolName,
            validationResult = if (result.isValid) "valid" else "invalid",
            validationErrors = (result as? ValidationResult.Invalid)?.errors,
            success = null
        )
        addEntry(entry)
        if (result.isValid) {
            Timber.i("ToolExecutionLogger: validation passed for '$toolName'")
        } else {
            Timber.w("ToolExecutionLogger: validation failed for '$toolName': ${(result as ValidationResult.Invalid).errors}")
        }
    }

    fun logExecution(toolName: String, durationMs: Long, success: Boolean, error: String? = null) {
        val entry = LogEntry(
            toolName = toolName,
            validationResult = "executed",
            executionTimeMs = durationMs,
            success = success,
            error = error
        )
        addEntry(entry)
        Timber.i("ToolExecutionLogger: execution '$toolName' -> ${if (success) "success" else "failure"} in ${durationMs}ms${error?.let { " error=$it" } ?: ""}")
    }

    fun logSelection(toolNames: List<String>, query: String) {
        Timber.i("ToolExecutionLogger: selected tools $toolNames for query '${query.take(80)}'")
        synchronized(lock) {
            // Selection is logged via Timber only, not stored as entry to avoid spam
        }
    }

    fun logStreamingBuffered(count: Int) {
        Timber.d("ToolExecutionLogger: buffered $count streaming tool calls (not streamed)")
    }

    fun logRetry(toolName: String, attempt: Int, reason: String) {
        Timber.w("ToolExecutionLogger: retry $attempt for '$toolName' due to: $reason")
    }

    private fun addEntry(entry: LogEntry) {
        synchronized(lock) {
            entries += entry
            if (entries.size > maxEntries) entries.removeAt(0)
        }
    }

    fun getRecentEntries(limit: Int = 50): List<LogEntry> = synchronized(lock) {
        entries.takeLast(limit).toList()
    }

    fun clear() = synchronized(lock) { entries.clear() }
}
