package io.androllm.feature.coding.task

/**
 * A safe, one-shot fix the executor can apply automatically when a command
 * fails. The plan MUST be:
 *  - safe to run without user approval (no deletes outside the workspace, no
 *    shells from the internet, no force-pushes);
 *  - likely to actually fix the classified failure;
 *  - bounded in time / side effects.
 */
data class RecoveryPlan(
    /** A short, human-readable category — "npm_peer_deps", "vite_port"… */
    val category: String,
    /** The recovery command to run INSTEAD of the original. */
    val command: String,
    /** Working directory to use (defaults to the original command's working dir). */
    val workingDir: String? = null,
    /** Short explanation of what the recovery will do — shown to the user. */
    val rationale: String
)

/**
 * Pure, synchronous classifier that maps a failed command + its output to a
 * safe retry plan. Returns null when the failure is not auto-recoverable —
 * the executor will then surface the error to the agent for human review.
 *
 * Every rule here is conservative: when in doubt, returns null.
 */
object CommandRecovery {

    private val RULES: List<(String, String) -> RecoveryPlan?> = listOf(
        ::npmPeerDeps,
        ::npmLockfileOutdated,
        ::npmReinstallNodeModules,
        ::vitePortConflict,
        ::pythonMissingModule,
        ::gradleDaemonStuck,
        ::typescriptCache
    )

    fun suggest(command: String, outputTail: String): RecoveryPlan? {
        for (rule in RULES) {
            val plan = runCatching { rule(command, outputTail) }.getOrNull()
            if (plan != null) return plan
        }
        return null
    }
}

// ── npm / pnpm / yarn ──────────────────────────────────────────────────────

private fun npmPeerDeps(command: String, output: String): RecoveryPlan? {
    if (!isNodeCommand(command)) return null
    val matches = output.contains(Regex("ERESOLVE", RegexOption.IGNORE_CASE)) ||
        output.contains(Regex("PEER_DEP", RegexOption.IGNORE_CASE)) ||
        output.contains(Regex("peer dep", RegexOption.IGNORE_CASE))
    if (!matches) return null
    val pkg = packageManager(command)
    val flag = when (pkg) {
        "pnpm" -> "--no-strict-peer-dependencies"
        "yarn" -> "--ignore-engines"
        else -> "--legacy-peer-deps"
    }
    return RecoveryPlan(
        category = "${pkg}_peer_deps",
        command = appendFlag(command, flag),
        rationale = "Dependency conflict — retrying with relaxed peer-dependency rules."
    )
}

private fun npmLockfileOutdated(command: String, output: String): RecoveryPlan? {
    if (!isNodeCommand(command)) return null
    val matches = output.contains(Regex("lockfile", RegexOption.IGNORE_CASE)) || output.contains("EBADENGINE")
    if (!matches) return null
    return RecoveryPlan(
        category = "lockfile_update",
        command = command.replace(Regex("\\binstall\\b"), "install --no-audit --no-fund"),
        rationale = "Outdated lockfile — retrying with a fresh resolution."
    )
}

private fun npmReinstallNodeModules(command: String, output: String): RecoveryPlan? {
    if (!isNodeCommand(command)) return null
    val matches = output.contains("Cannot find module") ||
        output.contains("ELIFECYCLE") ||
        output.contains("ELSPIDER")
    if (!matches) return null
    val pkg = packageManager(command)
    val cleanup = when (pkg) {
        "pnpm" -> "rm -rf node_modules pnpm-lock.yaml"
        "yarn" -> "rm -rf node_modules yarn.lock"
        else -> "rm -rf node_modules package-lock.json"
    }
    return RecoveryPlan(
        category = "reinstall",
        command = "$cleanup && $command",
        rationale = "Module / lifecycle error — wiping node_modules and reinstalling from scratch."
    )
}

// ── vite / ports ──────────────────────────────────────────────────────────

private fun vitePortConflict(command: String, output: String): RecoveryPlan? {
    if (!command.contains("vite", ignoreCase = true)) return null
    if (!output.contains("EADDRINUSE")) return null
    val base = command.replace(Regex("\\s*--port\\s+\\d+"), "").trim()
    return RecoveryPlan(
        category = "vite_port",
        command = "$base --port 5180 --strictPort false",
        rationale = "Vite port conflict — retrying on an alternate port."
    )
}

// ── python ────────────────────────────────────────────────────────────────

private fun pythonMissingModule(command: String, output: String): RecoveryPlan? {
    if (!command.contains("python", ignoreCase = true)) return null
    val match = Regex("""ModuleNotFoundError: No module named ['"]([^'"]+)['"]""").find(output) ?: return null
    val module = match.groupValues[1]
    return RecoveryPlan(
        category = "pip_install",
        command = "pip install $module",
        rationale = "Missing Python module '$module' — installing via pip."
    )
}

// ── gradle ────────────────────────────────────────────────────────────────

private fun gradleDaemonStuck(command: String, output: String): RecoveryPlan? {
    if (!command.contains(Regex("""gradle|gradlew|.\/gradlew""", RegexOption.IGNORE_CASE))) return null
    val matches = output.contains("Could not connect to the daemon", ignoreCase = true) ||
        output.contains("daemon has stopped", ignoreCase = true)
    if (!matches) return null
    return RecoveryPlan(
        category = "gradle_daemon",
        command = "./gradlew --stop; $command",
        rationale = "Gradle daemon stuck — restarting it before retrying."
    )
}

// ── typescript ────────────────────────────────────────────────────────────

private fun typescriptCache(command: String, output: String): RecoveryPlan? {
    if (!command.contains(Regex("tsc|typescript", RegexOption.IGNORE_CASE))) return null
    val matches = output.contains("TS2307") || output.contains("Cannot find module")
    if (!matches) return null
    return RecoveryPlan(
        category = "ts_build",
        command = "rm -rf node_modules/.cache; $command",
        rationale = "TypeScript build cache stale — clearing it before retrying."
    )
}

// ── helpers ───────────────────────────────────────────────────────────────

private fun isNodeCommand(command: String): Boolean =
    command.contains(Regex("\\b(npm|pnpm|yarn|bun)\\b"))

private fun packageManager(command: String): String = when {
    command.contains(Regex("\\bbun\\b")) -> "bun"
    command.contains(Regex("\\bpnpm\\b")) -> "pnpm"
    command.contains(Regex("\\byarn\\b")) -> "yarn"
    else -> "npm"
}

private fun appendFlag(command: String, flag: String): String {
    val trimmed = command.trim().trimEnd(';', '&')
    return if (trimmed.endsWith(flag)) trimmed else "$trimmed $flag"
}
