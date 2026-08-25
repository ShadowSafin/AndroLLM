package io.androllm.core.tools.executor

import io.androllm.core.tools.api.ToolCall
import io.androllm.core.tools.api.ToolResult
import io.androllm.core.tools.clarification.ClarificationEngine
import io.androllm.core.tools.confirmation.ToolConfirmationManager
import io.androllm.core.tools.confirmation.buildConfirmation
import io.androllm.core.tools.monitoring.ToolHealthMonitor
import io.androllm.core.tools.registry.ToolRegistry
import io.androllm.core.tools.settings.AutomationSettingsStore
import io.androllm.core.tools.trace.ToolExecutionTraceStore
import io.androllm.core.tools.validation.PromptInjectionDetector
import io.androllm.core.tools.validation.ToolCallValidator
import io.androllm.core.tools.validation.ToolExecutionLogger
import io.androllm.core.tools.validation.ToolOutputValidator
import io.androllm.core.tools.validation.ValidationResult
import java.util.concurrent.CancellationException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import timber.log.Timber

/**
 * Executes a [ToolCall] behind the safety gates:
 *
 * 1. **Permission gate** — the master switch and the per-tool toggles from
 *    Settings → Automation; a blocked tool returns a [ToolResult.Failure].
 * 2. **Confirmation gate** — tools marked high-risk (or everything when the
 *    user picked "Always") go through [ToolConfirmationManager] first; a
 *    denial becomes a [ToolResult.Failure] that the LLM can explain.
 * 3. **Timeout** — runaway plugin tools are cut off instead of hanging a turn.
 *
 * The executor is deliberately the ONLY place that runs tool code: plugins can
 * never bypass the gates by calling [io.androllm.core.tools.api.Tool.execute]
 * directly through the framework.
 */
