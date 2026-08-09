package io.androllm.core.accessibility.planner

import io.androllm.core.accessibility.analyzer.UiScreenSnapshot
import io.androllm.core.common.getOrNull
import io.androllm.engine.api.EngineRepository
import io.androllm.engine.api.EngineState
import io.androllm.engine.models.ChatPromptMessage
import io.androllm.engine.models.GenerationConfig
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import timber.log.Timber

/**
 * LLM-driven next-action planner. Feeds the goal, the current screen and the
 * executed steps to the loaded local GGUF model (JSON-Schema-grammar
 * constrained, the same mechanism as the tool planner), and maps the single
 * returned action onto [PlannedAction]. Falls back to
 * [HeuristicActionPlanner] whenever no model is loaded or the model's output
 * cannot be parsed — planning can never brick a task.
 */
@Singleton
class LlmActionPlanner @Inject constructor(
    private val engineRepository: EngineRepository,
    private val heuristic: HeuristicActionPlanner
) : ActionPlanner {

    override suspend fun nextAction(
        goal: String,
        screen: UiScreenSnapshot,
        history: List<PlannedAction>
    ): PlannedAction? {
        // No model → rules.
        if (engineRepository.engineState.value !is EngineState.Ready) {
            return heuristic.nextAction(goal, screen, history)
        }
        val planMessages = listOf(
            ChatPromptMessage(role = "system", content = SYSTEM_PROMPT),
            ChatPromptMessage(role = "user", content = buildUserContent(goal, screen, history))
        )
        val prompt = engineRepository.buildChatPrompt(planMessages, addAssistant = true).getOrNull()
        if (prompt.isNullOrBlank()) return heuristic.nextAction(goal, screen, history)

        val config = GenerationConfig(
            maxTokens = 180,
            temperature = 0.2f,
            topP = 1.0f,
            minP = 0.0f,
            repetitionPenalty = 1.05f,
            jsonSchema = ACTION_SCHEMA,
            reuseKvCache = false
        )
        val output = engineRepository.generateQuiet(prompt, config).getOrNull()
        if (output.isNullOrBlank()) return heuristic.nextAction(goal, screen, history)

        return parse(output) ?: heuristic.nextAction(goal, screen, history)
    }

    private fun parse(raw: String): PlannedAction? {
        val json = runCatching {
            Json { ignoreUnknownKeys = true }.parseToJsonElement(raw).jsonObject
        }.getOrNull() ?: return null
        val action = json["action"]?.jsonPrimitive?.contentOrNull ?: return null
        val target = json["target"]?.jsonPrimitive?.contentOrNull
        val text = json["text"]?.jsonPrimitive?.contentOrNull
        val field = json["field"]?.jsonPrimitive?.contentOrNull
        val direction = json["direction"]?.jsonPrimitive?.contentOrNull
        val index = json["index"]?.jsonPrimitive?.intOrNull ?: 0
        return when (action) {
            "click" -> if (target.isNullOrBlank()) null else PlannedAction.Click(target, index)
            "type" -> if (text.isNullOrBlank()) null else PlannedAction.Type(text, field)
            "scroll" -> PlannedAction.Scroll(direction ?: "down")
            "swipe" -> PlannedAction.Swipe(direction ?: "down")
            "back" -> PlannedAction.Back
            "home" -> PlannedAction.Home
            "recents" -> PlannedAction.Recents
            "notifications" -> PlannedAction.Notifications
            "quick_settings" -> PlannedAction.QuickSettings
            "launch" -> if (target.isNullOrBlank()) null else PlannedAction.LaunchApp(target)
            "wait" -> PlannedAction.Wait(json["millis"]?.jsonPrimitive?.longOrNull ?: 1_000L)
            "ask" -> PlannedAction.RequestConfirmation(target ?: text ?: "proceed")
            "done" -> PlannedAction.Done(json["summary"]?.jsonPrimitive?.contentOrNull ?: "")
            else -> null
        }
    }

    private fun buildUserContent(goal: String, screen: UiScreenSnapshot, history: List<PlannedAction>): String = buildString {
        append("GOAL: ").append(goal).append('\n')
        append("CURRENT SCREEN:\n").append(screen.describe(70)).append('\n')
        append("STEPS DONE:\n")
        if (history.isEmpty()) append("  (none)\n")
        else history.forEach { append("  - ").append(it.description).append('\n') }
        append("Output the single next action as JSON.")
    }

    companion object {
        private val SYSTEM_PROMPT = """
            You are the UI automation planner of an on-device AI assistant.
            You receive a user goal, the current screen (App, focused element,
            semantic element list) and the steps already done. Choose the ONE
            next action that moves the goal forward and output ONLY a JSON
            object: {"action": "...", "target": "...", "text": "...", ...}
            Actions: click (target=on-screen label), type (text, optional
            field=hint), scroll/swipe (direction=up|down|left|right),
            back, home, recents, notifications, quick_settings, launch
            (target=app name), wait (millis), ask (summary) when the goal
            needs user input, done (summary) when the goal is satisfied.
            Rules:
            - Click only elements listed on screen; use their exact visible text.
            - After typing a search query, click the search/go button.
            - Before tapping anything that sends, pays, books, deletes or
              installs, emit ask instead — never perform it silently.
            - If the screen shows a permission/update dialog, click allow/ok.
            - Never repeat an action that is already in STEPS DONE.
            - Respond with the JSON object only — no prose, no markdown.
        """.trimIndent()

        private val ACTION_SCHEMA = """
        {
          "type": "object",
          "properties": {
            "action": {
              "type": "string",
              "enum": ["click", "type", "scroll", "swipe", "back", "home",
                       "recents", "notifications", "quick_settings", "launch",
                       "wait", "ask", "done"]
            },
            "target": { "type": "string" },
            "text": { "type": "string" },
            "field": { "type": "string" },
            "direction": { "type": "string", "enum": ["up", "down", "left", "right"] },
            "index": { "type": "integer" },
            "millis": { "type": "integer" },
            "summary": { "type": "string" }
          },
          "required": ["action"]
        }
        """
    }
}
