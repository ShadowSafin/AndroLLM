package io.androllm.feature.coding.tools.impl

import io.androllm.feature.coding.tools.CodingTool
import io.androllm.feature.coding.tools.CodingToolContext
import io.androllm.feature.coding.tools.CodingToolResult
import io.androllm.feature.coding.tools.CodingToolSpec
import io.androllm.feature.coding.tools.PlanStep
import io.androllm.feature.coding.tools.PlanStepStatus
import io.androllm.feature.coding.tools.Schemas
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The agent's visible task plan (OpenCode-style). The model calls this with the
 * FULL step list whenever the plan changes; the user sees the plan live in the
 * Plan panel (pending / in-progress / done), and the status strip shows the
 * current step.
 */
class UpdatePlanTool : CodingTool {
    override val spec = CodingToolSpec(
        name = "update_plan",
        description = "Create or update the visible task plan for the current goal. Send the COMPLETE step list " +
            "(3-10 concise steps) every time — it replaces the previous plan. Statuses: pending, in_progress, done. " +
            "Keep exactly ONE step in_progress at a time; mark steps done as soon as they finish. " +
            "Use this for any multi-step task before you start working.",
        parameters = Schemas.obj(
            mapOf(
                "steps" to Schemas.array(
                    items = Schemas.obj(
                        mapOf(
                            "text" to Schemas.string("Short imperative step, e.g. 'Add the login form'."),
                            "status" to Schemas.string(
                                "Step state.",
                                enum = listOf("pending", "in_progress", "done")
                            )
                        ),
                        required = listOf("text", "status")
                    ),
                    description = "The full plan step list (replaces the previous plan)."
                )
            ),
            required = listOf("steps")
        ),
        readOnly = false
    )

    override suspend fun execute(arguments: JsonObject, context: CodingToolContext): CodingToolResult {
        val rawSteps = arguments["steps"] as? JsonArray
            ?: return CodingToolResult.Failure("Missing 'steps' array.", retryable = false)
        if (rawSteps.isEmpty()) {
            return CodingToolResult.Failure("'steps' must not be empty.", retryable = false)
        }
        if (rawSteps.size > MAX_STEPS) {
            return CodingToolResult.Failure("Too many steps (${rawSteps.size}) — keep the plan to at most $MAX_STEPS.", retryable = false)
        }

        val steps = mutableListOf<PlanStep>()
        for (element in rawSteps) {
            val obj = element as? JsonObject
                ?: return CodingToolResult.Failure("Each step must be an object {text, status}.", retryable = false)
            val text = (obj["text"] as? JsonPrimitive)?.content?.trim().orEmpty()
            if (text.isEmpty()) {
                return CodingToolResult.Failure("Every step needs a non-empty 'text'.", retryable = false)
            }
            val status = (obj["status"] as? JsonPrimitive)?.content?.let { PlanStepStatus.fromWire(it) }
                ?: PlanStepStatus.PENDING
            steps += PlanStep(text.take(MAX_STEP_CHARS), status)
        }

        val inProgress = steps.count { it.status == PlanStepStatus.IN_PROGRESS }
        if (inProgress > 1) {
            return CodingToolResult.Failure(
                "Only ONE step may be in_progress at a time ($inProgress given). Update statuses and retry.",
                retryable = true
            )
        }

        context.onPlanUpdated(steps)
        val done = steps.count { it.status == PlanStepStatus.DONE }
        val current = steps.firstOrNull { it.status == PlanStepStatus.IN_PROGRESS }?.text
        return CodingToolResult.Success(
            buildString {
                append("Plan updated: ").append(steps.size).append(" steps (").append(done).append(" done")
                if (current != null) append(", working on: ").append(current)
                append("). The user can see the plan live.")
            }
        )
    }

    companion object {
        const val MAX_STEPS = 20
        const val MAX_STEP_CHARS = 160
    }
}
