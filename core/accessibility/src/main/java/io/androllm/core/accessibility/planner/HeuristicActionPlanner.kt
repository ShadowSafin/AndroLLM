package io.androllm.core.accessibility.planner

import io.androllm.core.accessibility.analyzer.UiScreenSnapshot
import io.androllm.core.accessibility.tree.UiElementType
import io.androllm.core.accessibility.tree.UiNode
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Deterministic, zero-inference planner. Handles the common flows without
 * ever needing a model loaded: launching an app, searching ("search for X"),
 * messaging ("tell Mom X"), and accepting system dialogs. Used as the fallback
 * by [LlmActionPlanner] and by the executor when LLM planning is disabled.
 */
@Singleton
class HeuristicActionPlanner @Inject constructor() : ActionPlanner {

    override suspend fun nextAction(
        goal: String,
        screen: UiScreenSnapshot,
        history: List<PlannedAction>
    ): PlannedAction? {
        val last = history.lastOrNull()

        // 1. Launch the requested app when it isn't on screen yet.
        appLabelFromGoal(goal)?.let { label ->
            if (last !is PlannedAction.LaunchApp && last !is PlannedAction.Wait &&
                !screen.packageName.contains(label.lowercase().substringBefore(' '), ignoreCase = true)
            ) {
                return PlannedAction.LaunchApp(label)
            }
        }
        // Give the freshly launched app a beat to render.
        if (last is PlannedAction.LaunchApp) return PlannedAction.Wait(1400)

        // 2. Accept benign system dialogs (permission / update / continue).
        if (last !is PlannedAction.Click && last !is PlannedAction.Type) {
            screen.dialogs.firstNotNullOfOrNull { dialog ->
                dialog.flatten().firstOrNull { node ->
                    node.type == UiElementType.BUTTON && isAcceptLabel(node.label)
                }
            }?.let { accept ->
                return PlannedAction.Click(accept.label, indexOfButton(screen, accept))
            }
        }

        val searchQuery = searchQueryFromGoal(goal)
        val messageText = messageTextFromGoal(goal)
        val typedAlready = history.any { it is PlannedAction.Type }

        // 3. Type the search query or message into the first field.
        val toType = searchQuery ?: messageText
        if (!typedAlready && toType != null && screen.textFields.isNotEmpty()) {
            val field = screen.textFields.first()
            val into = field.label.ifBlank { field.resourceId.substringAfterLast('/') }
                .takeIf { it.isNotBlank() }
            return PlannedAction.Type(toType, into)
        }

        // 4. After a search query → press the search/go button.
        if (last is PlannedAction.Type && searchQuery != null) {
            val searchButton = screen.buttons.firstOrNull { isSearchButton(it) }
            if (searchButton != null) return PlannedAction.Click(searchButton.label, indexOfButton(screen, searchButton))
            return PlannedAction.Done("Search query entered.")
        }

        // 5. After a message → press send (executor confirms this step).
        if (last is PlannedAction.Type && messageText != null) {
            val sendButton = screen.buttons.firstOrNull { isSendButton(it) }
            if (sendButton != null) return PlannedAction.Click(sendButton.label, indexOfButton(screen, sendButton))
            return PlannedAction.Done("Message is typed and ready to send.")
        }

        // 6. Goal mentions sending but nothing was typed — tell the caller.
        if (messageText != null && screen.textFields.isEmpty()) {
            return PlannedAction.Done("No message field is available on this screen.")
        }

        return PlannedAction.Done()
    }

    private fun indexOfButton(screen: UiScreenSnapshot, button: UiNode): Int =
        screen.buttons.filter { it.label.equals(button.label, ignoreCase = true) }
            .indexOf(button).coerceAtLeast(0)

    private fun isSearchButton(node: UiNode): Boolean {
        val label = node.label.lowercase()
        return label == "search" || label == "go" || label == "submit" || label == "done" ||
            label.contains("search", ignoreCase = true) || label.contains("magnifier", ignoreCase = true)
    }

    private fun isSendButton(node: UiNode): Boolean {
        val label = node.label.lowercase()
        return label.contains("send", ignoreCase = true) ||
            node.contentDescription.contains("send", ignoreCase = true) ||
            label == "→" || label == "\u2713"
    }

    private fun isAcceptLabel(label: String): Boolean {
        val l = label.lowercase().trim()
        return l in setOf("continue", "allow", "ok", "okay", "got it", "accept", "next", "i agree", "yes", "agree") ||
            l.contains("allow", ignoreCase = true)
    }

    private fun appLabelFromGoal(goal: String): String? {
        val m = Regex(
            """(?:open|launch|start)\s+(?:the\s+)?([A-Za-z0-9][A-Za-z0-9 .\-]{0,23}?)(?=\s+(?:and|to|for|then)\b|$)""",
            RegexOption.IGNORE_CASE
        ).find(goal) ?: return null
        val label = m.groupValues[1].trim()
            .replace(Regex("""\s+app$""", RegexOption.IGNORE_CASE), "")
        return label.takeIf { it.isNotBlank() && it.length >= 2 && !it.equals("chat", true) }
    }

    private fun searchQueryFromGoal(goal: String): String? {
        // "search X for Y" / "search for Y" — Y is the query.
        val forMatch = Regex(
            """\b(?:search|look|find)\b.{0,40}?\bfor\s+["']?([^"'\n]{2,80}?)["']?$""",
            RegexOption.IGNORE_CASE
        ).find(goal)
        if (forMatch != null) {
            val q = stripTrailingTarget(forMatch.groupValues[1])
            if (q.isNotBlank()) return q
        }
        // "search Y" (no "for").
        val m = Regex(
            """\b(?:search|look up|look for)\s+(?:for\s+|up\s+)?["']?([^"'\n]{2,60}?)["']?$""",
            RegexOption.IGNORE_CASE
        ).find(goal) ?: return null
        return stripTrailingTarget(m.groupValues[1]).takeIf { it.isNotBlank() }
    }

    /** Removes a trailing " on YouTube" / " in Chrome" clause from a query. */
    private fun stripTrailingTarget(raw: String): String =
        raw.trim().replace(
            Regex("""\s+(?:on|in|with|using)\s+[A-Za-z0-9 .]+$""", RegexOption.IGNORE_CASE),
            ""
        ).trim()

    private fun messageTextFromGoal(goal: String): String? {
        // "saying X" / "with the message X" / quoted text
        val explicit = Regex(
            """(?:saying\s+(?:that\s+)?|with the message\s+|the message is\s+|message:\s*|text:\s*)["']?(.+?)["']?$""",
            RegexOption.IGNORE_CASE
        ).find(goal)
        if (explicit != null) {
            val text = explicit.groupValues[1].trim()
            if (text.isNotBlank() && text.length >= 2) return text
        }
        // Quoted message as a fallback.
        val quoted = Regex("""["']([^"']{2,120})["']""").find(goal)
        if (quoted != null) return quoted.groupValues[1].trim()
        // "tell Mom I'm late" → "I'm late"
        val tell = Regex(
            """(?:tell|text|message)\s+(?:him|her|them|me|[A-Za-z]{2,20})\s+(.+)$""",
            RegexOption.IGNORE_CASE
        ).find(goal)
        if (tell != null) {
            val text = tell.groupValues[1].trim()
            if (text.startsWith("me ", ignoreCase = true) || text.startsWith("me to", ignoreCase = true)) return null
            return text.takeIf { it.isNotBlank() && it.length >= 2 }
        }
        return null
    }
}
