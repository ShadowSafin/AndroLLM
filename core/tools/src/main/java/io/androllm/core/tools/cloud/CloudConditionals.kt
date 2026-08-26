package io.androllm.core.tools.cloud

import kotlinx.serialization.json.JsonObject

/**
 * Conditional-logic evaluator for cloud tool workflows.
 *
 * Handles the "IF observation THEN action" patterns cloud models cannot
 * always be trusted to honor on their own:
 *
 * - "check the weather, then message Mom **if it rains**" → the messaging
 *   call is skipped when the observed weather shows no rain.
 * - "search for X and **if you find anything**, email it to me" → the email
 *   call is skipped when the search observed no results.
 *
 * The evaluator is conservative: it only skips when the user's request
 * clearly carries a condition AND the observed tool output clearly fails it.
 * Ambiguous cases return null (proceed) — a wrongly-executed confirmation
 * tool is worse than a double-check, but a wrongly-skipped action breaks the
 * user's intent.
 *
 * Pure JVM logic; unit-testable in isolation.
 */
object CloudConditionals {

    /** Tools that send/act and are therefore conditional candidates. */
    private val ACTION_TOOLS = setOf(
        "send_sms", "send_email", "make_call", "note_save",
        "calendar", "share_text", "open_navigation"
    )

    /** Query keywords identifying each action tool (for phrase association). */
    private val ACTION_KEYWORDS: Map<String, List<String>> = mapOf(
        "send_email" to listOf("email", "mail"),
        "send_sms" to listOf("sms", "text", "message"),
        "make_call" to listOf("call"),
        "note_save" to listOf("note"),
        "calendar" to listOf("calendar", "event"),
        "share_text" to listOf("share"),
        "open_navigation" to listOf("navigate", "navigation", "directions")
    )

    private val RAIN_CONDITION_PATTERNS = listOf(
        "if it rains", "if it rain", "if it's raining", "if its raining",
        "if rain", "if there is rain", "if there's rain", "in case of rain",
        "if it will rain", "if it is going to rain", "should it rain",
        "if raining", "when it rains", "if precipitation"
    )

    private val RESULTS_CONDITION_PATTERNS = listOf(
        "if you find", "if found", "if there are results", "if any results",
        "if results are found", "if something is found", "if anything is found",
        "if you find anything", "if you find something", "if there are any",
        "only if found", "if available"
    )

    private val RAIN_INDICATORS = listOf(
        "rain", "raining", "rainy", "showers", "shower", "drizzle",
        "thunderstorm", "thunder", "precipitation", "sleet", "downpour"
    )

    private val NO_RAIN_INDICATORS = listOf(
        "clear sky", "clear skies", "sunny", "no rain", "0% rain", "0 %",
        "0 mm", "fair", "partly cloudy", "mostly sunny", "dry"
    )

    private val NO_RESULTS_INDICATORS = listOf(
        "no results", "nothing found", "0 results", "no relevant",
        "could not find", "couldn't find", "not found", "empty result"
    )

