package io.androllm.feature.coding.environment

/**
 * Raw outcome of one shell command. Output is preserved verbatim — stdout and
 * stderr are NOT stripped, summarized, or sanitized, per the coding-agent
 * contract (build logs, warnings, errors and progress must reach the user and
 * the model exactly as the toolchain emitted them).
 *
 * @param command the command line that was executed.
 * @param exitCode process exit code ([EXIT_CANCELLED] when cancelled).
 * @param stdout raw standard output.
 * @param stderr raw standard error.
 * @param cancelled true when the run was aborted by the user/agent.
 * @param durationMs wall-clock duration.
 * @param workingDir the cwd the command ran in.
 * @param missingDependency set when the command never ran because a required
 *   runtime addon is absent (drives the auto-install prompt).
 */
data class CommandResult(
    val command: String,
    val exitCode: Int,
    val stdout: String = "",
    val stderr: String = "",
    val cancelled: Boolean = false,
    val durationMs: Long = 0L,
    val workingDir: String = "",
    val missingDependency: MissingDependency? = null
) {
    val isSuccess: Boolean get() = !cancelled && exitCode == 0 && missingDependency == null

    /** Combined raw output in execution order (stdout then stderr). */
    val combinedOutput: String
        get() = buildString {
            if (stdout.isNotEmpty()) append(stdout)
            if (stderr.isNotEmpty()) {
                if (isNotEmpty()) append('\n')
                append(stderr)
            }
        }

    /** A compact rendering for the terminal panel / tool feedback. */
    fun render(): String = buildString {
        append("$ ").append(command).append('\n')
        if (combinedOutput.isNotEmpty()) append(combinedOutput).append('\n')
        append(
            when {
                cancelled -> "[cancelled]"
                missingDependency != null -> "[missing: ${missingDependency.addonId}]"
                else -> "[exit $exitCode]"
            }
        )
    }

    companion object {
        const val EXIT_CANCELLED = 130
        const val EXIT_BLOCKED = 126
        const val EXIT_MISSING_DEP = 127
    }
}
