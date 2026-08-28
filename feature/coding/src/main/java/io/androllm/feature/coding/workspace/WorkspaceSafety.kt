package io.androllm.feature.coding.workspace

import java.io.File

/**
 * Sandbox safety for the coding workspace.
 *
 * Two responsibilities:
 *  1. **Path containment** — every file the agent touches must resolve to a
 *     location INSIDE the active workspace. Any `..` traversal, absolute path
 *     or symlink escape is rejected. This is the hard boundary that keeps the
 *     agent from reading or writing unrelated device files.
 *  2. **Command risk classification** — shell commands are bucketed into
 *     [RiskLevel.SAFE] (auto-run), [RiskLevel.NEEDS_CONFIRMATION] (user must
 *     approve) and [RiskLevel.BLOCKED] (never run, even with approval).
 *
 * Both are pure functions over strings / [File]s so they are fully unit
 * testable on the JVM.
 */
object WorkspaceSafety {

    /** Risk bucket for a shell command. */
    enum class RiskLevel {
        /** Read-only / build / test commands. Run without asking. */
        SAFE,

        /** Destructive or externally-visible effects. Require explicit approval. */
        NEEDS_CONFIRMATION,

        /** Device-damaging or sandbox-escaping. Refused outright. */
        BLOCKED
    }

    // ── Path containment ─────────────────────────────────────────────────────

    /**
     * Resolves [relativePath] against [workspaceRoot], returning the target
     * [File] only when it stays inside the workspace. Returns null when the
     * path escapes (absolute path outside root, `..` traversal, or a symlink
     * that canonicalizes outside).
     *
     * A blank path resolves to the workspace root itself.
     *
     * The returned file keeps the root's own path form (lexically normalized,
     * NOT canonicalized). This matters when the workspace lives under
     * `/storage/emulated/0/...`: canonicalization resolves that symlink to
     * `/data/media/0/...`, which apps cannot open directly — so containment
     * is *checked* canonically but the file handed back for I/O preserves the
     * `/storage/...` prefix.
     */
    fun resolveWithin(workspaceRoot: File, relativePath: String): File? {
        val root = workspaceRoot.absoluteFile
        val target = if (relativePath.isBlank()) root else normalizeLexical(File(root, relativePath).path)
        return if (isWithin(root, target)) target else null
    }

    /**
     * True when [target] is [root] itself or lives underneath it. Comparison is
     * done on canonical paths so `..`, `.` and symlinks cannot smuggle a path
     * past the check.
     */
    fun isWithin(root: File, target: File): Boolean {
        val rootPath = root.canonicalPath
        val targetPath = target.canonicalPath
        if (targetPath == rootPath) return true
        return targetPath.startsWith(rootPath + File.separator)
    }

    /**
     * Validates an absolute [candidate] path against the workspace [root]. Used
     * when a tool receives an already-absolute path from the model. Returns the
     * contained file (in the original path form, see [resolveWithin]) or null
     * when it escapes.
     */
    fun absoluteWithin(root: File, candidate: String): File? {
        val target = normalizeLexical(File(candidate).path)
        return if (isWithin(root.absoluteFile, target)) target else null
    }

    /**
     * Resolves a model-supplied path that may be relative OR absolute, always
     * clamping the result into the workspace. Absolute paths outside the
     * workspace are rejected (not silently re-rooted) so the agent gets a clear
     * error instead of writing to the wrong place.
     */
    fun resolveAny(workspaceRoot: File, path: String): File? {
        val trimmed = path.trim()
        if (trimmed.isBlank()) return workspaceRoot.absoluteFile
        val asFile = File(trimmed)
        return if (asFile.isAbsolute) {
            absoluteWithin(workspaceRoot, trimmed)
        } else {
            resolveWithin(workspaceRoot, trimmed)
        }
    }

    /**
     * Normalizes a path lexically (without touching the filesystem): drops `.`
     * segments and resolves `..` against the preceding segment. Unlike
     * [File.getCanonicalFile] it does NOT resolve symlinks, so `/storage/...`
     * paths keep their user-visible form. Any residual escape (e.g. `..` above
     * the filesystem root) is still caught by the canonical containment check.
     */
    private fun normalizeLexical(path: String): File =
        File(java.nio.file.Paths.get(path).normalize().toString())

