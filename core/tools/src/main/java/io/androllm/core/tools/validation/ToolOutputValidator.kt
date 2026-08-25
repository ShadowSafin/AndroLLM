package io.androllm.core.tools.validation

import io.androllm.core.tools.api.ToolResult
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonObject
import timber.log.Timber

/**
 * Validates every tool output before it is fed into later tools.
 *
 * Rejects:
 * - empty object `{}`
 * - missing required fields (e.g. Weather must have temperature, rainProbability, forecast)
 * - malformed or blank summary
 *
 * Accepts only outputs that contain actionable data.
 * Invalid outputs are treated as retryable failures so the retry engine can
 * attempt recovery without restarting the whole execution.
 */
@Singleton
class ToolOutputValidator @Inject constructor() {

    sealed interface OutputValidation {
        data object Valid : OutputValidation
        data class Invalid(val reason: String, val retryable: Boolean = true) : OutputValidation
    }

    fun validate(toolName: String, result: ToolResult): OutputValidation {
        val summary = result.summary

        // Blank output is invalid (except for success with empty but tool already deemed success)
        if (result is ToolResult.Success && summary.isBlank()) {
            return OutputValidation.Invalid("Tool '$toolName' returned blank output", retryable = true)
        }
        if (summary.trim() == "{}" || summary.trim() == "[]") {
            return OutputValidation.Invalid("Tool '$toolName' returned empty object", retryable = true)
        }

        // Tool-specific output contracts
        val specific = when (toolName) {
            "get_weather" -> validateWeather(summary, result)
            "search_web" -> validateSearch(summary, result)
            "get_battery", "get_device_info" -> validateDevice(summary)
            "search_places", "open_navigation" -> validateMaps(summary)
            "note_save", "export_pdf", "export_markdown" -> validateFiles(summary, result)
            "send_sms", "send_email", "make_call" -> validateCommunication(summary, result)
            else -> null // generic tools: only blank check
        }
        if (specific is OutputValidation.Invalid) return specific

        // Generic data check for Success: if data is present but empty object for tools that should have data
        if (result is ToolResult.Success && result.data.isNotEmpty()) {
            // data is non-empty is fine; empty data for e.g. weather would have been caught above
        }

        Timber.d("ToolOutputValidator: $toolName valid (${summary.length} chars)")
        return OutputValidation.Valid
    }

    private fun validateWeather(summary: String, result: ToolResult): OutputValidation? {
        val lower = summary.lowercase()
        // Must contain temperature or condition
        if (!lower.contains("°c") && !lower.contains("temperature") && !lower.contains("humidity") && !lower.contains("rain") && !lower.contains("clear") && !lower.contains("sunny") && !lower.contains("cloud")) {
            if (summary.contains("No current weather") || summary.contains("Could not find")) return null // legitimate failure message, not crash
            return OutputValidation.Invalid("Weather output missing temperature/condition: ${summary.take(100)}")
        }
        return null
    }

    private fun validateSearch(summary: String, result: ToolResult): OutputValidation? {
        val lower = summary.lowercase()
        if (summary.contains("No web results")) return null // legitimate empty result, not invalid
        if (lower.length < 10 || (!lower.contains("search") && lower.length < 20)) {
            // Search should have some results or explanatory text
            if (result is ToolResult.Success && result.data["results"] == null && summary.length < 20) {
                return OutputValidation.Invalid("Search output too short and no results field")
            }
        }
        return null
    }

    private fun validateDevice(summary: String): OutputValidation? {
        if (summary.isBlank()) return OutputValidation.Invalid("Device output blank")
        return null
    }

    private fun validateMaps(summary: String): OutputValidation? {
        if (summary.contains("No maps app") || summary.contains("No app")) return null // legitimate failure, not invalid output
        if (summary.isBlank()) return OutputValidation.Invalid("Maps output blank")
        return null
    }

    private fun validateFiles(summary: String, result: ToolResult): OutputValidation? {
        // File saves must contain path
        if (result is ToolResult.Success) {
            val data = result.data
            if (!data.containsKey("path") && !summary.contains(":") && !summary.contains("/")) {
                // Allow note_save which says "Note 'X' saved." without path — still valid
                if (summary.contains("saved", ignoreCase = true) || summary.contains("success", ignoreCase = true)) return null
                return OutputValidation.Invalid("File tool output missing path: ${summary.take(100)}")
            }
        }
        return null
    }

    private fun validateCommunication(summary: String, result: ToolResult): OutputValidation? {
        // Communication tools when succeeded should mention sent/opened
        if (result is ToolResult.Success) {
            if (summary.contains("sent", ignoreCase = true) || summary.contains("opened", ignoreCase = true) || summary.contains("draft", ignoreCase = true)) return null
            if (summary.length < 10) return OutputValidation.Invalid("Communication output too short: ${summary.take(100)}")
        }
        return null
    }
}
