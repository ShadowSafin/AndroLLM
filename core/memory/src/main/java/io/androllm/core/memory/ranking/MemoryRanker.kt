package io.androllm.core.memory.ranking

import io.androllm.core.memory.model.MemorySearchResult
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Unified relevance ranking shared by every model path.
 *
 * Local and cloud generation both call [MemoryManager.buildContext], which
 * ranks through here — so a fact stored while chatting with one model
 * surfaces identically when the user switches to another model. Semantic
 * similarity dominates; recency, importance, and user-preference signals
 * only re-order memories that are already relevant, so a recent but
 * irrelevant memory can never outrank a relevant older one.
 *
 * Score breakdown (all deterministic, side-effect free):
 * - semantic (0..1, weight 1.0): cosine similarity from the vector index,
 *   or 0.6/0.0 in keyword fallback.
 * - keyword boost (+0.06): matched the query by SQL LIKE on content/tags.
 * - preference boost (+0.08 pinned, +0.04 PREFERENCES/PINNED_FACTS):
 *   explicit user preferences outrank incidental facts at equal relevance.
 * - priority weight (+0.02 per effectivePriority point): extractor importance.
 * - recency weight (+0.00..0.08, 30-day linear decay): tie-breaker only.
 * - decay penalty (-0.10): weak (priority<=2), old (>30d), rarely used
 *   (accessCount<=1) memories sink instead of being deleted.
 */
@Singleton
class MemoryRanker @Inject constructor() {

    fun scoreOf(result: MemorySearchResult, now: Long = System.currentTimeMillis()): Float {
        var score = result.score
        if (result.matchedByKeyword) score += KEYWORD_BOOST
        if (result.memory.isPinned) score += PINNED_BOOST
        else if (result.memory.category.name == "PREFERENCES" ||
            result.memory.category.name == "PINNED_FACTS"
        ) score += PREFERENCE_BOOST
        score += (result.memory.effectivePriority.coerceIn(1, 5) * PRIORITY_STEP)
        score += recencyWeight(result.memory.updatedAt, now)
        score -= decayPenalty(
            priority = result.memory.effectivePriority,
            updatedAt = result.memory.updatedAt,
            accessCount = result.memory.accessCount,
            now = now
        )
        return score
    }

    /** 0.08 for <1 day old, 0.0 for >30 days, linear decay between. */
    fun recencyWeight(updatedAt: Long, now: Long = System.currentTimeMillis()): Float {
        val ageMs = (now - updatedAt).coerceAtLeast(0L)
        return ((1f - (ageMs.toFloat() / THIRTY_DAYS_MS).coerceIn(0f, 1f)) * 0.08f)
    }

    /**
     * Weak-memory aging: rarely-used, low-priority memories older than 30
     * days lose 0.10 so fresher or stronger memories win ties. Nothing is
     * deleted — the penalty only affects ordering.
     */
    fun decayPenalty(priority: Int, updatedAt: Long, accessCount: Int, now: Long): Float {
        val ageMs = (now - updatedAt).coerceAtLeast(0L)
        if (priority <= 2 && accessCount <= 1 && ageMs > THIRTY_DAYS_MS) return 0.10f
        return 0f
    }

    /** Stable sort: ranked score desc, pinned first on ties, then priority, recency, id. */
    fun rank(
        results: List<MemorySearchResult>,
        now: Long = System.currentTimeMillis()
    ): List<MemorySearchResult> {
        val scored = results.map { it to scoreOf(it, now) }
        return scored.sortedWith(
            compareByDescending<Pair<MemorySearchResult, Float>> { it.second }
                .thenByDescending { it.first.memory.isPinned }
                .thenByDescending { it.first.memory.effectivePriority }
                .thenByDescending { it.first.memory.updatedAt }
                .thenBy { it.first.memory.id }
        ).map { it.first }
    }

    companion object {
        const val KEYWORD_BOOST = 0.06f
        const val PINNED_BOOST = 0.08f
        const val PREFERENCE_BOOST = 0.04f
        const val PRIORITY_STEP = 0.02f
        const val THIRTY_DAYS_MS = 30L * 24 * 60 * 60 * 1000
    }
}
