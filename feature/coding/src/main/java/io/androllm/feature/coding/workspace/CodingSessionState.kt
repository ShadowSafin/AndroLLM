package io.androllm.feature.coding.workspace

import kotlinx.serialization.Serializable

/**
 * Persisted state for one coding agent session. Survives process death and is
 * restored when the coding chat reopens. Kept deliberately flat + serializable
 * so it round-trips through DataStore as JSON.
 *
 * @param workspaceId active workspace (empty until the user picks one).
 * @param modelId cloud model id pinned for this session (empty = default).
 * @param objective current high-level task the agent is working toward.
 * @param installedAddons ids of marketplace addons installed in this environment.
 * @param commandHistory recent commands (bounded, newest last).
 * @param toolHistory recent tool invocations (bounded, newest last).
 * @param recentFiles recently accessed workspace-relative paths (bounded).
 * @param plan current coding plan steps (free-form, agent maintained).
 * @param taskMode active task mode id (empty = GENERAL).
 * @param reviewMajorEdits when true, major file changes require diff approval.
 * @param updatedAtEpochMs last modification timestamp.
 */
@Serializable
data class CodingSessionState(
    val workspaceId: String = "",
    val modelId: String = "",
    val objective: String = "",
    val installedAddons: List<String> = emptyList(),
    val commandHistory: List<String> = emptyList(),
    val toolHistory: List<String> = emptyList(),
    val recentFiles: List<String> = emptyList(),
    val plan: List<String> = emptyList(),
    val taskMode: String = "",
    val reviewMajorEdits: Boolean = true,
    val updatedAtEpochMs: Long = 0L
) {
    val hasWorkspace: Boolean get() = workspaceId.isNotBlank()

    fun withCommand(cmd: String, limit: Int = MAX_HISTORY): CodingSessionState =
        copy(commandHistory = (commandHistory + cmd).takeLast(limit), updatedAtEpochMs = now())

    fun withTool(name: String, limit: Int = MAX_HISTORY): CodingSessionState =
        copy(toolHistory = (toolHistory + name).takeLast(limit), updatedAtEpochMs = now())

    fun withRecentFile(path: String, limit: Int = MAX_RECENT_FILES): CodingSessionState =
        copy(recentFiles = (recentFiles.filterNot { it == path } + path).takeLast(limit), updatedAtEpochMs = now())

    companion object {
        const val MAX_HISTORY = 100
        const val MAX_RECENT_FILES = 24
        fun now(): Long = System.currentTimeMillis()
    }
}
