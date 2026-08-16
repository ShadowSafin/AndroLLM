package io.androllm.core.tools.router

import io.androllm.core.tools.api.ToolPermission
import io.androllm.core.tools.api.ToolSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The intent a user request maps to. Determines which tools the LLM may see
 * for this turn — the spec's "classify the user's request BEFORE exposing
 * tools" step. Deterministic keyword classification: no extra LLM round, no
 * hallucinated tool selection, identical output for identical input.
 */
enum class ToolIntent(val displayName: String) {
    /** Request is about attached files — content is already injected, no tools. */
    ATTACHMENT("Attachment"),
    /** Arithmetic / unit or currency conversion — calculator tools only. */
    MATH("Math"),
    /** Device state queries (battery, volume, wifi, …) — device tools only. */
    DEVICE("Device"),
    /** Live info the assistant cannot know (web search / weather). */
    WEB("Web"),
    /** Communication actions (SMS, calls, email, share). */
    COMMUNICATION("Communication"),
    /** Small talk / writing requests — explicitly no tools. */
    NO_TOOLS("No tools"),
    /** Everything else — the full enabled tool set. */
    GENERAL("General")
}

/** Outcome of routing one request: the tools to expose + diagnostics. */
data class RoutedTools(
    val intent: ToolIntent,
    /** Tools the LLM may call this turn, best match first. */
    val specs: List<ToolSpec>,
    /** Per-tool confidence (0..1) for the request, same order as [specs]. */
    val confidence: Map<String, Float>,
    /** Why this route was chosen (UI / log diagnostics). */
    val reason: String
) {
    val isEmpty: Boolean get() = specs.isEmpty()
}

/**
 * Tool Router — decides WHICH tools the LLM sees for a request, so it can
 * never pick the wrong tool "just in case". The model only ever receives the
 * tools relevant to the request (plus the safe GENERAL fallback), and each
 * tool carries a confidence score computed from the query against its name,
 * description and [ToolSpec.supportedTasks].
 *
 * Priority order (spec): Attachment Reader → Vision → Device APIs → Web
 * Search → Calculator → other utilities. In this codebase attachment content
 * is injected into the prompt directly (no reader tool needed), so an
 * attachment-scoped request exposes NO tools — the model already has the
 * content.
 */
@Singleton
class ToolRouter @Inject constructor() {

    /**
     * Routes [query] (the latest user message, lowercase-normalized inside)
     * against [enabledTools] (the user-enabled tool specs). [hasAttachments]
     * is true when the current turn carries attachments.
     */
    fun route(query: String, hasAttachments: Boolean, enabledTools: List<ToolSpec>): RoutedTools {
        val q = normalize(query)

        // 1. Attachment-scoped request → no tools. The extracted content is
        //    already in the prompt; exposing tools invites misuse (the LLM
        //    calling calculate instead of answering from the file).
        if (hasAttachments && referencesAttachments(q)) {
            return RoutedTools(
                intent = ToolIntent.ATTACHMENT,
                specs = emptyList(),
                confidence = emptyMap(),
                reason = "attachments present and the request references them — content is injected, no tools exposed"
            )
        }

        // 2. Intent detection by strongest signal. Order matters: concrete
        //    intents win over the no-tool heuristic so "write a text to mom"
        //    routes to SMS, not to NO_TOOLS. DEVICE runs before MATH so
        //    "battery percentage" routes to the battery tool, not the
        //    calculator.
        val intent = when {
            isDeviceRequest(q) -> ToolIntent.DEVICE
            isMathRequest(q) -> ToolIntent.MATH
            isWebRequest(q) -> ToolIntent.WEB
            isCommunicationRequest(q) -> ToolIntent.COMMUNICATION
            // 3. Explicit no-tool intents (small talk / writing). The spec's
            //    "Write a poem" and "Hello" cases.
            isNoToolRequest(q) -> ToolIntent.NO_TOOLS
            else -> ToolIntent.GENERAL
        }

        val specs = when (intent) {
            ToolIntent.MATH -> pick(enabledTools, MATH_TOOLS)
            ToolIntent.DEVICE -> pick(enabledTools, DEVICE_TOOLS)
            ToolIntent.WEB -> pick(enabledTools, WEB_TOOLS)
            ToolIntent.COMMUNICATION -> pick(enabledTools, COMMUNICATION_TOOLS)
            ToolIntent.GENERAL -> {
                // Generic fallback: everything enabled, but ordered by
                // confidence so the highest-relevance tools come first.
                enabledTools.sortedByDescending { confidence(it, q) }
            }
            else -> emptyList()
        }

        val reason = when (intent) {
            ToolIntent.ATTACHMENT -> "attachments present and the request references them — content is injected, no tools exposed"
            ToolIntent.MATH -> "math request — calculator tools only"
            ToolIntent.DEVICE -> "device-state request — device tools only"
            ToolIntent.WEB -> "live-information request — web/weather tools only"
            ToolIntent.COMMUNICATION -> "communication request — messaging tools only"
            ToolIntent.NO_TOOLS -> "small talk / writing request — no tools needed"
            ToolIntent.GENERAL -> "general request — full tool set"
        }
        return RoutedTools(
            intent = intent,
            specs = specs,
            confidence = specs.associate { it.name to confidence(it, q) },
            reason = reason
        )
    }