    /**
     * Returns a skip reason when the user's conditional is clearly NOT met by
     * the observed tool outputs, or null to proceed with the call.
     *
     * [observations] is the tool working memory (tool name → last output),
     * as maintained by the run coordinator after every executed call.
     */
    fun evaluateSkip(
        userQuery: String,
        callName: String,
        arguments: JsonObject? = null,
        observations: Map<String, String>
    ): String? {
        if (userQuery.isBlank() || callName.isBlank()) return null
        if (callName !in ACTION_TOOLS) return null
        val query = userQuery.lowercase()

        // ── Weather condition: "message Mom if it rains" ──────────────────
        if (conditionAppliesToCall(query, RAIN_CONDITION_PATTERNS, callName)) {
            val weather = observe(observations, "get_weather", "weather")
            if (weather != null) {
                val w = weather.lowercase()
                // Strip negations first so "no rain" never counts as rain.
                val withoutNegation = w
                    .replace("no rain", " ")
                    .replace("without rain", " ")
                    .replace("0% rain", " ")
                    .replace("0 % rain", " ")
                    .replace("0mm", " ")
                    .replace("0 mm", " ")
                val rainSeen = RAIN_INDICATORS.any { it in withoutNegation }
                val noRainSeen = NO_RAIN_INDICATORS.any { it in w }
                // Skip only when the forecast is clearly dry; a mixed signal
                // ("partly cloudy, 30% chance of showers") proceeds — the
                // confirmation gate still protects the actual send.
                if (!rainSeen && noRainSeen) {
                    return "the observed weather reports no rain, and the user asked for this only if it rains"
                }
                if (noRainSeen && rainSeen) {
                    // Conflicting indicators — proceed (conservative).
                    return null
                }
            }
            // No weather observed yet: proceed — the model should have called
            // weather first, and the confirmation gate protects the send.
            return null
        }

        // ── Search-results condition: "email it if you find anything" ─────
        if (conditionAppliesToCall(query, RESULTS_CONDITION_PATTERNS, callName)) {
            val results = observe(
                observations,
                "search_web", "search_results", "github", "find_contacts", "search_places"
            )
            if (results != null) {
                val r = results.lowercase()
                val nothingFound = NO_RESULTS_INDICATORS.any { it in r }
                if (nothingFound || r.isBlank()) {
                    return "the search produced no results, and the user asked for this only if something was found"
                }
            }
            return null
        }

        return null
    }

    /**
     * True when a conditional phrase from [patterns] binds to [callName].
     *
     * Binding rule (phrase association): for every occurrence of a
     * conditional phrase, the condition attaches to the FIRST action
     * keyword that follows it — "if you find anything **email** it to me;
     * also save a note" binds to send_email, not note_save. When no action
     * keyword follows the phrase, it attaches to the NEAREST action keyword
     * before it — "send an **sms** to Mom if it rains" binds to send_sms.
     * When neither side names a known action, the condition is unbound and
     * no call is skipped (the confirmation gate still protects sends).
     */
    private fun conditionAppliesToCall(
        query: String,
        patterns: List<String>,
        callName: String
    ): Boolean {
        for (pattern in patterns) {
            var start = query.indexOf(pattern)
            while (start >= 0) {
                val afterPhrase = query.substring(start + pattern.length)
                val boundTo = firstActionKeyword(afterPhrase)
                    ?: lastActionKeyword(query.substring(0, start))
                if (boundTo == callName) return true
                start = query.indexOf(pattern, start + 1)
            }
        }
        return false
    }

    /** The action whose keyword appears earliest in [text], or null. */
    private fun firstActionKeyword(text: String): String? {
        var best: String? = null
        var bestPos = Int.MAX_VALUE
        for ((action, keywords) in ACTION_KEYWORDS) {
            for (keyword in keywords) {
                val pos = text.indexOf(keyword)
                if (pos in 0 until bestPos) {
                    bestPos = pos
                    best = action
                }
            }
        }
        return best
    }

    /** The action whose keyword appears nearest to the end of [text], or null. */
    private fun lastActionKeyword(text: String): String? {
        var best: String? = null
        var bestPos = -1
        for ((action, keywords) in ACTION_KEYWORDS) {
            for (keyword in keywords) {
                val pos = text.lastIndexOf(keyword)
                if (pos > bestPos) {
                    bestPos = pos
                    best = action
                }
            }
        }
        return best
    }

    /** Finds the most recent observation for any of the given tool keys. */
    private fun observe(observations: Map<String, String>, vararg keys: String): String? {
        for (key in keys) {
            observations[key]?.takeIf { it.isNotBlank() }?.let { return it }
            observations["last_${key}_output"]?.takeIf { it.isNotBlank() }?.let { return it }
        }
        // Generic last-output fallback for workflows where the exact tool
        // name differs (aliased tools, provider renames).
        return observations["last_tool_output"]?.takeIf { it.isNotBlank() }
    }
}
