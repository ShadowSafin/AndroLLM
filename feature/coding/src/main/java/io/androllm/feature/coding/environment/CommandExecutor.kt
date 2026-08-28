package io.androllm.feature.coding.environment

import io.androllm.feature.coding.workspace.WorkspaceSafety
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull

/** Decides whether a risky command may run. Production wires this to the UI. */
fun interface ConfirmationGate {
    suspend fun confirm(command: String, risk: WorkspaceSafety.RiskLevel): Boolean
}

/** A gate that approves everything (used by tests / "never confirm" mode). */
object AutoApproveGate : ConfirmationGate {
    override suspend fun confirm(command: String, risk: WorkspaceSafety.RiskLevel): Boolean = true
}

/** A gate that declines everything (used to test the decline path). */
object AutoDeclineGate : ConfirmationGate {
    override suspend fun confirm(command: String, risk: WorkspaceSafety.RiskLevel): Boolean = false
}

/**
 * Executes shell commands for the coding agent inside the workspace sandbox.
 *
 * Pipeline for every [execute] call:
 *  1. **Risk classification** ([WorkspaceSafety.classifyCommand]) — BLOCKED
 *     commands are refused outright; NEEDS_CONFIRMATION commands suspend on the
 *     [ConfirmationGate] and only run if approved.
 *  2. **Working-directory containment** — the cwd is resolved inside the
 *     workspace; a path that escapes is clamped back to the workspace root.
 *  3. **Missing-dependency detection** ([DependencyDetector]) — when the needed
 *     runtime addon is absent the command is NOT run; the result carries the
 *     [MissingDependency] so the caller can prompt to install + retry.
 *  4. **Execution** via the [ShellBackend] with a timeout + cancellation.
 *  5. **History** — every result (raw output preserved) is appended to a bounded
 *     stream the terminal panel renders.
 *
 * The executor never throws for a failed command — failures are data
 * ([CommandResult]) so the agent can read the error and recover.
 */
