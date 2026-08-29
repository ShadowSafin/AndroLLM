package io.androllm.feature.coding.task

import io.androllm.feature.coding.tools.PlanStep
import java.util.UUID
import kotlinx.serialization.Serializable

/**
 * Lifecycle of a coding task. The state machine the orchestrator walks:
 *  READY → PLANNING → AWAITING_APPROVAL → EXECUTING → (TESTING → REVIEWING) → COMPLETED / FAILED / CANCELED.
 */
@Serializable
enum class TaskLifecycle {
    READY,
    PLANNING,
    AWAITING_APPROVAL,
    EXECUTING,
    TESTING,
    REVIEWING,
    COMPLETED,
    FAILED,
    CANCELED
}

/** Live status of any background server started for the task (dev / static / test). */
@Serializable
enum class ServerStatus { IDLE, STARTING, READY, FAILED }

/** One file change recorded by the agent or the user, shown in the file activity feed. */
@Serializable
data class FileChangeRecord(
    val path: String,
    val kind: String,           // "create" | "edit" | "delete" | "overwrite"
    val timestampMs: Long = System.currentTimeMillis(),
    val linesAdded: Int = 0,
    val linesRemoved: Int = 0
)

/** Lightweight reference to a checkpoint stored in the CheckpointStore. */
@Serializable
data class CheckpointRef(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val createdAtMs: Long = System.currentTimeMillis(),
    val fileCount: Int = 0,
    val sizeBytes: Long = 0
)

/**
 * The full state of a single coding task. Persisted to disk per workspace so
 * the task survives process death, the app being backgrounded, or the user
 * switching to another workspace. JSON-serializable, kept compact.
 */
@Serializable
data class CodingTaskState(
    val workspaceId: String,
    val objective: String = "",
    val lifecycle: TaskLifecycle = TaskLifecycle.READY,
    val plan: List<PlanStep> = emptyList(),
    val currentStepIndex: Int = 0,
    val planApproved: Boolean = false,
    val planApprovalPending: Boolean = false,
    val checkpoints: List<CheckpointRef> = emptyList(),
    val changedFiles: List<FileChangeRecord> = emptyList(),
    val logTail: String = "",
    val serverStatus: ServerStatus = ServerStatus.IDLE,
    val serverUrl: String? = null,
    val lastRecovery: RecoveryRecord? = null,
    val lastTestResult: TestResultRecord? = null,
    val lastUpdatedMs: Long = 0L,
    val version: Int = 0
) {
    val isActive: Boolean
        get() = lifecycle in setOf(
            TaskLifecycle.PLANNING,
            TaskLifecycle.AWAITING_APPROVAL,
            TaskLifecycle.EXECUTING,
            TaskLifecycle.TESTING,
            TaskLifecycle.REVIEWING
        )

    val isResumable: Boolean
        get() = isActive && plan.isNotEmpty()
}

/** Record of a single auto-recovery attempt. */
@Serializable
data class RecoveryRecord(
    val originalCommand: String,
    val recoveryCommand: String,
    val category: String,         // e.g. "npm_peer_deps", "port_conflict", "reinstall"
    val succeeded: Boolean,
    val timestampMs: Long = System.currentTimeMillis()
)

/** Record of the most recent test run. */
@Serializable
data class TestResultRecord(
    val framework: String,        // "npm test", "pytest", "gradle test", ...
    val passed: Int = 0,
    val failed: Int = 0,
    val skipped: Int = 0,
    val error: Int = 0,
    val rawOutputTail: String = "",
    val timestampMs: Long = System.currentTimeMillis()
) {
    val isPass: Boolean get() = failed == 0 && error == 0 && (passed > 0 || rawOutputTail.isNotBlank())
}
