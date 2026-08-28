package io.androllm.feature.coding.agent

import io.androllm.feature.coding.workspace.CodingWorkspace
import io.androllm.feature.coding.workspace.WorkspaceManager

/** Result of the pre-flight check before opening the coding agent. */
sealed interface CodingGate {
    /** Both requirements met — the coding chat may open. */
    data object Ready : CodingGate

    /** Cloud mode is not configured/enabled — coding is cloud-only. */
    data class NeedsCloud(val message: String) : CodingGate

    /** No workspace folder selected yet. */
    data class NeedsWorkspace(val message: String) : CodingGate
}

/**
 * Enforces the two hard prerequisites for the coding agent:
 *  1. **Cloud-only** — a cloud provider + model must be configured. Local models
 *     are never allowed in coding mode (tool-use + workflow reliability).
 *  2. **Workspace required** — an active workspace folder must be selected.
 *
 * Kept as a small pure collaborator so the gating rules are unit-testable.
 */
class CodingAvailabilityChecker(
    private val cloud: CodingCloudClient,
    private val workspaceManager: WorkspaceManager
) {
    suspend fun check(): CodingGate {
        val cloudOk = runCatching { cloud.isConfigured() }.getOrDefault(false)
        if (!cloudOk) {
            return CodingGate.NeedsCloud(
                "AI Agent Coding Chat is cloud-only. Switch to a cloud model in Cloud Providers, then come back."
            )
        }
        val workspace: CodingWorkspace? = workspaceManager.validateCurrent()
        if (workspace == null) {
            return CodingGate.NeedsWorkspace(
                "Choose a workspace folder to start coding."
            )
        }
        return CodingGate.Ready
    }
}