    // ── Command risk classification ──────────────────────────────────────────

    // Device-damaging / sandbox-escaping patterns. Never run.
    private val BLOCKED_PATTERNS = listOf(
        Regex("""\brm\s+(-[a-zA-Z]*[rRf][a-zA-Z]*\s+)+/(\s|$)"""),   // rm -rf /
        Regex("""\brm\s+(-[a-zA-Z]*\s+)*(/|~|${'$'}HOME)(\s|$)"""),   // rm on root/home
        Regex("""\bmkfs\b"""),
        Regex("""\bdd\s+.*of=/dev/"""),
        Regex("""\b(shutdown|reboot|halt|poweroff|init\s+0|init\s+6)\b"""),
        Regex(""":\(\)\s*\{\s*:\s*\|\s*:\s*&\s*\}\s*;\s*:"""),          // fork bomb
        Regex("""\bchmod\s+(-[a-zA-Z]+\s+)*777\s+/(\s|$)"""),
        Regex("""\bchown\s+.*\s+/(\s|$)"""),
        Regex("""\bmount\b"""),
        Regex("""\bumount\b"""),
        Regex("""\bsu\b"""),
        Regex("""\b>\s*/dev/(sd|mmc|nvme|block)"""),
        Regex("""\brm\s+-[a-zA-Z]*\s+(/system|/vendor|/data/data|/proc|/sys)\b"""),
        Regex("""\bcurl\b.*\|\s*(ba)?sh"""),                            // curl | sh
        Regex("""\bwget\b.*\|\s*(ba)?sh""")
    )

    // Destructive / externally-visible patterns. Require confirmation.
    private val CONFIRM_PATTERNS = listOf(
        Regex("""\brm\b"""),
        Regex("""\brmdir\b"""),
        Regex("""\bmv\b"""),
        Regex("""\bgit\s+push\b"""),
        Regex("""\bgit\s+reset\s+--hard\b"""),
        Regex("""\bgit\s+clean\b"""),
        Regex("""\bgit\s+checkout\s+--\s"""),
        Regex("""\bgit\s+branch\s+-D\b"""),
        Regex("""\bnpm\s+publish\b"""),
        Regex("""\bpnpm\s+publish\b"""),
        Regex("""\byarn\s+publish\b"""),
        Regex("""\bchmod\b"""),
        Regex("""\bchown\b"""),
        Regex("""\bkill(all)?\b"""),
        Regex("""\btruncate\b"""),
        Regex("""\bshred\b"""),
        Regex("""\bgit\s+push\s+.*--delete\b"""),
        Regex("""\bgit\s+push\s+.*:\s*\w+""")
    )

    /**
     * Classifies a shell command line into a [RiskLevel]. The check is
     * intentionally conservative: anything that deletes, moves, force-resets,
     * publishes, or touches device nodes escalates; a small denylist of
     * device-damaging commands is blocked outright.
     */
    fun classifyCommand(command: String): RiskLevel {
        val normalized = command.trim()
        if (normalized.isEmpty()) return RiskLevel.SAFE
        for (pattern in BLOCKED_PATTERNS) {
            if (pattern.containsMatchIn(normalized)) return RiskLevel.BLOCKED
        }
        for (pattern in CONFIRM_PATTERNS) {
            if (pattern.containsMatchIn(normalized)) return RiskLevel.NEEDS_CONFIRMATION
        }
        return RiskLevel.SAFE
    }

    /**
     * True when a command is a obvious "write file" redirection that overwrites
     * a target (`> file`). Used to surface a confirmation for silent overwrites
     * even though `>` is not in [CONFIRM_PATTERNS] (too noisy for build logs).
     */
    fun isOverwritingRedirect(command: String): Boolean =
        Regex("""(^|[^>2&])>\s*[^>\s]""").containsMatchIn(command.trim())
}