    /**
     * Confidence (0..1) that [spec] is the right tool for [query]. Keyword
     * overlap between the query and the tool's name, description and
     * [ToolSpec.supportedTasks]. Used to order GENERAL fallbacks and to let
     * callers threshold out irrelevant tools.
     */
    fun confidence(spec: ToolSpec, query: String): Float {
        val q = normalize(query)
        val haystack = buildString {
            append(spec.name.lowercase())
            append(' ')
            append(spec.description.lowercase())
            spec.supportedTasks.forEach { append(' ').append(it.lowercase()) }
        }
        var hits = 0
        for (token in KEYWORD_TOKENS) {
            if (token in haystack && token in q) hits++
        }
        if (hits == 0) return 0f
        // Rough normalization: more matched keywords → closer to 1.
        return (hits / (hits + 2f)).coerceIn(0f, 1f)
    }

    // ── Intent matchers ────────────────────────────────────────────────────

    private fun referencesAttachments(q: String): Boolean =
        ATTACHMENT_REFERENCE_TERMS.any { it in q } ||
            q.contains(Regex("\\b(this|that|the attached|the uploaded|my)\\b.*\\b(file|log|pdf|doc|document|image|screenshot|sheet|spreadsheet|csv|json|html|markdown|readme)\\b"))

    private fun isNoToolRequest(q: String): Boolean {
        // Greetings / pleasantries.
        if (GREETINGS.any { q.startsWith(it) } && q.length < 40) return true
        // Writing requests the assistant does from its own knowledge.
        return WRITING_VERBS.any { q.startsWith(it) || " $it " in q }
    }

    private fun isMathRequest(q: String): Boolean =
        MATH_TERMS.any { it in q } ||
            // "what is 25 x 67", "23 * 48", "500 / 7" …
            q.contains(Regex("\\d+\\s*[+\\-*x×÷/^]\\s*\\d+")) ||
            q.contains(Regex("\\b(how much is|what is|what's)\\b.*\\b(divided by|multiplied by|percent|percent of|\\+|-|x|×|÷|/|times)\\b"))

    private fun isDeviceRequest(q: String): Boolean =
        DEVICE_TERMS.any { it in q }

    private fun isWebRequest(q: String): Boolean =
        WEB_TERMS.any { it in q }

    private fun isCommunicationRequest(q: String): Boolean =
        COMMUNICATION_TERMS.any { it in q }

    // ── Helpers ────────────────────────────────────────────────────────────

    private fun pick(enabled: List<ToolSpec>, names: Set<String>): List<ToolSpec> {
        val byName = enabled.associateBy { it.name }
        val picked = names.mapNotNull { byName[it] }
        // Include same-logical-capability tools (same [ToolPermission]) so an
        // unregistered sibling still works (e.g. converter tools beside
        // calculate, wifi/bluetooth beside get_battery). Category alone is
        // deliberately NOT used — INFORMATION would drag in every search/
        // weather/device tool and defeat the "calculator only" guarantee.
        val family = picked.flatMap { spec ->
            if (spec.permission == null) return@flatMap emptyList()
            enabled.filter {
                it.name != spec.name && it.permission == spec.permission
            }
        }
        return (picked + family).distinctBy { it.name }
    }

