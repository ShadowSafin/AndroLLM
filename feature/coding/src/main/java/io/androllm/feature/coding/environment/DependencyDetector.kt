package io.androllm.feature.coding.environment

/**
 * A runtime/toolchain the agent needs but that is not installed. Drives the
 * marketplace auto-install prompt and the post-install retry.
 *
 * @param addonId marketplace addon to install (e.g. "nodejs").
 * @param command the executable that triggered the detection (e.g. "npm").
 * @param reason human-readable explanation shown in the prompt.
 */
data class MissingDependency(
    val addonId: String,
    val command: String,
    val reason: String
)

/**
 * Maps an executable to the marketplace addons that provide it, including
 * dependency chains (e.g. `pnpm` needs BOTH nodejs and pnpm; `gradlew` needs
 * java). Detection returns the FIRST requirement that is not installed so the
 * user installs the foundation (nodejs) before the dependent (pnpm).
 *
 * Core shell applets (ls, cat, grep, find, sed, mkdir, cp, mv, echo, touch)
 * come from the base Linux userland (toybox on Android) and are always present,
 * so they never produce a [MissingDependency].
 */
object DependencyDetector {

    /** executable → ordered addon requirements (install order). */
    private val REQUIREMENTS: Map<String, List<String>> = mapOf(
        // Node.js family
        "node" to listOf("nodejs"),
        "nodejs" to listOf("nodejs"),
        "npm" to listOf("nodejs"),
        "npx" to listOf("nodejs"),
        "pnpm" to listOf("nodejs", "pnpm"),
        "yarn" to listOf("nodejs", "yarn"),
        // Python
        "python" to listOf("python"),
        "python3" to listOf("python"),
        "pip" to listOf("python"),
        "pip3" to listOf("python"),
        // Git
        "git" to listOf("git"),
        // JVM
        "java" to listOf("java"),
        "javac" to listOf("java"),
        "gradle" to listOf("java", "gradle"),
        "gradlew" to listOf("java"),
        "./gradlew" to listOf("java"),
        // Go
        "go" to listOf("go"),
        "gofmt" to listOf("go"),
        // Rust
        "cargo" to listOf("rust"),
        "rustc" to listOf("rust"),
        "rustup" to listOf("rust"),
        // Build helpers
        "make" to listOf("build-tools"),
        "cmake" to listOf("build-tools"),
        "ninja" to listOf("build-tools")
    )

    /** Executables provided by the base userland — never "missing". */
    private val BASE_COMMANDS = setOf(
        "ls", "cat", "grep", "find", "sed", "awk", "echo", "touch", "mkdir",
        "rm", "cp", "mv", "pwd", "cd", "head", "tail", "wc", "sort", "uniq",
        "cut", "tr", "xargs", "tee", "diff", "sh", "bash", "env", "which",
        "chmod", "date", "uname", "basename", "dirname", "test", "printf", "tar"
    )

    /** Extracts the executable (first token, stripped of path/`sudo`). */
    fun executableOf(command: String): String {
        val tokens = command.trim().split(Regex("\\s+"))
        var idx = 0
        // Skip leading env assignments (FOO=bar cmd) and sudo.
        while (idx < tokens.size) {
            val t = tokens[idx]
            if (t.contains('=') && !t.startsWith("-")) { idx++; continue }
            if (t == "sudo") { idx++; continue }
            break
        }
        val raw = tokens.getOrNull(idx).orEmpty()
        return raw.substringAfterLast('/').ifBlank { raw }
    }

    /**
     * Returns the first uninstalled addon required to run [command], or null
     * when the command's requirements are satisfied (or unknown/base).
     */
    fun detectMissing(command: String, installedAddons: Set<String>): MissingDependency? {
        val exe = executableOf(command)
        if (exe.isEmpty()) return null
        if (exe in BASE_COMMANDS) return null
        val requirements = REQUIREMENTS[exe] ?: return null
        for (addonId in requirements) {
            if (addonId !in installedAddons) {
                return MissingDependency(
                    addonId = addonId,
                    command = exe,
                    reason = "'$exe' needs the '${addonId}' addon, which is not installed."
                )
            }
        }
        return null
    }

    /** True when [command] is a recognized runtime command (known to the detector). */
    fun isKnownRuntimeCommand(command: String): Boolean {
        val exe = executableOf(command)
        return exe in REQUIREMENTS || exe in BASE_COMMANDS
    }
}
