package io.androllm.core.tools.settings

/**
 * How strictly the assistant must confirm tool actions before executing them.
 */
enum class ConfirmationMode(val displayName: String, val description: String) {
    HIGH_RISK("High-risk only", "Confirm messages, calls and emails"),
    ALWAYS("Always", "Confirm every tool action"),
    NEVER("Never", "Execute everything immediately")
}

/**
 * User-controlled automation policy. The master switch ([toolCallingEnabled])
 * turns the whole pipeline on; [disabledTools] holds the names of tools the
 * user has individually blocked in Settings → Automation. Defaults are
 * conservative: the pipeline is OFF until the user opts in, and every tool is
 * enabled the moment the master switch flips (per-tool toggles then prune).
 */
data class AutomationSettings(
    /** Master switch for the whole tool-calling pipeline. */
    val toolCallingEnabled: Boolean = false,
    /** Which actions require a user confirmation. */
    val confirmationMode: ConfirmationMode = ConfirmationMode.HIGH_RISK,
    /** Tool names the user blocked (everything else is allowed). */
    val disabledTools: Set<String> = emptySet(),
    /** Voice mode asks for confirmation out loud and listens for yes/no. */
    val voiceConfirmations: Boolean = true,
    /** Max planner→execute→re-plan rounds in one turn (loop guard). */
    val maxToolRounds: Int = 3,
    /** Show tool activity chips in the chat stream. */
    val showToolActivity: Boolean = true
) {

    /** True when the pipeline is on AND this tool is not user-blocked. */
    fun isToolEnabled(toolName: String): Boolean =
        toolCallingEnabled && toolName !in disabledTools

    /** True when [requiresConfirmation] (spec) should actually prompt. */
    fun shouldConfirm(requiresConfirmation: Boolean): Boolean =
        when (confirmationMode) {
            ConfirmationMode.ALWAYS -> true
            ConfirmationMode.NEVER -> false
            ConfirmationMode.HIGH_RISK -> requiresConfirmation
        }
}
