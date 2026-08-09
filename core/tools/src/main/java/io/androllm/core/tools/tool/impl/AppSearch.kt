package io.androllm.core.tools.tool.impl

/**
 * A single app known to the launcher, indexed from PackageManager. The LLM
 * only ever sees the [label]; everything else is resolved here.
 */
data class AppEntry(
    val label: String,
    val packageName: String,
    /** True when the app exposes a LAUNCHER activity. */
    val hasLauncher: Boolean,
    /** Ready-to-launch intent when [hasLauncher], else null. */
    val launchIntent: android.content.Intent?,
    /** Coarse bucket for display/debug: Social, Media, Tools, Games, System, Other. */
    val category: String = "Other"
)

/** Outcome of a label search. */
data class AppSearchResult(
    /** Best matches, best first (bounded). */
    val matches: List<AppEntry>,
    /** True when several apps tie closely — the caller must ask the user. */
    val ambiguous: Boolean
)

/**
 * Pure app matching — no PackageManager here, so it is fully unit-testable.
 *
 * Match tiers (higher wins):
 *  100  exact package name
 *   95  exact label (case-insensitive)
 *   90  normalized label == normalized query (ignores spaces/punctuation/case)
 *   85  alias hit (e.g. "yt" → YouTube, "insta" → Instagram)
 *   80  label starts with query
 *   70  label contains query
 *   65  normalized label contains normalized query
 *
 * "Open Discord" → "discord" → label contains → com.discord. "YT" → alias →
 * YouTube. If Discord and Discord Canary both match closely the result is
 * flagged ambiguous and the tool asks the user instead of guessing.
 */
object AppSearch {

    // Only entries where the spoken/typed shorthand differs from the label
    // ("yt" → YouTube, "insta" → Instagram). Everything else already scores
    // through the fuzzy tiers below — self-mapping entries here would narrow
    // matching to an exact label (e.g. "chrome" would miss "Google Chrome").
    private val ALIASES = mapOf(
        "yt" to "youtube",
        "insta" to "instagram",
        "ig" to "instagram",
        "fb" to "facebook",
        "wa" to "whatsapp",
        "playstore" to "play store"
    )

    fun search(entries: List<AppEntry>, rawQuery: String): AppSearchResult {
        val query = rawQuery.trim()
        if (query.isEmpty()) return AppSearchResult(emptyList(), false)

        val q = query.lowercase()
        val qNorm = normalize(q)

        // Alias expansion first ("yt" → YouTube).
        val aliasTarget = ALIASES[q]
        if (aliasTarget != null) {
            val aliased = entries
                .filter { it.label.lowercase() == aliasTarget || normalize(it.label.lowercase()) == normalize(aliasTarget) }
                .sortedBy { it.label }
            if (aliased.isNotEmpty()) {
                return AppSearchResult(aliased, ambiguous = aliased.size > 1)
            }
        }

        val scored = entries.mapNotNull { entry ->
            val label = entry.label
            val score = score(entry, q, qNorm)
            if (score == null) null else entry to score
        }.sortedByDescending { it.second }

        val top = scored.firstOrNull()?.second ?: return AppSearchResult(emptyList(), false)
        // Keep everything within 10 points of the winner (best-first).
        val matches = scored.takeWhile { top - it.second <= 10 }.map { it.first }
        // Ambiguous when the top two are close (within 15 points) — guessing
        // between "Discord" and "Discord Canary" is worse than asking.
        val ambiguous = matches.size > 1 &&
            (scored.getOrNull(1)?.second ?: 0) >= top - 15
        return AppSearchResult(matches.take(5), ambiguous)
    }

    private fun score(entry: AppEntry, q: String, qNorm: String): Int? {
        val label = entry.label
        val pkg = entry.packageName
        val l = label.lowercase()
        val lNorm = normalize(l)

        if (pkg.equals(q, ignoreCase = true)) return 100
        if (l == q) return 95
        if (lNorm == qNorm) return 90
        if (l.startsWith(q)) return 80
        if (l.contains(q)) return 70
        if (lNorm.contains(qNorm)) return 65
        return null
    }

    /** Lowercases and strips everything except letters/digits. */
    private fun normalize(s: String): String =
        s.lowercase().filter { it.isLetterOrDigit() }
}
