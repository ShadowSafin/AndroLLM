package io.androllm.feature.coding.tools

import java.util.UUID
import kotlinx.serialization.Serializable

/** Status of one plan step (mirrors the model's enum values). */
@Serializable
enum class PlanStepStatus(val wire: String) {
    PENDING("pending"),
    IN_PROGRESS("in_progress"),
    DONE("done");

    companion object {
        fun fromWire(raw: String): PlanStepStatus = when (raw.lowercase().trim()) {
            "in_progress", "inprogress", "active", "doing" -> IN_PROGRESS
            "done", "complete", "completed" -> DONE
            else -> PENDING
        }
    }
}

/**
 * One step of the agent's visible task plan. A stable [id] lets the user
 * reorder / remove / edit individual steps without losing track of which step
 * the agent is currently working on (independent of array indices).
 */
@Serializable
data class PlanStep(
    val text: String,
    val status: PlanStepStatus,
    val id: String = UUID.randomUUID().toString()
) {
    companion object {
        /** Builds a pending step with a fresh id. */
        fun pending(text: String): PlanStep = PlanStep(text = text, status = PlanStepStatus.PENDING)
        /** Builds an in-progress step with a fresh id. */
        fun inProgress(text: String): PlanStep = PlanStep(text = text, status = PlanStepStatus.IN_PROGRESS)
        /** Builds a done step with a fresh id. */
        fun done(text: String): PlanStep = PlanStep(text = text, status = PlanStepStatus.DONE)
    }
}
