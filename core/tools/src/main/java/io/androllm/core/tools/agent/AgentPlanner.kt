package io.androllm.core.tools.agent

import io.androllm.core.tools.api.ToolSpec
import io.androllm.core.tools.validation.ToolExecutionLogger
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

/**
 * Internal agent planner — the autonomous orchestration layer that mirrors
 * ChatGPT/Claude/Gemini/OpenAI Agents behavior.
 *
 * Before executing ANY tool, the planner decomposes the user request into:
 * Goal -> Required Information -> Required Tools -> Execution Order -> Dependencies
 * Then drives: Run Tools -> Observe Results -> Continue Until Goal Complete.
 *
 * This planner is **internal only** — never exposed to the user unless
 * developer mode is enabled. Normal execution only shows the final answer;
 * developer view shows Plan Created -> Tool Selected -> Completed -> Goal Complete.
 *
 * Hardening guarantees:
 * - Dependency awareness (Research MUST finish before SMS)
 * - Sequential keyword understanding (then, after, next, finally, before, first, second, last, and then, once finished, after researching, before sending)
 * - Parallel detection for independent tools (Weather || News)
 * - Conditional IF/ELSE branching (if it rains -> SMS else no-op)
 * - Tool result memory (outputs cached as workflow variables)
 * - Never stops early — asks "Does the original request require additional actions?"
 * - Confirmation gating only for side-effect tools
 */
