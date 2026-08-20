package io.androllm.core.tools.executor

import io.androllm.core.tools.api.ToolCall
import io.androllm.core.tools.api.ToolResult
import io.androllm.core.tools.confirmation.ToolConfirmationManager
import io.androllm.core.tools.confirmation.buildConfirmation
import io.androllm.core.tools.registry.ToolRegistry
import io.androllm.core.tools.settings.AutomationSettingsStore
import io.androllm.core.tools.trace.ToolExecutionTraceStore
import io.androllm.core.tools.validation.PromptInjectionDetector
import io.androllm.core.tools.validation.ToolCallValidator
import io.androllm.core.tools.validation.ToolExecutionLogger
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
    private val logger: ToolExecutionLogger = ToolExecutionLogger()
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
        when (val validation = validator.validate(call)) {
            is ValidationResult.Invalid -> {
                logger.logValidation(call.name, validation)
                // Do not execute anything — remove invalid call per recovery policy
                val isMissingParam = validation.errors.any { "Missing required" in it }
                val userMessage = if (isMissingParam) {
                    "The tool '${call.name}' call is missing required parameters: ${validation.errors.joinToString("; ")}. Ask the user for the missing information."
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

        // ── Confirmation gate ───────────────────────────────────────────────
        if (settings.shouldConfirm(spec.requiresConfirmation)) {
            val approved = confirmationManager.awaitDecision(buildConfirmation(call, spec))
            if (!approved) {
                Timber.i("ToolExecutor: '${spec.name}' declined by user")
                return finish(
                    ToolResult.Failure("The user declined the action.", retryable = false),
                    error = "declined by user"
                )
            }
        }

        // ── Execute with timeout ────────────────────────────────────────────
        // Multi-step tools (ui_run) can opt into a longer budget via the spec.
        val timeoutMs = spec.executionTimeoutMs ?: EXECUTION_TIMEOUT_MS
        return try {
            finish(withTimeout(timeoutMs) { tool.execute(call.arguments) })
        } catch (e: TimeoutCancellationException) {
            Timber.w("ToolExecutor: '${spec.name}' timed out")
            finish(
                ToolResult.Failure("The '${spec.name}' tool timed out after ${timeoutMs / 1000}s."),
                error = e.message
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "ToolExecutor: '${spec.name}' crashed")
            finish(
                ToolResult.Failure("The '${spec.name}' tool failed: ${e.message ?: e.javaClass.simpleName}"),
                error = e.message
            )
        }
    }

    companion object {
        /** Hard cap for a single tool execution (network tools may be slow). */
        const val EXECUTION_TIMEOUT_MS = 20_000L
    }
}
