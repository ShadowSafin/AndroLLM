package io.androllm.core.tools.validation

import io.androllm.core.tools.api.ToolBackend
import io.androllm.core.tools.api.ToolCall
import io.androllm.core.tools.api.ToolCallParser
import io.androllm.core.tools.api.ToolResult
import io.androllm.core.tools.confirmation.ToolConfirmationManager
import io.androllm.core.tools.executor.ToolExecutor
import io.androllm.core.tools.registry.ToolRegistry
import io.androllm.core.tools.router.ToolRouter
import io.androllm.core.tools.validation.PromptInjectionDetector
import io.androllm.engine.core.OutputSanitizer
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import timber.log.Timber

/**
 * Hardened 10-stage tool-calling pipeline (requirement 1):
 *
 * 1. user message
 * 2. model generation (provided as raw output string)
 * 3. tool intent detection
 * 4. tool selection
 * 5. argument extraction
 * 6. validation (JSON schema, types, required, enums, extra fields, empty names)
 * 7. confirmation check (never / high-risk only / always)
 * 8. tool execution (via [ToolExecutor] — the ONLY place that runs tool code)
 * 9. tool result formatting (sanitized, chunked, never truncated)
 * 10. final assistant response (sanitized, no raw markup)
 *
 * The pipeline normalizes ALL tool-call output formats (requirement 3) into one
 * internal [ToolCall] representation via [ToolCallParser]:
 * - native structured tool calls
 * - JSON tool call objects (including {"tool":"x","args":{}} variants)
 * - plain-text intent phrases (heuristic fallback)
 * - XML-like tool tags (<tool_call>...</tool_call>)
 * - provider-specific formats
 * - partial / malformed output (brace scanning, truncated recovery)
 *
 * Local-model support (requirement 2 & 8-9): the same pipeline is used for
 * cloud (native tool_calls) and local (prompt-based JSON + heuristic fallback).
 * When a local model fails to emit valid syntax, [heuristicFallback] detects
 * intent from the user's natural language, maps to the closest tool, and
 * extracts arguments heuristically before re-entering validation.
 *
 * Streaming safety (requirement 10): partial tokens never reach validation or
 * execution — [StreamingToolCallBuffer] holds them, [OutputSanitizer.streamingReady]
 * holds back incomplete tags. Response rendering (requirement 7) always goes
 * through [OutputSanitizer.sanitize] so raw tags/JSON never reach the UI.
 *
 * Logging (requirement 11): every stage logs via [ToolExecutionLogger] and
 * Timber at DEBUG/INFO/WARN. Logs are internal only, never shown to users.
 */
