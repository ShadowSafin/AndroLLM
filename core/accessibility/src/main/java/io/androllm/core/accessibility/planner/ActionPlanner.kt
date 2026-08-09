package io.androllm.core.accessibility.planner

import io.androllm.core.accessibility.analyzer.UiScreenSnapshot

/**
 * Decides the next [PlannedAction] for a [goal] given the current
 * [UiScreenSnapshot] and the plan steps already executed. Called once per
 * loop iteration by the executor; returning null means "cannot plan further".
 *
 * Implementations: [HeuristicActionPlanner] (deterministic rules) and
 * [LlmActionPlanner] (the loaded local GGUF model, falling back to rules).
 */
interface ActionPlanner {
    suspend fun nextAction(
        goal: String,
        screen: UiScreenSnapshot,
        history: List<PlannedAction>
    ): PlannedAction?
}