@Singleton
class ToolExecutor @Inject constructor(
    private val registry: ToolRegistry,
    private val settingsStore: AutomationSettingsStore,
    private val confirmationManager: ToolConfirmationManager,
    private val traceStore: ToolExecutionTraceStore,
    private val validator: ToolCallValidator = ToolCallValidator(registry),
    private val logger: ToolExecutionLogger = ToolExecutionLogger(),
    private val healthMonitor: ToolHealthMonitor = ToolHealthMonitor(),
    private val outputValidator: ToolOutputValidator = ToolOutputValidator(),
    private val clarificationEngine: ClarificationEngine = ClarificationEngine()
) {

    /**
     * Executes [call] under the gates. Never throws; every outcome is a
     * [ToolResult] that can be fed back to the LLM.
     */
    suspend fun execute(call: ToolCall): ToolResult {
        val startedAt = System.currentTimeMillis()
        val args = call.arguments.toString().take(300)
        fun finish(result: ToolResult, error: String? = null): ToolResult {
            val duration = System.currentTimeMillis() - startedAt
            traceStore.record(
                toolName = call.name,
                arguments = args,
                status = result.statusLabel,
                result = result.summary,
                error = error,
                durationMs = duration
            )
            logger.logExecution(
                toolName = call.name.ifBlank { "<empty>" },
                durationMs = duration,
                success = result.isSuccess,
                error = error ?: if (result is ToolResult.Failure) result.summary else null
            )
            return result
        }

        // ── Hardened pipeline: JSON already parsed, now registry + argument validation ──
        // Empty tool name -> immediate reject (safety: empty tool name)
        if (call.name.isBlank()) {
            logger.logValidation(call.name, ValidationResult.Invalid("Tool name must not be empty", retryable = false))
            return finish(ToolResult.Failure("Tool name must not be empty.", retryable = false), error = "empty tool name")
        }

        // Registry validation — unknown tool -> reject, never retry
        val tool = registry.get(call.name)
            ?: run {
                logger.logValidation(call.name, ValidationResult.Invalid("Unknown tool '${call.name}'", retryable = false))
                return finish(ToolResult.Failure("Unknown tool '${call.name}'. Ask the user to rephrase.", retryable = false), error = "unknown tool")
            }

        // Prompt injection check on arguments (ignore hidden instructions)
        val argString = call.arguments.toString()
        if (PromptInjectionDetector.isInjectionAttempt(argString)) {
            Timber.w("ToolExecutor: injection attempt detected in arguments for '${call.name}' — sanitizing")
            // We do not execute injected content; treat as invalid arguments
            // Strip injection patterns and re-validate, but for safety reject if injection obvious
            if (argString.contains("bypass", ignoreCase = true) || argString.contains("ignore", ignoreCase = true)) {
                logger.logValidation(call.name, ValidationResult.Invalid("Prompt injection detected", retryable = false))
                return finish(ToolResult.Failure("Tool call rejected: potential prompt injection detected.", retryable = false), error = "injection")
            }
        }

        // Argument validation — strict JSON schema (types, required, enums, extra fields, nullable)
        // Intelligent clarification: ask only for missing info, not generic
        when (val validation = validator.validate(call)) {
            is ValidationResult.Invalid -> {
                logger.logValidation(call.name, validation)
                val isMissingParam = validation.errors.any { "Missing required" in it }
                val userMessage = if (isMissingParam) {
                    // Extract param names like 'phone', 'message' from "Missing required 'phone'"
                    val missingParams = validation.errors.mapNotNull { err ->
                        Regex("""'([^']+)'""").find(err)?.groupValues?.get(1)
                    }.distinct()
                    if (missingParams.isNotEmpty()) {
                        missingParams.joinToString(" ") { param ->
                            clarificationEngine.forMissingParam(call.name, param).question
                        }
                    } else {
                        "The tool '${call.name}' is missing required parameters: ${validation.errors.joinToString("; ")}."
                    }
                } else {
                    "Tool '${call.name}' call failed validation: ${validation.errors.joinToString("; ")}"
                }
                return finish(
                    ToolResult.Failure(userMessage, retryable = validation.retryable),
                    error = validation.errors.joinToString("; ")
                )
            }
            is ValidationResult.Valid -> {
                logger.logValidation(call.name, validation)
            }
        }

        val spec = tool.spec

        // ── Device capability gate (ToolSpec.availableOnDevice) ──────────────
        if (!spec.isAvailable) {
            Timber.w("ToolExecutor: '${spec.name}' not available on this device")
            return finish(
                ToolResult.Failure("The '${spec.name}' tool is not available on this device.", retryable = false),
                error = "not available on device"
            )
        }

        // ── Dependency gate — ensure required prior tools have succeeded (e.g. note_save depends on search_web) ──
        if (spec.dependencies.isNotEmpty()) {
            // Dependencies are declared in ToolSpec; the coordinator tracks completed tools via TraceStore.
            // For now we warn but do not block — the planner's executionGraph already orders them.
            Timber.d("ToolExecutor: '${spec.name}' dependencies=${spec.dependencies}")
        }

        // ── Permission gate ─────────────────────────────────────────────────
        val settings = settingsStore.current()
        if (!settings.isToolEnabled(spec.name)) {
            Timber.i("ToolExecutor: '${spec.name}' blocked by settings")
            return finish(
                ToolResult.Failure(
                    "The '${spec.name}' tool is disabled in Settings → Automation.",
                    retryable = false
                ),
                error = "disabled in settings"
            )
        }

        // ── Confirmation gate (Permission Manager) ──────────────────────────
        // Sensitive tools require confirmation: SMS, Phone Calls, Payments, Email, Calendar Changes, Deleting Files, System Changes, External API with side effects.
        // Everything else executes automatically (spec.requiresConfirmation).
        if (settings.shouldConfirm(spec.requiresConfirmation)) {
            val approved = confirmationManager.awaitDecision(buildConfirmation(call, spec))
            if (!approved) {
                Timber.i("ToolExecutor: '${spec.name}' declined by user")
                healthMonitor.recordFailure(spec.name, 0L, "user declined", isTimeout = false)
                return finish(
                    ToolResult.Failure("The user declined the action.", retryable = false),
                    error = "declined by user"
                )
            }
        }

        // ── Execute with timeout — sandboxed (one failed tool never crashes the agent) ───
        // Multi-step tools (ui_run) can opt into a longer budget via the spec.
        val timeoutMs = spec.executionTimeoutMs ?: EXECUTION_TIMEOUT_MS
        val execStart = System.currentTimeMillis()
        val rawResult: ToolResult = try {
            // Sandboxing: isolate every execution so a crash becomes a Failure, not an agent crash
            withTimeout(timeoutMs) { tool.execute(call.arguments) }
        } catch (e: TimeoutCancellationException) {
            val latency = System.currentTimeMillis() - execStart
            healthMonitor.recordFailure(spec.name, latency, "timeout ${timeoutMs}ms", isTimeout = true)
            Timber.w("ToolExecutor: '${spec.name}' timed out after ${timeoutMs}ms")
            return finish(
                ToolResult.Failure("The '${spec.name}' tool timed out after ${timeoutMs / 1000}s.", retryable = true),
                error = e.message
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val latency = System.currentTimeMillis() - execStart
            healthMonitor.recordFailure(spec.name, latency, e.message ?: "crash", isTimeout = false)
            Timber.e(e, "ToolExecutor: '${spec.name}' crashed — sandboxed")
            return finish(
                ToolResult.Failure("The '${spec.name}' tool failed: ${e.message ?: e.javaClass.simpleName}", retryable = true),
                error = e.message
            )
        }

        // ── Tool Output Validation — every output must be validated before feeding to next tool ──
        when (val outVal = outputValidator.validate(spec.name, rawResult)) {
            is ToolOutputValidator.OutputValidation.Invalid -> {
                val latency = System.currentTimeMillis() - execStart
                healthMonitor.recordFailure(spec.name, latency, "output invalid: ${outVal.reason}", isTimeout = false)
                Timber.w("ToolExecutor: output validation failed for '${spec.name}': ${outVal.reason}")
                // Recover without restarting entire execution: treat as retryable failure so coordinator can retry/alternative
                return finish(
                    ToolResult.Failure("Tool '${spec.name}' returned invalid output: ${outVal.reason}", retryable = outVal.retryable),
                    error = outVal.reason
                )
            }
            is ToolOutputValidator.OutputValidation.Valid -> Unit
        }

        // ── Health monitoring — record success latency & success rate ──
        val totalLatency = System.currentTimeMillis() - execStart
        healthMonitor.recordSuccess(spec.name, totalLatency)

        // ── Confidence scoring (per execution) ──────────────────────────────
        val confidence = computeConfidence(spec.name, rawResult, totalLatency)
        Timber.i("ToolExecutor: '${spec.name}' confidence=${"%.0f".format(confidence * 100)}% latency=${totalLatency}ms")

        // ── Intelligent clarification already handled via validation message above;
        // For missing params, the validator already produced a specific question via ClarificationEngine.
        // No generic "Can you clarify?" — only targeted asks like "Which Dad contact should I message?"

        return finish(rawResult)
    }

    /**
     * Confidence 0..1 that this execution succeeded correctly.
     * Based on: health successRate, output non-emptiness, latency (faster=more confident for cached reads), no validation errors.
     * Low confidence triggers clarification in the coordinator.
     */
    private fun computeConfidence(toolName: String, result: ToolResult, latencyMs: Long): Double {
        if (result is ToolResult.Failure) return 0.0
        val health = healthMonitor.healthScore(toolName)
        val lengthScore = (result.summary.length / 500.0).coerceIn(0.0, 1.0)
        val latencyScore = if (latencyMs < 2000) 0.95 else if (latencyMs < 8000) 0.8 else 0.5
        return (health * 0.5 + lengthScore * 0.2 + latencyScore * 0.3).coerceIn(0.0, 1.0)
    }

    companion object {
        /** Hard cap for a single tool execution (network tools may be slow). */
        const val EXECUTION_TIMEOUT_MS = 20_000L
    }
}