class CommandExecutor(
    private val workspaceRoot: File,
    private val backend: ShellBackend,
    private val installedAddons: () -> Set<String> = { emptySet() },
    private val confirmationGate: ConfirmationGate = AutoApproveGate,
    private val defaultTimeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val historyLimit: Int = 200,
    private val backgroundServices: BackgroundServiceManager? = null
) {
    private val _history = MutableStateFlow<List<CommandResult>>(emptyList())
    val history: StateFlow<List<CommandResult>> = _history.asStateFlow()

    private val _running = MutableStateFlow<String?>(null)
    /** The command currently executing (null when idle) — drives the cancel button. */
    val running: StateFlow<String?> = _running.asStateFlow()

    @Volatile
    private var runJob: Job? = null

    /**
     * Runs [command] in the workspace (optionally in [workingDir] relative to
     * the workspace root). Returns a [CommandResult]; never throws for command
     * failure. [timeoutMs] overrides the default command timeout.
     *
     * When [onOutput] is non-null, output lines are streamed to it AS THEY ARE
     * PRODUCED (real-time terminal feedback in the UI); the returned result
     * still contains the complete raw output.
     */
    suspend fun execute(
        command: String,
        workingDir: String = "",
        env: Map<String, String> = emptyMap(),
        timeoutMs: Long = defaultTimeoutMs,
        onOutput: ((String) -> Unit)? = null
    ): CommandResult {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) {
            return record(CommandResult(trimmed, 0, stdout = "", workingDir = workspaceRoot.path))
        }

        // 1. Risk gate.
        when (WorkspaceSafety.classifyCommand(trimmed)) {
            WorkspaceSafety.RiskLevel.BLOCKED -> {
                return record(
                    CommandResult(
                        command = trimmed,
                        exitCode = CommandResult.EXIT_BLOCKED,
                        stderr = "Blocked by workspace safety policy: '$trimmed' can damage the device or escape the sandbox.",
                        workingDir = workspaceRoot.path
                    )
                )
            }
            WorkspaceSafety.RiskLevel.NEEDS_CONFIRMATION -> {
                val approved = confirmationGate.confirm(trimmed, WorkspaceSafety.RiskLevel.NEEDS_CONFIRMATION)
                if (!approved) {
                    return record(
                        CommandResult(
                            command = trimmed,
                            exitCode = CommandResult.EXIT_BLOCKED,
                            stderr = "Declined by user — '$trimmed' needs approval because it is destructive.",
                            workingDir = workspaceRoot.path
                        )
                    )
                }
            }
            WorkspaceSafety.RiskLevel.SAFE -> Unit
        }

        // 2. Contain the working directory inside the workspace.
        val cwd = WorkspaceSafety.resolveWithin(workspaceRoot, workingDir) ?: workspaceRoot.canonicalFile

        // 3. Missing-dependency detection (before spawning anything).
        val missing = DependencyDetector.detectMissing(trimmed, installedAddons())
        if (missing != null) {
            return record(
                CommandResult(
                    command = trimmed,
                    exitCode = CommandResult.EXIT_MISSING_DEP,
                    stderr = missing.reason,
                    workingDir = cwd.path,
                    missingDependency = missing
                )
            )
        }

        // 4. Execute with timeout + cancellation.
        _running.value = trimmed
        val result = try {
            val timed = withTimeoutOrNull(timeoutMs) {
                if (onOutput != null) {
                    backend.runStreaming(trimmed, cwd, env, onOutput)
                } else {
                    backend.run(trimmed, cwd, env)
                }
            }
            if (timed == null) {
                // Timeout: kill the child so it cannot leak, then report.
                backend.cancelCurrent()
                CommandResult(
                    command = trimmed,
                    exitCode = 124,
                    stderr = "Command timed out after ${timeoutMs}ms.",
                    cancelled = true,
                    workingDir = cwd.path
                )
            } else {
                timed
            }
        } catch (ce: CancellationException) {
            backend.cancelCurrent()
            CommandResult(
                command = trimmed,
                exitCode = CommandResult.EXIT_CANCELLED,
                stderr = "Command cancelled.",
                cancelled = true,
                workingDir = cwd.path
            )
        } catch (t: Throwable) {
            CommandResult(
                command = trimmed,
                exitCode = 1,
                stderr = "Execution error: ${t.message}",
                workingDir = cwd.path
            )
        } finally {
            _running.value = null
        }
        return record(result)
    }

    /**
     * Starts [command] as a detached background service (dev servers, watchers).
     * Applies the same risk / containment / missing-dependency gates as
     * [execute], then hands the command to the [BackgroundServiceManager],
     * which spawns it detached and reports the detected port + access URLs.
     */
    suspend fun executeBackground(
        command: String,
        workingDir: String = "",
        env: Map<String, String> = emptyMap()
    ): BackgroundStartOutcome {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return BackgroundStartOutcome.Failed("Empty command.")

        val services = backgroundServices
            ?: return BackgroundStartOutcome.Failed(
                "Background services are not available in this environment. " +
                    "Run the command in the foreground with a timeout instead."
            )

        when (WorkspaceSafety.classifyCommand(trimmed)) {
            WorkspaceSafety.RiskLevel.BLOCKED -> return BackgroundStartOutcome.Failed(
                "Blocked by workspace safety policy: '$trimmed' can damage the device or escape the sandbox."
            )
            WorkspaceSafety.RiskLevel.NEEDS_CONFIRMATION -> {
                val approved = confirmationGate.confirm(trimmed, WorkspaceSafety.RiskLevel.NEEDS_CONFIRMATION)
                if (!approved) {
                    return BackgroundStartOutcome.Failed(
                        "Declined by user — '$trimmed' needs approval because it is destructive."
                    )
                }
            }
            WorkspaceSafety.RiskLevel.SAFE -> Unit
        }

        val cwd = WorkspaceSafety.resolveWithin(workspaceRoot, workingDir) ?: workspaceRoot.canonicalFile

        val missing = DependencyDetector.detectMissing(trimmed, installedAddons())
        if (missing != null) {
            return BackgroundStartOutcome.Failed(
                "Cannot run '$trimmed': ${missing.reason} Install the '${missing.addonId}' addon, then retry.",
                missingAddonId = missing.addonId
            )
        }

        return services.start(trimmed, cwd, env)
    }

    /** Cancels the currently running command, if any. */
    fun cancel() {
        backend.cancelCurrent()
        runJob?.cancel()
        _running.value = null
    }

    /** Clears the terminal history. */
    fun clearHistory() {
        _history.value = emptyList()
    }

    private fun record(result: CommandResult): CommandResult {
        _history.value = (_history.value + result).takeLast(historyLimit)
        return result
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 120_000L
    }
}
