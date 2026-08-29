package io.androllm.feature.coding.tools

import io.androllm.feature.coding.environment.BackgroundServiceManager
import io.androllm.feature.coding.environment.CommandExecutor
import io.androllm.feature.coding.workspace.CodingWorkspace
import io.androllm.feature.coding.workspace.WorkspaceFileOps
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Workspace-scoped contract for a coding tool. Mirrors the platform's
 * `core/tools` ToolSpec shape (name + description + JSON-schema parameters) so
 * the cloud model sees a familiar function-calling surface, but coding tools are
 * NOT registered in the global device-tool registry — they are scoped to the
 * active workspace and executed by [CodingToolExecutor]. This keeps the coding
 * agent a self-contained feature, separate from normal chat tool calling.
 */
interface CodingTool {
    /** Declarative, model-visible metadata. */
    val spec: CodingToolSpec

    /** Runs the tool with parsed [arguments] and the live workspace [context]. */
    suspend fun execute(arguments: JsonObject, context: CodingToolContext): CodingToolResult
}

/**
 * Static description of a coding tool. The model sees [name], [description] and
 * [parameters]; never the implementation.
 *
 * @param name snake_case identifier used in the cloud `tools` array.
 * @param description 1–2 sentence model-facing description.
 * @param parameters JSON Schema for the argument object.
 * @param requiresConfirmation true for destructive/externally-visible tools.
 * @param readOnly true when the tool never mutates the workspace (parallel-safe).
 */
data class CodingToolSpec(
    val name: String,
    val description: String,
    val parameters: JsonObject,
    val requiresConfirmation: Boolean = false,
    val readOnly: Boolean = true
)

/**
 * Live dependencies handed to every coding tool invocation. Carries the sandboxed
 * file ops, the CLI executor, the active workspace and hooks to record activity
 * into the session (recent files / tool history).
 *
 * @param services background-service manager (dev servers); null in tests.
 * @param onCommandOutput live line-by-line sink for command output, so the UI
 *   can stream a command's stdout/stderr in real time while it runs.
 * @param editReviewGate user-approval gate for major file changes (diff review);
 *   null auto-approves everything (tests / review disabled).
 * @param planApprovalGate user-approval gate for the first plan a task emits.
 *   When non-null, the first update_plan call for a not-yet-approved task is
 *   held as a draft and the gate decides whether the plan may be applied. The
 *   gate implementation is responsible for letting the user edit the plan.
 * @param onPlanUpdated fired when the agent updates its visible task plan.
 */
class CodingToolContext(
    val workspace: CodingWorkspace,
    val fileOps: WorkspaceFileOps,
    val executor: CommandExecutor,
    val services: BackgroundServiceManager? = null,
    val onCommandOutput: (String) -> Unit = {},
    val editReviewGate: EditReviewGate? = null,
    val planApprovalGate: PlanApprovalGate? = null,
    val onPlanUpdated: (List<PlanStep>) -> Unit = {},
    private val onFileTouched: (path: String, kind: String) -> Unit = { _, _ -> },
    private val onToolUsed: (String) -> Unit = {}
) {
    /** Records a touched file for the file-activity feed. [kind] is "read", "create", "edit", or "delete". */
    fun recordFile(path: String, kind: String = "read") = onFileTouched(path, kind)
    fun recordTool(name: String) = onToolUsed(name)
}

/**
 * Asks the user to approve / edit a plan the agent just emitted. When the gate
 * approves, the orchestrator commits the plan and the agent proceeds; when it
 * rejects, the agent is told to revise the plan and the visible plan is left
 * empty. The gate should only block the FIRST plan for a new task — once the
 * user has approved a plan, subsequent update_plan calls commit directly.
 */
fun interface PlanApprovalGate {
    suspend fun approve(draft: List<PlanStep>): Boolean
}

/** Outcome of a coding tool invocation (fed back to the model verbatim). */
sealed interface CodingToolResult {
    val summary: String

    data class Success(
        override val summary: String,
        val data: Map<String, JsonElement> = emptyMap()
    ) : CodingToolResult

    data class Failure(
        override val summary: String,
        val retryable: Boolean = true,
        /** Set when the failure is a missing runtime addon (drives auto-install). */
        val missingAddonId: String? = null
    ) : CodingToolResult

    val isSuccess: Boolean get() = this is Success
}
