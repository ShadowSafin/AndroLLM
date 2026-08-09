package io.androllm.core.tools.executor

import io.androllm.core.tools.api.ToolCall
import io.androllm.core.tools.api.ToolResult
import io.androllm.core.tools.confirmation.ToolConfirmationManager
import io.androllm.core.tools.confirmation.buildConfirmation
import io.androllm.core.tools.registry.ToolRegistry
import io.androllm.core.tools.settings.AutomationSettingsStore
import io.androllm.core.tools.trace.ToolExecutionTraceStore
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
    private val traceStore: ToolExecutionTraceStore
) {

    /**
     * Executes [call] under the gates. Never throws; every outcome is a
     * [ToolResult] that can be fed back to the LLM.
     */
    suspend fun execute(call: ToolCall): ToolResult {
        val startedAt = System.currentTimeMillis()
        val args = call.arguments.toString().take(300)
        fun finish(result: ToolResult, error: String? = null): ToolResult {
            traceStore.record(
                toolName = call.name,
                arguments = args,
                status = result.statusLabel,
                result = result.summary,
                error = error,
                durationMs = System.currentTimeMillis() - startedAt
            )
            return result
        }

        val tool = registry.get(call.name)
            ?: return finish(ToolResult.Failure("Unknown tool '${call.name}'. Ask the user to rephrase."))
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
