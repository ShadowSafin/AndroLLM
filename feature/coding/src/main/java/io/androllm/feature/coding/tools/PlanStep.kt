package io.androllm.feature.coding.tools

/** Status of one plan step (mirrors the model's enum values). */
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

/** One step of the agent's visible task plan. */
data class PlanStep(val text: String, val status: PlanStepStatus)