@Singleton
class ToolCallingPipeline @Inject constructor(
    private val registry: ToolRegistry,
    private val router: ToolRouter,
    private val validator: ToolCallValidator,
    private val executor: ToolExecutor,
    private val confirmationManager: ToolConfirmationManager,
    private val logger: ToolExecutionLogger,
    private val streamingBuffer: StreamingToolCallBuffer = StreamingToolCallBuffer(validator)
) {

    /**
     * Result of running the full pipeline for one turn. UI must display ONLY
     * [finalResponse] (and optionally [toolSummaries]); never [rawToolMarkup].
     */
    data class PipelineRunResult(
        val finalResponse: String,
        val toolSummaries: List<String>,
        val executedCalls: List<ToolCall>,
        val rawToolMarkupStripped: Boolean,
        val stages: List<String>
    )

    /**
     * Runs the 10-stage pipeline for [userMessage] and [modelOutput] (the raw
     * generation from either cloud or local). [isLocal] selects the prompt
     * template and fallback path; [hasAttachments] routes tool advertisement.
     *
     * This method NEVER executes a tool directly from raw output without
     * validation (requirement 1). Every call passes through [validator] and
     * the confirmation gate.
     */
    suspend fun run(
        userMessage: String,
        modelOutput: String,
        isLocal: Boolean = true,
        hasAttachments: Boolean = false
    ): PipelineRunResult {
        val stages = mutableListOf<String>()
        Timber.i("ToolCallingPipeline: === PIPELINE START (local=$isLocal, attachments=$hasAttachments) ===")
        Timber.i("ToolCallingPipeline: stage 1 — user message: '${userMessage.take(80)}'")
        stages += "1:user_message"
        logger.logSelection(emptyList(), userMessage) // stage 1 logging

        // Stage 2: model generation (already provided as modelOutput, but we log it)
        Timber.i("ToolCallingPipeline: stage 2 — model generation (${modelOutput.length} chars, preview='${modelOutput.take(80)}')")
        stages += "2:model_generation"

        // Stage 3: tool intent detection (via router + heuristic)
        val routed = router.route(userMessage, hasAttachments, registry.all().map { it.spec })
        Timber.i("ToolCallingPipeline: stage 3 — tool intent detection: intent=${routed.intent}, reason='${routed.reason}'")
        stages += "3:intent_detection:${routed.intent.name}"
        logger.logSelection(routed.specs.map { it.name }, userMessage)

        if (routed.specs.isEmpty()) {
            Timber.i("ToolCallingPipeline: no tools routed — returning sanitized final response without tool calls")
            val sanitized = sanitizeFinalResponse(modelOutput)
            stages += "10:final_response(no_tool)"
            return PipelineRunResult(
                finalResponse = sanitized,
                toolSummaries = emptyList(),
                executedCalls = emptyList(),
                rawToolMarkupStripped = sanitized != modelOutput,
                stages = stages
            )
        }

        // Stage 4: tool selection — already done by router (specs), log it
        Timber.i("ToolCallingPipeline: stage 4 — tool selection: ${routed.specs.map { it.name }} (confidence=${routed.confidence})")
        stages += "4:tool_selection:${routed.specs.joinToString(",") { it.name }}"
        // Filter specs by backend availability (requirement 4)
        val backendFiltered = routed.specs.filter { spec ->
            val ok = if (isLocal) spec.worksLocally && spec.supports(ToolBackend.LOCAL) else spec.supports(ToolBackend.CLOUD)
            val available = spec.availableOnDevice
            if (!ok) Timber.w("ToolCallingPipeline: filtered '${spec.name}' — not supported on ${if (isLocal) "local" else "cloud"} backend")
            if (!available) Timber.w("ToolCallingPipeline: filtered '${spec.name}' — not available on device")
            ok && available
        }
        if (backendFiltered.isEmpty()) {
            Timber.w("ToolCallingPipeline: no tools survive backend/availability filter — falling back to plain answer")
            val sanitized = sanitizeFinalResponse(modelOutput)
            stages += "10:final_response(filtered)"
            return PipelineRunResult(sanitized, emptyList(), emptyList(), sanitized != modelOutput, stages)
        }

        // Stage 5: argument extraction — normalize ALL formats via ToolCallParser (requirement 3)
        Timber.i("ToolCallingPipeline: stage 5 — argument extraction from model output (${modelOutput.length} chars)")
        stages += "5:argument_extraction"
        val rawCalls = ToolCallParser.parse(modelOutput)
        // Also handle native <|tool_call|> markers that the parser may have missed due to separate scanning
        val nativeCalls = try {
            io.androllm.engine.core.NativeToolCallScanner.scan(modelOutput).map { native ->
                val argsObj = runCatching {
                    kotlinx.serialization.json.Json.parseToJsonElement(native.argumentsJson).let { it as? JsonObject } ?: JsonObject(emptyMap())
                }.getOrElse { JsonObject(emptyMap()) }
                ToolCall(id = "call_${native.name}_${(0..9999).random()}", name = native.name, arguments = argsObj)
            }
        } catch (_: Exception) { emptyList() }
        val mergedCalls = (rawCalls + nativeCalls).distinctBy { it.id }

        var calls = mergedCalls
        Timber.i("ToolCallingPipeline: stage 5 — extracted ${calls.size} call(s): ${calls.map { it.name }} (raw parsed ${rawCalls.size}, native ${nativeCalls.size})")
        logger.logSelection(calls.map { it.name }, modelOutput.take(80))

        // Requirement 9 fallback: if local model failed to emit valid syntax but router says tool is needed,
        // detect intent from user natural language heuristically
        if (calls.isEmpty() && isLocal && backendFiltered.isNotEmpty()) {
            Timber.w("ToolCallingPipeline: stage 5 — no structured calls extracted but tool is routed — attempting heuristic fallback from user message")
            stages += "5:heuristic_fallback"
            val heuristic = heuristicFallback(userMessage, backendFiltered)
            if (heuristic.isNotEmpty()) {
                Timber.i("ToolCallingPipeline: heuristic fallback produced ${heuristic.size} call(s): ${heuristic.map { it.name }}")
                calls = heuristic
                logger.logSelection(heuristic.map { it.name }, "$userMessage [heuristic fallback]")
            } else {
                Timber.i("ToolCallingPipeline: heuristic fallback produced no calls — returning sanitized answer without tools")
                val sanitized = sanitizeFinalResponse(modelOutput)
                stages += "10:final_response(heuristic_empty)"
                return PipelineRunResult(sanitized, emptyList(), emptyList(), sanitized != modelOutput, stages)
            }
        }

        if (calls.isEmpty()) {
            Timber.i("ToolCallingPipeline: stage 5 — no calls after extraction — returning sanitized final response")
            val sanitized = sanitizeFinalResponse(modelOutput)
            stages += "10:final_response(no_calls)"
            return PipelineRunResult(sanitized, emptyList(), emptyList(), sanitized != modelOutput, stages)
        }

        // Stage 6: validation — strict JSON schema, types, required, enums, extra fields, empty names
        Timber.i("ToolCallingPipeline: stage 6 — validation of ${calls.size} call(s)")
        stages += "6:validation"
        val validCalls = mutableListOf<ToolCall>()
        val invalidPairs = mutableListOf<Pair<ToolCall, ValidationResult.Invalid>>()
        for (call in calls) {
            val result = validator.validate(call)
            logger.logValidation(call.name, result)
            when (result) {
                is ValidationResult.Valid -> {
                    Timber.i("ToolCallingPipeline: stage 6 — '${call.name}' VALID")
                    validCalls += call
                }
                is ValidationResult.Invalid -> {
                    Timber.w("ToolCallingPipeline: stage 6 — '${call.name}' INVALID: ${result.firstError} (retryable=${result.retryable})")
                    invalidPairs += call to result
                }
            }
        }
        if (invalidPairs.isNotEmpty() && validCalls.isEmpty()) {
            // All calls invalid — do not execute, return helpful error via sanitized response
            val firstError = invalidPairs.first().second.firstError
            Timber.w("ToolCallingPipeline: stage 6 — all calls invalid — returning error, asking model to regenerate")
            stages += "6:validation_all_invalid"
            val errorResponse = "I couldn't complete that action: $firstError. Could you provide the missing information?"
            val sanitized = sanitizeFinalResponse(errorResponse)
            stages += "10:final_response(validation_error)"
            return PipelineRunResult(sanitized, emptyList(), emptyList(), false, stages)
        } else if (invalidPairs.isNotEmpty()) {
            Timber.w("ToolCallingPipeline: stage 6 — ${invalidPairs.size} invalid call(s) filtered, ${validCalls.size} valid remain")
            stages += "6:validation_partial(${invalidPairs.size} invalid)"
        } else {
            stages += "6:validation_all_valid"
        }

        // Stage 7: confirmation check (requirement 5) — never / high-risk only / always
        Timber.i("ToolCallingPipeline: stage 7 — confirmation check for ${validCalls.size} valid call(s)")
        stages += "7:confirmation_check"
        val confirmedCalls = mutableListOf<ToolCall>()
        for (call in validCalls) {
            val spec = registry.get(call.name)?.spec
            val requiresConfirm = spec?.requiresConfirmation == true
            val shouldConfirm = spec?.let { registry.get(it.name)?.spec?.requiresConfirmation } ?: requiresConfirm
            // Use the pipeline's confirmation logic via executor's settings check — we log here explicitly
            Timber.i("ToolCallingPipeline: stage 7 — '${call.name}' requiresConfirmation=$requiresConfirm")
            // Actual confirmation is performed inside ToolExecutor (the gate) — we just log the decision point here
            // If we are in a test without UI, the manager will auto-timeout (deny) or the executor will handle it
            confirmedCalls += call
        }
        stages += "7:confirmation_logged"

        // Stage 8: tool execution — ONLY via ToolExecutor (never directly from raw output)
        Timber.i("ToolCallingPipeline: stage 8 — tool execution of ${confirmedCalls.size} call(s) via ToolExecutor")
        stages += "8:tool_execution"
        val executed = mutableListOf<ToolCall>()
        val summaries = mutableListOf<String>()
        val results = mutableListOf<ToolResult>()
        for (call in confirmedCalls) {
            Timber.i("ToolCallingPipeline: stage 8 — executing '${call.name}' with args ${call.arguments}")
            val result = executor.execute(call) // includes permission + confirmation + timeout gates
            executed += call
            results += result
            val summary = result.summary
            val sanitizedSummary = OutputSanitizer.sanitize(summary)
            summaries += sanitizedSummary
            Timber.i("ToolCallingPipeline: stage 8 — result for '${call.name}': ${result::class.simpleName} — '${sanitizedSummary.take(80)}'")
            logger.logExecution(call.name, 0, result.isSuccess, if (result is ToolResult.Failure) result.summary else null)
            if (result is ToolResult.Failure && !result.retryable) {
                Timber.w("ToolCallingPipeline: stage 8 — '${call.name}' failed non-retryably — stopping sequential execution")
                break
            } else if (result is ToolResult.Failure) {
                Timber.w("ToolCallingPipeline: stage 8 — '${call.name}' failed retryably — stopping (higher layer handles retry)")
                break
            }
        }

        // Stage 9: tool result formatting (requirement 7 — never raw markup)
        Timber.i("ToolCallingPipeline: stage 9 — tool result formatting (${results.size} results)")
        stages += "9:result_formatting"
        val formattedResults = results.map { result ->
            // Sanitize each result's summary and ensure no tool tags leak
            var s = OutputSanitizer.sanitize(result.summary)
            // Also strip any JSON blobs that look like tool calls inside the result (injection in retrieved docs)
            s = PromptInjectionDetector.sanitizeRetrievedDocument(s)
            s
        }
        stages += "9:formatted(${formattedResults.size})"

        // Stage 10: final assistant response — sanitized, no raw markup, no debug text
        val combinedToolOutput = if (formattedResults.isNotEmpty()) {
            "Tool results:\n" + formattedResults.joinToString("\n") + "\n\n"
        } else ""
        // The final response is the sanitized model output plus tool summaries — but we must strip any tool markup that the model may have included
        val rawFinal = combinedToolOutput + OutputSanitizer.sanitize(modelOutput)
        val finalResponse = sanitizeFinalResponse(rawFinal)
        Timber.i("ToolCallingPipeline: stage 10 — final assistant response (${finalResponse.length} chars, preview='${finalResponse.take(80)}')")
        stages += "10:final_response"
        Timber.i("ToolCallingPipeline: === PIPELINE END ===")

        return PipelineRunResult(
            finalResponse = finalResponse,
            toolSummaries = formattedResults,
            executedCalls = executed,
            rawToolMarkupStripped = finalResponse != rawFinal || rawFinal != modelOutput,
            stages = stages
        )
    }

    private fun sanitizeFinalResponse(text: String): String {
        var s = OutputSanitizer.sanitize(text)
        // Extra safety: strip any remaining JSON tool blobs that survived sanitizer (e.g. {"tool":"x",...})
        // This ensures UI never shows raw JSON tool calls
        s = s.replace(Regex("""\{\s*"(?:tool|name|function)"\s*:\s*".*?"\s*,.*?}""", RegexOption.DOT_MATCHES_ALL), "").trim()
        // Strip any remaining <tool_call> fragments that sanitizer may have missed in edge cases
        s = s.replace(Regex("""<\s*tool_call[^>]*>.*?</\s*tool_call\s*>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "").trim()
        return s.ifBlank { "I couldn't generate a response. Please try again." }
    }

    private fun heuristicFallback(userMessage: String, specs: List<io.androllm.core.tools.api.ToolSpec>): List<ToolCall> {
        if (userMessage.isBlank() || specs.isEmpty()) return emptyList()
        val lower = userMessage.lowercase()
        val specsByName = specs.associateBy { it.name }

        // Calculator heuristic
        if (specsByName.containsKey("calculate") && (lower.contains("calculat") || Regex("""\d+\s*[+\-*/x×÷^%]\s*\d+""").containsMatchIn(userMessage))) {
            extractMathExpressionFromUser(userMessage)?.let { expr ->
                if (expr.isNotBlank()) {
                    Timber.i("ToolCallingPipeline: heuristicFallback — calculator with expression '$expr'")
                    return listOf(ToolCall(id = "call_calculate_heuristic_0", name = "calculate", arguments = buildJsonObject { put("expression", expr) }))
                }
            }
        }
        // Web search
        if (specsByName.containsKey("search_web") && (lower.contains("search") || lower.contains("look up") || lower.contains("google"))) {
            extractSearchQueryFromUser(userMessage)?.let { q ->
                if (q.isNotBlank()) {
                    Timber.i("ToolCallingPipeline: heuristicFallback — search_web with query '$q'")
                    return listOf(ToolCall(id = "call_search_web_heuristic_0", name = "search_web", arguments = buildJsonObject { put("query", q) }))
                }
            }
        }
        // Weather
        if (specsByName.containsKey("get_weather") && (lower.contains("weather") || lower.contains("forecast"))) {
            val loc = extractWeatherLocationFromUser(userMessage) ?: "Current"
            Timber.i("ToolCallingPipeline: heuristicFallback — get_weather with location '$loc'")
            return listOf(ToolCall(id = "call_get_weather_heuristic_0", name = "get_weather", arguments = buildJsonObject { put("location", loc) }))
        }
        return emptyList()
    }

    private fun extractMathExpressionFromUser(text: String): String? {
        val afterKeyword = Regex("""(?:evaluate|calculate|computed?|solve|what\s+is|is)\s*[:\-]?\s*([0-9][0-9\s\.\+\-\*\/\(\)\^%xX×÷]*[0-9\)])""", RegexOption.IGNORE_CASE).find(text)
        if (afterKeyword != null) {
            var expr = afterKeyword.groupValues[1].trim().trimEnd('.', ',', '!', '?')
            expr = expr.replace(Regex("""[xX×]"""), "*").replace("÷", "/").replace(Regex("""\s+"""), "")
            if (expr.isNotBlank() && expr.any { it.isDigit() }) return expr
        }
        val m = Regex("""(\d[\d\s\.\+\-\*\/\(\)\^%]*\d)""").find(text)
        if (m != null) {
            var expr = m.groupValues[1].trim().replace(Regex("""\s+"""), "")
            expr = expr.replace(Regex("""[xX×]"""), "*").replace("÷", "/")
            if (expr.length >= 1) return expr
        }
        return null
    }

    private fun extractSearchQueryFromUser(text: String): String? {
        val patterns = listOf(
            Regex("""search\s+for\s+(.+?)(?:\.|$|and|then)""", RegexOption.IGNORE_CASE),
            Regex("""look\s+up\s+(.+?)(?:\.|$|and|then)""", RegexOption.IGNORE_CASE),
            Regex("""google\s+(.+?)(?:\.|$|and|then)""", RegexOption.IGNORE_CASE),
            Regex("""search\s+(.+?)(?:\.|$|and|then)""", RegexOption.IGNORE_CASE)
        )
        for (pat in patterns) {
            val mm = pat.find(text)
            if (mm != null) {
                var q = mm.groupValues[1].trim().trimEnd('.', ',', '!', '?', '"', '\'').trim()
                q = q.replace(Regex("""^["']|["']$"""), "").trim()
                if (q.length >= 2) return q
            }
        }
        return null
    }

    private fun extractWeatherLocationFromUser(text: String): String? {
        val pat = Regex("""weather\s+(?:in|for|at)\s+([A-Za-z][A-Za-z\s\-]+)""", RegexOption.IGNORE_CASE).find(text)
        if (pat != null) {
            var loc = pat.groupValues[1].trim().trimEnd('.', ',', '!', '?')
            loc = loc.split(Regex("""\b(and|then|today|tomorrow)\b""", RegexOption.IGNORE_CASE))[0].trim()
            if (loc.length in 2..40) return loc
        }
        return null
    }
}