    private fun normalize(query: String): String =
        query.lowercase().replace(Regex("\\s+"), " ").trim()

    companion object {
        /** Math tools (spec: calculator LAST in priority, but the ONLY tool for math). */
        private val MATH_TOOLS = setOf("calculate", "convert_units", "convert_currency")

        /** Device-state tools. */
        private val DEVICE_TOOLS = setOf("get_battery", "get_device_info")

        /** Live-information tools. */
        private val WEB_TOOLS = setOf("search_web", "get_weather")

        /** Communication tools. */
        private val COMMUNICATION_TOOLS = setOf("send_sms", "make_call", "send_email", "share_text")

        private val MATH_TERMS = listOf(
            "calculate", "computation", "compute", "calculator", "arithmetic",
            "math", "mathematical", "equation", "sum", "total", "multiply",
            "multiplication", "divide", "division", "subtract", "addition",
            "percentage", "percent of", "conversion", "convert", "how much is",
            "what is 25", "times"
        )

        private val DEVICE_TERMS = listOf(
            "battery", "charging", "charge", "volume", "brightness", "screen",
            "wifi", "wi-fi", "bluetooth", "flashlight", "device info",
            "device information", "storage", "ram", "cpu", "processor",
            "temperature", "notification", "do not disturb", "airplane mode",
            "battery percentage", "system info", "my phone"
        )

        private val WEB_TERMS = listOf(
            "search", "search the web", "look up", "look it up", "latest news",
            "news", "current", "today's", "who won", "what happened",
            "weather", "forecast", "temperature outside", "price of",
            "google", "how to", "when did"
        )

        private val COMMUNICATION_TERMS = listOf(
            "send a text", "send text", "text ", "sms", "call ", "phone call",
            "call mom", "call dad", "email ", "send an email", "send email",
            "share with", "share this"
        )

        private val WRITING_VERBS = listOf(
            "write", "compose", "draft", "create a poem", "poem", "poetry",
            "story", "essay", "joke", "haiku", "song", "lyrics", "blog post",
            "article about", "explain to me", "explain the concept", "what do you think",
            "opinion", "thank", "thanks"
        )

        private val GREETINGS = listOf(
            "hello", "hi", "hey", "good morning", "good afternoon", "good evening",
            "how are you", "what's up", "whats up", "yo"
        )

        /** Terms that mark a query as being ABOUT the attached files. */
        private val ATTACHMENT_REFERENCE_TERMS = listOf(
            "this file", "the file", "this log", "the log", "the logs",
            "this pdf", "the pdf", "this document", "the document",
            "this image", "the image", "this screenshot", "the screenshot",
            "this csv", "the csv", "this json", "the json", "this html",
            "the html", "this excel", "the excel", "this sheet", "the sheet",
            "this spreadsheet", "the spreadsheet", "the attachment", "attached",
            "uploaded", "the docx", "this docx", "this markdown", "the markdown",
            "errors in this", "errors in the", "summarize this", "summarize the",
            "read this", "read the", "what does this", "what's in this",
            "what is in this", "extract from this", "translate this", "analyze this",
            "analyze the", "review this", "review the", "answer from the",
            "based on the", "based on this", "from the file", "from this file"
        )

        /** Tokens used by [confidence] to score tool relevance. */
        private val KEYWORD_TOKENS = listOf(
            "battery", "weather", "search", "sms", "call", "email", "map",
            "alarm", "reminder", "note", "clipboard", "flashlight", "bluetooth",
            "wifi", "volume", "calendar", "contact", "camera", "screenshot",
            "share", "music", "file", "download", "calculate", "convert",
            "translate", "github", "location", "record", "qr", "launch",
            "app", "variable", "notification", "device"
        )
    }
}
