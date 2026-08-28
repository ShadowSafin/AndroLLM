package io.androllm.feature.coding.ui

import io.androllm.feature.coding.agent.CodingGate
import io.androllm.feature.coding.agent.CodingTaskMode
import io.androllm.feature.coding.environment.CommandResult
import io.androllm.feature.coding.environment.InstallProgress
import io.androllm.feature.coding.tools.ChangeKind
import io.androllm.feature.coding.tools.PlanStep
import io.androllm.feature.coding.workspace.CodingWorkspace
import io.androllm.feature.coding.workspace.FileTreeNode

/** Role of a chat line in the coding conversation. */
enum class CodingMessageRole { USER, ASSISTANT, SYSTEM, TOOL }

/** One line in the coding chat transcript. */
data class CodingChatMessage(
    val id: String,
    val role: CodingMessageRole,
    val text: String,
    val timestampMs: Long = System.currentTimeMillis(),
    /** Set for TOOL lines: which tool produced it. */
    val toolName: String? = null,
    /** Set when this assistant line is still streaming. */
    val isStreaming: Boolean = false,
    /** Set for file-mutating TOOL lines: unified diff to render inline. */
    val diff: String? = null,
    /** Set for failed run_command TOOL lines: command the user can retry. */
    val failedCommand: String? = null
)

/** A pending destructive-action confirmation surfaced in the chat. */
data class PendingCodingConfirmation(
    val id: String,
    val title: String,
    val detail: String
)

/** A major file change awaiting the user's diff review (approve/reject). */
data class PendingEditReviewUi(
    val path: String,
    val kind: ChangeKind,
    val diff: String,
    val added: Int,
    val removed: Int
)

/** Preview lifecycle for the coding workspace (auto-detection + UX). */
enum class PreviewUiStatus {
    IDLE,           // nothing scanned yet
    SCANNING,
    READY,          // preview target available, panel should be shown
    NOT_AVAILABLE,  // no target found — show explanation + manual fallback
    FAILED          // preview attempted but failed to load
}

data class PreviewUiState(
    val status: PreviewUiStatus = PreviewUiStatus.IDLE,
    val framework: String? = null,
    /** Relative path for static file previews (e.g. "index.html"). */
    val targetPath: String? = null,
    /** Resolved HTTP URL (http://localhost:PORT) when ready. Never file://. */
    val targetUrl: String? = null,
    val targetTitle: String? = null,
    val suggestion: String? = null,
    /** Human-readable trace for debugging. */
    val logs: List<String> = emptyList(),
    /** True when auto-open was triggered this scan. */
    val autoOpened: Boolean = false,
    /** Increments to force WebView reload (refresh after edits/build). */
    val refreshTick: Int = 0,
    val lastScannedAtMs: Long = 0L,
    val error: String? = null,
    /** Precise lifecycle step, e.g. "Starting local server...", "Waiting for localhost:5173...". */
    val phase: String? = null,
    /** Background-service id of the preview server (drives Stop Preview). */
    val serverServiceId: String? = null,
    /** Server output captured when startup failed (shown to the user). */
    val serverLog: String? = null,
    /** True when a local server can be started for the detected target. */
    val canStartServer: Boolean = false
)

/**
 * Immutable UI state for the coding chat screen. The screen renders this plus
 * the hot flows (terminal history, install progress) exposed by the ViewModel.
 */
data class CodingUiState(
    val gate: CodingGate = CodingGate.NeedsWorkspace("Choose a workspace folder to start coding."),
    val workspace: CodingWorkspace? = null,
    val messages: List<CodingChatMessage> = emptyList(),
    val isGenerating: Boolean = false,
    val streamingText: String = "",
    val toolActivity: String? = null,
    val runningCommand: String? = null,
    val objective: String = "",
    val modelLabel: String = "",
    val error: String? = null,
    val pendingConfirmation: PendingCodingConfirmation? = null,
    /** Addon id awaiting user install approval (missing-dependency prompt). */
    val pendingAddonInstall: String? = null,
    /** Major file change awaiting diff review (approve/reject). */
    val pendingEditReview: PendingEditReviewUi? = null,
    val fileTree: FileTreeNode? = null,
    /** The agent's visible task plan (maintained via the update_plan tool). */
    val plan: List<PlanStep> = emptyList(),
    /** Active task mode (tailors the agent's working method). */
    val taskMode: CodingTaskMode = CodingTaskMode.GENERAL,
    /** When true, major file changes require the user's diff approval. */
    val reviewMajorEdits: Boolean = true,
    /** URL shown in the preview panel (blank = pick from running services). */
    val previewUrl: String = "",
    val preview: PreviewUiState = PreviewUiState(),
    val showMarketplace: Boolean = false,
    val showEnvironment: Boolean = false
)

/** A terminal line for the dedicated terminal panel (raw output preserved). */
data class TerminalLine(
    val id: Long,
    val command: String,
    val output: String,
    val exitCode: Int,
    val cancelled: Boolean,
    val durationMs: Long
) {
    companion object {
        fun from(result: CommandResult): TerminalLine = TerminalLine(
            id = System.identityHashCode(result).toLong() * 1000 + (result.durationMs % 1000),
            command = result.command,
            output = result.combinedOutput,
            exitCode = result.exitCode,
            cancelled = result.cancelled,
            durationMs = result.durationMs
        )
    }
}

/** Marketplace row state. */
data class MarketplaceItemUi(
    val id: String,
    val name: String,
    val description: String,
    val version: String,
    val sizeLabel: String,
    val commands: String,
    val requiresInternet: Boolean,
    val platforms: String,
    val status: io.androllm.feature.coding.environment.InstallStatus,
    val progress: InstallProgress?
)