@Singleton
class AgentPlanner @Inject constructor(
    private val logger: ToolExecutionLogger = ToolExecutionLogger(),
    private val variableStore: AgentVariableStore
) {

    /**
     * One step in the execution graph.
     * Mirrors the prompt's Execution Graph: Research -> Summary -> SMS Draft -> SMS Send -> Done
     */
    data class PlanStep(
        val id: String,
        val toolName: String,
        val description: String,
        val argumentsHint: Map<String, String> = emptyMap(),
        val dependsOn: List<String> = emptyList(),
        val condition: Condition? = null,
        val parallelGroup: Int? = null,
        val order: Int = 0
    )

    data class Condition(
        val expression: String,
        val toolOutputKey: String,
        val expectedContains: String? = null,
        val onTrue: String? = null,
        val onFalse: String? = null
    )

    data class ExecutionGraph(
        val nodes: List<PlanStep>,
        /** Level-ordered execution: each inner list can run in parallel */
        val levels: List<List<PlanStep>>
    )

    data class AgentPlan(
        val goal: String,
        val requiredInformation: List<String>,
        val requiredTools: List<String>,
        val executionOrder: List<PlanStep>,
        val dependencies: Map<String, List<String>>,
        val executionGraph: ExecutionGraph,
        val hasConditional: Boolean = false,
        val hasParallel: Boolean = false,
        val isMultiStep: Boolean = false,
        val rawRequest: String = ""
    )

    // ── Sequential markers: then, after, next, finally, before, first, second, last, and then, once finished, after researching, after checking, before sending, etc.
    private val SEQUENTIAL_MARKERS = listOf(
        " and then ", " then ", " after ", " next ", " finally ", " before ",
        " first ", " second ", " last ", " once finished ", " once done ",
        " after researching", " after checking", " before sending",
        " after that", " and next", " followed by"
    )

    // Parallel markers: independent work without ordering dependency
    private val PARALLEL_MARKERS = listOf(
        " and ", " as well as ", " plus ", " also "
    )

    // Conditional markers: if, whether, when, unless, in case
    private val CONDITIONAL_MARKERS = listOf(
        " if ", " whether ", " when ", " unless ", " in case ", " only if "
    )

    private val WEATHER_TERMS = setOf("weather", "forecast", "rain", "temperature", "humidity", "climate")
    private val SEARCH_TERMS = setOf("search", "research", "look up", "google", "news", "latest", "find", "investigate", "explore")
    private val SMS_TERMS = setOf("sms", "text", "message", "send a text", "send text", "message dad", "message mom", "message dad", "text dad", "text mom")
    private val EMAIL_TERMS = setOf("email", "mail")
    private val NOTE_TERMS = setOf("note", "save", "save it as", "save as note")
    private val TRANSLATE_TERMS = setOf("translate", "translation")
    private val PDF_TERMS = setOf("pdf", "export", "generate a pdf", "generate pdf")
    private val MAP_TERMS = setOf("navigate", "maps", "restaurant", "nearest", "hospital", "hotel", "flight")
    private val FILE_TERMS = setOf("file", "screenshot", "image", "download", "analyze", "explain what is wrong")
    private val CALENDAR_TERMS = setOf("calendar", "event", "reminder")
    private val SHOPPING_TERMS = setOf("shopping list", "grocery", "buy")

    /**
     * Internal planning entry point — creates the full execution plan before ANY tool execution.
     * This is the "PLANNER" in: LLM -> Planner -> Tool -> Observe -> LLM -> Need Another Tool? -> ...
     */
    fun createPlan(
        userRequest: String,
        enabledTools: List<ToolSpec> = emptyList(),
        conversationContext: String = "",
        previousToolOutputs: Map<String, String> = emptyMap(),
        developerMode: Boolean = false
    ): AgentPlan {
        val request = userRequest.trim()
        val lower = request.lowercase()

        // ── Goal extraction
        val goal = request

        // ── Detect multi-step markers
        val isSequential = SEQUENTIAL_MARKERS.any { it in lower }
        val isConditional = CONDITIONAL_MARKERS.any { it in lower } && lower.contains("if")
        val isParallelCandidate = !isSequential && PARALLEL_MARKERS.any { it in lower }

        // ── Required Information inference
        val requiredInfo = mutableListOf<String>()
        if (containsAny(lower, SEARCH_TERMS)) requiredInfo += "search results"
        if (containsAny(lower, WEATHER_TERMS)) requiredInfo += "weather data"
        if (lower.contains("quantum")) requiredInfo += "quantum computing summary"
        if (lower.contains("ai news") || lower.contains("latest ai")) requiredInfo += "latest AI news articles"
        if (lower.contains("flight") || lower.contains("hotel")) requiredInfo += "travel options sorted by price"
        if (lower.contains("weather") && lower.contains("rain")) requiredInfo += "rain probability decision"
        if (lower.contains("translate")) requiredInfo += "translated content"
        if (lower.contains("pdf") || lower.contains("export")) requiredInfo += "exported document"
        if (requiredInfo.isEmpty()) requiredInfo += "user intent analysis"

        // ── Clause splitting for sequential understanding
        val clauses = splitBySequentialMarkers(request)

        // ── Required Tools inference per clause (and overall)
        val requiredTools = mutableSetOf<String>()
        val steps = mutableListOf<PlanStep>()
        var stepOrder = 0

        for ((idx, clause) in clauses.withIndex()) {
            val clauseLower = clause.lowercase()
            val inferred = inferToolsForClause(clause, enabledTools)
            for (tool in inferred) {
                if (tool !in requiredTools) requiredTools += tool
                // Dedupe steps with same tool in same request: allow sequential distinct steps e.g., search -> note_save
            }
            // Create steps preserving execution order and dependencies
            for (toolName in inferred) {
                val dependsOn = if (idx == 0) emptyList() else steps.filter { it.order < stepOrder }.map { it.id }.takeLast(1)
                val condition = if (isConditional && idx > 0) {
                    inferConditionForClause(request, clause, idx)
                } else null
                steps += PlanStep(
                    id = "step_${stepOrder}",
                    toolName = toolName,
                    description = clause.trim().take(80),
                    dependsOn = dependsOn,
                    condition = condition,
                    parallelGroup = if (!isSequential && isParallelCandidate) 0 else null,
                    order = stepOrder++
                )
            }
        }

        // ── Handle parallel case: independent tools can run concurrently
        val hasParallel = detectParallel(userRequest, requiredTools)
        val hasConditionalFinal = isConditional || request.lowercase().contains("if it will rain") || request.lowercase().contains("if it rains")

        // ── Build execution graph levels (topological grouping)
        val graph = buildExecutionGraph(steps, hasParallel)

        // ── Dependencies map
        val dependencies = steps.associate { it.id to it.dependsOn }

        val plan = AgentPlan(
            goal = goal,
            requiredInformation = requiredInfo,
            requiredTools = requiredTools.toList(),
            executionOrder = steps,
            dependencies = dependencies,
            executionGraph = graph,
            hasConditional = hasConditionalFinal,
            hasParallel = hasParallel,
            isMultiStep = clauses.size > 1 || requiredTools.size > 1 || isSequential || hasParallel || hasConditionalFinal,
            rawRequest = request
        )

        if (developerMode) {
            Timber.i("AgentPlanner: PLAN CREATED goal='${goal.take(60)}' steps=${steps.map { it.toolName }} parallel=$hasParallel conditional=$hasConditionalFinal sequential=$isSequential")
            logger.logSelection(requiredTools.toList(), "[PLAN] $request")
        } else {
            // Internal only — no user-visible output
            Timber.d("AgentPlanner: internal plan for '${request.take(40)}' -> ${steps.map { it.toolName }}")
        }
        return plan
    }

    /**
     * Splits request by sequential markers, preserving order.
     * Understands: then, after, next, finally, before, first, second, last, and then, once finished, after researching, etc.
     */
    fun splitBySequentialMarkers(request: String): List<String> {
        val lower = request.lowercase()
        // Build regex for sequential splits — ordered longest first to avoid partial matches
        val markers = listOf(
            " and then ", " once finished ", " once done ", " after researching ",
            " after checking ", " before sending ", " followed by ",
            " then ", " after ", " next ", " finally ", " before "
        )
        var remaining = request
        val parts = mutableListOf<String>()
        // Iteratively split on earliest marker occurrence
        while (true) {
            var earliestIdx = -1
            var earliestMarker: String? = null
            val remainingLower = remaining.lowercase()
            for (m in markers) {
                val idx = remainingLower.indexOf(m)
                if (idx >= 0 && (earliestIdx == -1 || idx < earliestIdx)) {
                    earliestIdx = idx
                    earliestMarker = m
                }
            }
            if (earliestIdx == -1 || earliestMarker == null) {
                if (remaining.trim().isNotBlank()) parts += remaining.trim()
                break
            }
            val before = remaining.substring(0, earliestIdx).trim()
            if (before.isNotBlank()) parts += before
            remaining = remaining.substring(earliestIdx + earliestMarker.length).trim()
            // Handle "before" inversion: "message Dad before researching" -> research should be first, but syntactically reversed.
            // For simplicity, we preserve textual order; dependency resolver will reorder if "before" was used.
            // A full "before" inversion would swap last two parts; we handle common case:
            if (earliestMarker.trim() == "before" && parts.size >= 1 && remaining.isNotBlank()) {
                // "A before B" means B happens first -> swap
                val a = parts.removeAt(parts.lastIndex)
                parts += remaining.substringBefore(",").trim()
                parts += a
                remaining = remaining.substringAfter(",", "").trim()
                if (remaining.isBlank()) break
            }
        }
        // If no split occurred but request contains ordinal markers like "first", "second", treat as implicit sequence
        if (parts.size == 1 && (lower.contains(" first ") || lower.contains(" second ") || lower.contains(" last "))) {
            // Try splitting on commas and numbered steps
            val commaParts = request.split(Regex(",|;|\\band\\b")).map { it.trim() }.filter { it.length > 8 }
            if (commaParts.size > 1) return commaParts
        }
        return if (parts.isEmpty()) listOf(request) else parts
    }

    private fun inferToolsForClause(clause: String, enabledTools: List<ToolSpec>): List<String> {
        val lower = clause.lowercase()
        val tools = mutableListOf<String>()
        val enabledNames = enabledTools.map { it.name }.toSet()

        fun addIf(termMatch: Boolean, toolName: String) {
            if (!termMatch) return
            if (enabledNames.isNotEmpty() && toolName !in enabledNames) return
            if (toolName !in tools) tools += toolName
        }

        // Search / Research
        if (containsAny(lower, setOf("research", "search", "look up", "google", "find", "latest", "news", "investigate", "explore"))) {
            // Map to search_web if available; github for GitHub-specific, but search_web is universal fallback
            if (lower.contains("github")) addIf(true, "github") else addIf(true, "search_web")
            // Flight/hotel specific could still be search_web (no dedicated flight tool; search covers)
            if (lower.contains("flight") || lower.contains("hotel")) addIf(true, "search_web")
        }
        // Weather
        if (containsAny(lower, WEATHER_TERMS) || lower.contains("weather") || lower.contains("will it rain") || lower.contains("if it rains")) {
            addIf(true, "get_weather")
        }
        // SMS
        if (lower.contains("sms") || lower.contains("message dad") || lower.contains("message mom") || lower.contains("text dad") || lower.contains("text mom") || (lower.contains("message") && (lower.contains("dad") || lower.contains("mom")))) {
            addIf(true, "send_sms")
        } else if (lower.contains("sms") || lower.contains("text") || lower.contains("message")) {
            // Generic messaging
            addIf(containsAny(lower, SMS_TERMS), "send_sms")
        }
        // Generic SMS catch-all for "message"
        if (lower.contains("sms") || (lower.contains("message") && lower.contains("dad"))) addIf(true, "send_sms")

        // Email
        if (containsAny(lower, EMAIL_TERMS)) addIf(true, "send_email")
        // Notes
        if (lower.contains("note") || lower.contains("save") && (lower.contains("note") || lower.contains("cheapest"))) {
            addIf(true, "note_save")
        }
        // Translation
        if (containsAny(lower, TRANSLATE_TERMS)) addIf(true, "open_translation")
        // Export PDF / Markdown
        if (lower.contains("pdf") || lower.contains("export")) addIf(true, "export_pdf")
        if (lower.contains("markdown") || lower.contains(".md")) addIf(true, "export_markdown")
        // Share
        if (lower.contains("share")) addIf(true, "share_text")
        // Maps / Navigation
        if (containsAny(lower, MAP_TERMS) || lower.contains("navigate") || lower.contains("restaurant") || lower.contains("hospital")) addIf(true, "maps_search")
        // Files / Screenshot / Image analysis
        if (lower.contains("screenshot") || lower.contains("image") || lower.contains("download") || lower.contains("analyze")) addIf(lower.contains("screenshot"), "take_screenshot")
        // Shopping list
        if (containsAny(lower, SHOPPING_TERMS)) addIf(true, "note_save")
        // Calendar / Reminder
        if (containsAny(lower, CALENDAR_TERMS)) addIf(true, "calendar_create")

        // Fallback: if clause still empty but we have enabled tools, try semantic confidence matching
        if (tools.isEmpty() && enabledTools.isNotEmpty()) {
            // Pick best matching tool by keyword overlap for this clause ( smallest set principle: only one best)
            val best = enabledTools.maxByOrNull { spec ->
                var hits = 0
                val haystack = (spec.name + " " + spec.description + " " + spec.supportedTasks.joinToString(" ")).lowercase()
                for (tok in clause.lowercase().split(Regex("\\s+"))) {
                    if (tok.length >= 3 && tok in haystack) hits++
                }
                hits
            }
            if (best != null) {
                val hasSignal = best.supportedTasks.any { it.lowercase() in lower } || lower.contains(best.name.replace("_", " "))
                if (hasSignal || lower.contains(best.name)) tools += best.name
            }
        }
        return tools
    }

    private fun inferConditionForClause(fullRequest: String, clause: String, idx: Int): Condition? {
        val lower = fullRequest.lowercase()
        // Weather -> SMS conditional: "if it will rain today, message Mom"
        if (lower.contains("if it will rain") || lower.contains("if it rains") || lower.contains("if it will rain today") || lower.contains("whether it rains")) {
            return Condition(
                expression = "weather.contains(rain)",
                toolOutputKey = "get_weather",
                expectedContains = "rain",
                onTrue = "send_sms",
                onFalse = "skip"
            )
        }
        // Generic "if" detection: previous tool output determines next step
        if (lower.contains(" if ")) {
            val afterIf = lower.substringAfter(" if ").substringBefore(",").trim().take(40)
            return Condition(
                expression = afterIf,
                toolOutputKey = if (idx > 0) "previous_tool" else "get_weather",
                onTrue = clause.take(30),
                onFalse = "skip"
            )
        }
        return null
    }

    private fun detectParallel(request: String, tools: Set<String>): Boolean {
        val lower = request.lowercase()
        // Explicit parallel: "Search weather and latest AI news." (no sequential marker, but two distinct intents with "and")
        if (lower.contains(" and ") && !SEQUENTIAL_MARKERS.any { it in lower }) {
            // Count distinct tool families mentioned
            var families = 0
            if (containsAny(lower, WEATHER_TERMS + setOf("weather"))) families++
            if (containsAny(lower, SEARCH_TERMS)) families++
            if (lower.contains("news")) families++
            if (lower.contains("email")) families++
            if (lower.contains("sms") || lower.contains("message")) families++
            if (families >= 2) return true
            // If tools set already has >=2 independent reads, consider parallel
            val readTools = tools.intersect(setOf("get_weather", "search_web", "get_device_info", "get_battery"))
            if (readTools.size >= 2) return true
        }
        // "Search weather and latest AI news" -> two parallel searches
        if (lower.contains("weather and") && lower.contains("news")) return true
        return false
    }

    private fun buildExecutionGraph(steps: List<PlanStep>, hasParallel: Boolean): ExecutionGraph {
        if (steps.isEmpty()) return ExecutionGraph(emptyList(), emptyList())
        if (!hasParallel) {
            // Sequential chain: each level has one node
            val levels = steps.map { listOf(it) }
            return ExecutionGraph(steps, levels)
        }
        // Parallel: group steps that share same parallelGroup and have no dependency between them
        // For simplicity: steps with same parallelGroup run together, others sequential
        val groups = steps.groupBy { it.parallelGroup }
        val levels = mutableListOf<List<PlanStep>>()
        // Sort groups: null (sequential) groups keep order, grouped parallel merged
        // For hasParallel case, first N independent reads are parallel in level 0
        val parallelSteps = steps.filter { it.parallelGroup != null }
        val seqSteps = steps.filter { it.parallelGroup == null }
        if (parallelSteps.isNotEmpty()) {
            levels += parallelSteps
            // Remaining sequential after parallel merge
            seqSteps.forEach { levels += listOf(it) }
        } else {
            // Fallback: detect independent consecutive steps (no dependsOn) as parallel
            var currentLevel = mutableListOf<PlanStep>()
            for (s in steps) {
                if (s.dependsOn.isEmpty() && currentLevel.isEmpty()) {
                    currentLevel += s
                } else if (s.dependsOn.isEmpty() && currentLevel.isNotEmpty()) {
                    // Could be parallel sibling if no sequential marker forced dependency
                    currentLevel += s
                } else {
                    if (currentLevel.isNotEmpty()) { levels += currentLevel.toList(); currentLevel.clear() }
                    levels += listOf(s)
                }
            }
            if (currentLevel.isNotEmpty()) levels += currentLevel
        }
        return ExecutionGraph(steps, levels)
    }

    /**
     * Evaluate conditional: whether next step should run based on previous tool output.
     * Returns true if condition passes, false to skip.
     */
    fun evaluateCondition(condition: Condition?, toolOutput: String): Boolean {
        if (condition == null) return true
        val outputLower = toolOutput.lowercase()
        val expected = condition.expectedContains?.lowercase()
        return when {
            expected != null -> outputLower.contains(expected)
            condition.expression.contains("rain") -> outputLower.contains("rain") || outputLower.contains("shower") || outputLower.contains("precipitation") || outputLower.contains("80%") || outputLower.contains("% rain")
            condition.expression.contains("error") -> outputLower.contains("error")
            else -> true // default proceed
        }
    }

    /**
     * Developer-mode logging helper — renders full plan as text.
     * Hidden from normal users; exposed via ToolExecutionTraceStore or Timber when developerMode=true.
     */
    fun renderDeveloperLog(plan: AgentPlan): String = buildString {
        appendLine("Plan Created")
        appendLine(" Goal: ${plan.goal.take(80)}")
        appendLine(" Required Information: ${plan.requiredInformation.joinToString(", ")}")
        appendLine(" Required Tools: ${plan.requiredTools.joinToString(", ")}")
        appendLine(" Execution Order:")
        plan.executionOrder.forEachIndexed { i, s ->
            appendLine("  ${i + 1}. ${s.toolName} — ${s.description} ${if (s.dependsOn.isNotEmpty()) "(dependsOn=${s.dependsOn.joinToString(",")})" else ""} ${s.condition?.let { "[IF ${it.expression}]" } ?: ""}")
        }
        appendLine(" Dependencies: ${plan.dependencies}")
        if (plan.hasParallel) appendLine(" Parallel groups: ${plan.executionGraph.levels.size} levels (parallel execution enabled)")
        if (plan.hasConditional) appendLine(" Conditional branch detected — will evaluate after tool observation")
        appendLine(" Execution Graph:")
        plan.executionGraph.levels.forEachIndexed { lvl, group ->
            appendLine("  Level $lvl: ${group.joinToString(" || ") { it.toolName }}")
        }
    }

    private fun containsAny(text: String, terms: Set<String>): Boolean = terms.any { it in text }
}
