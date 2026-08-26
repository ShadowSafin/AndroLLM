package io.androllm.core.tools.cloud

import io.androllm.core.cloud.model.CloudChatMessage
import io.androllm.core.cloud.model.CloudTool
import io.androllm.core.cloud.model.CloudToolCall
import io.androllm.core.cloud.model.CloudToolCallFunction
import io.androllm.core.tools.agent.AgentPlanner
import io.androllm.core.tools.agent.AgentVariableStore
import io.androllm.core.tools.api.ToolEvent
import io.androllm.core.tools.coordinator.ToolLoopGuard
import io.androllm.core.tools.coordinator.ToolRunCoordinator
import io.androllm.core.tools.planner.ToolPlanner
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import timber.log.Timber

/** One tool call the router skipped because its condition evaluated false. */
data class CloudConditionalSkip(
    val toolName: String,
    val callId: String,
    val reason: String
)

/** Outcome of one cloud tool-routing round. */
data class CloudToolRouteResult(
    /** Messages to append to the OpenAI history (assistant + tool messages). */
    val messages: List<CloudChatMessage>,
    val executedCount: Int,
    val conditionalSkips: List<CloudConditionalSkip>,
    val allBlockedByGuard: Boolean
) {
    val anyExecuted: Boolean get() = executedCount > 0
}

/**
 * The cloud tool router — the tool side of the cloud pipeline:
 *
 * ```
 * Tool planning → Tool selection → [routing] → Tool execution →
 * Tool result observation → continue until goal satisfied
 * ```
 *
 * Cloud models plan and select tools themselves through native function
 * calling (the OpenAI-compatible `tools` array built by [ToolPlanner]); this
 * router sits between the model's tool requests and the gated executor and
 * adds the pieces a raw native loop does not have:
 *
 * - **Internal workflow planning** ([AgentPlanner]): every turn gets an
 *   execution plan (goal → required tools → order → dependencies) used for
 *   conditional evaluation and developer logging.
 * - **Conditional logic**: "check the weather, then message Mom IF it rains"
 *   — the router observes earlier tool results (weather, search results) and
 *   skips the dependent call when the user's condition is not met, feeding
 *   the skip back to the model as a normal tool result.
 * - **Multi-step observation**: every executed result lands in
 *   [AgentVariableStore] working memory, so later rounds (SMS draft after a
 *   web search) see earlier outputs without re-asking.
 * - **Fallback normalization**: calls recovered from plain-text responses
 *   (providers without native tool-call syntax) arrive already converted to
 *   [CloudToolCall]s by the caller and flow through the exact same gated
 *   path — validation, confirmation for sensitive actions, loop guard.
 *
 * Execution itself is delegated to [ToolRunCoordinator.executeCloudToolCalls],
 * which owns validation, confirmation gating, chunking and retries.
 */
@Singleton
class CloudToolRouter @Inject constructor(
    private val coordinator: ToolRunCoordinator,
    private val planner: ToolPlanner,
    private val agentPlanner: AgentPlanner,
    private val variableStore: AgentVariableStore
) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * OpenAI-compatible `tools` array for this request, routed to the query
     * (math → calculator only, device → device tools...). Blank query = the
     * full enabled set.
     */
    suspend fun advertisedTools(query: String = "", hasAttachments: Boolean = false): List<CloudTool> =
        planner.buildCloudTools(query, hasAttachments)

    /**
     * Builds the internal execution plan for the user's request (goal →
     * required tools → order → conditionals). Used for conditional
     * evaluation and developer logging only — never shown to users.
     */
    fun planWorkflow(userQuery: String): AgentPlanner.AgentPlan? = runCatching {
        if (userQuery.isBlank()) return null
        val plan = agentPlanner.createPlan(
            userRequest = userQuery,
            previousToolOutputs = variableStore.snapshot()
        )
        Timber.d(
            "CloudToolRouter: plan steps=%s conditional=%s parallel=%s",
            plan.executionOrder.map { it.toolName }, plan.hasConditional, plan.hasParallel
        )
        plan
    }.getOrElse { e ->
        Timber.w(e, "CloudToolRouter: planning failed — continuing without a plan")
        null
    }

    /**
     * Routes one round of requested tool calls: applies conditional gating
     * against observed results, executes the survivors through the gated
     * coordinator, and returns the history messages for the next round.
     *
     * [userQuery] is the user's ORIGINAL request for this turn — conditional
     * phrases ("if it rains", "if you find anything") are evaluated against
     * it plus the tool outputs observed so far.
     */
    suspend fun routeAndExecute(
        calls: List<CloudToolCall>,
        userQuery: String,
        assistantContent: String? = null,
        onEvent: suspend (ToolEvent) -> Unit = {},
        guard: ToolLoopGuard? = null
    ): CloudToolRouteResult {
        if (calls.isEmpty()) {
            return CloudToolRouteResult(emptyList(), 0, emptyList(), allBlockedByGuard = false)
        }

        // ── Conditional gating: skip calls whose user-stated condition is false ──
        val observations = variableStore.snapshot()
        val executable = mutableListOf<CloudToolCall>()
        val skips = mutableListOf<CloudConditionalSkip>()
        for (call in calls) {
            val name = call.function?.name.orEmpty()
            val args = parseArgs(call.function?.arguments)
            val skipReason = CloudConditionals.evaluateSkip(
                userQuery = userQuery,
                callName = name,
                arguments = args,
                observations = observations
            )
            if (skipReason != null) {
                Timber.i("CloudToolRouter: conditional skip '$name' — $skipReason")
                skips += CloudConditionalSkip(
                    toolName = name,
                    callId = call.id ?: "call_${name}_${skips.size}",
                    reason = skipReason
                )
            } else {
                executable += call
            }
        }

        // ── Execute the survivors through the hardened coordinator path ──
        val executedMessages = if (executable.isNotEmpty()) {
            coordinator.executeCloudToolCalls(
                calls = executable,
                assistantContent = assistantContent,
                onEvent = onEvent,
                guard = guard
            )
        } else emptyList()

        val executedCount = executedMessages.count { it.role == "tool" }
        val allBlocked = executable.isEmpty() && skips.isEmpty() ||
            (executedMessages.isEmpty() && guard?.blockedThisTurn == true)

        // ── Feed conditional skips back as normal tool results so the model
        //    can observe them and answer ("No rain, so I didn't message Mom").
        val skipMessages = if (skips.isNotEmpty()) {
            val assistantCalls = skips.mapIndexed { index, skip ->
                CloudToolCall(
                    index = index,
                    id = skip.callId,
                    type = "function",
                    function = CloudToolCallFunction(skip.toolName, "{}")
                )
            }
            listOf(
                CloudChatMessage(role = "assistant", content = null, toolCalls = assistantCalls)
            ) + skips.map { skip ->
                CloudChatMessage(
                    role = "tool",
                    content = "Skipped '${skip.toolName}': ${skip.reason}. " +
                        "Continue and tell the user what happened.",
                    toolCallId = skip.callId
                )
            }
        } else emptyList()

        return CloudToolRouteResult(
            messages = executedMessages + skipMessages,
            executedCount = executedCount,
            conditionalSkips = skips,
            allBlockedByGuard = allBlocked
        )
    }

    private fun parseArgs(raw: String?): JsonObject {
        if (raw.isNullOrBlank()) return JsonObject(emptyMap())
        return runCatching { json.parseToJsonElement(raw) as? JsonObject ?: JsonObject(emptyMap()) }
            .getOrDefault(JsonObject(emptyMap()))
    }
}
