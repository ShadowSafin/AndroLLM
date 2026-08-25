package io.androllm.core.tools.monitoring

import io.androllm.core.tools.api.ToolSpec
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

/**
 * Intelligent tool ranking — picks the best tool when multiple solve the same problem.
 *
 * Ranking criteria (in order):
 * - Accuracy (tool health successRate)
 * - Speed (estimatedLatencyMs + measured avgLatencyMs)
 * - Reliability (healthScore, failure rate)
 * - Cost (FREE < NETWORK < PAID)
 * - Privacy (LOCAL < NETWORK < CLOUD < SENSITIVE)
 * - Local preference (LOCAL backend preferred, on-device preferred)
 * - Previous success rate (healthScore)
 *
 * The planner calls [rank] with a pre-filtered candidate list (e.g. all search tools)
 * and receives them sorted best-first. The top one is executed; on failure the
 * retry engine can try the next.
 */
@Singleton
class ToolRanker @Inject constructor(
    private val healthMonitor: ToolHealthMonitor
) {

    data class RankedTool(
        val spec: ToolSpec,
        val score: Double,
        val reasons: List<String>
    )

    /**
     * Rank [candidates] for the given [query]. Returns best-first.
     */
    fun rank(candidates: List<ToolSpec>, query: String = "", preferLocal: Boolean = true): List<RankedTool> {
        return candidates.map { spec ->
            var score = 0.0
            val reasons = mutableListOf<String>()

            // 1. Health / reliability (0..1) * 40 points
            val health = healthMonitor.healthScore(spec.name)
            score += health * 40
            reasons += "health=${"%.2f".format(health)}"

            // 2. Speed: lower latency better (inverse) * 15 points
            // estimated + measured
            val measured = healthMonitor.getStats(spec.name).avgLatencyMs
            val est = spec.estimatedLatencyMs.toDouble()
            val latency = if (measured > 0) (measured * 0.5 + est * 0.5) else est
            val speedScore = (1.0 - (latency / 20000.0).coerceIn(0.0, 1.0)) * 15
            score += speedScore
            reasons += "latency=${latency.toInt()}ms"

            // 3. Cost *10 (FREE preferred)
            val costScore = (1.0 - spec.cost.rank / 2.0) * 10
            score += costScore
            reasons += "cost=${spec.cost}"

            // 4. Privacy *10 (LOCAL preferred)
            val privacyScore = (1.0 - spec.privacyLevel.rank / 3.0) * 10
            score += privacyScore
            reasons += "privacy=${spec.privacyLevel}"

            // 5. Local preference *10
            if (preferLocal && spec.worksLocally && !spec.isCloudOnly) {
                score += 10
                reasons += "local=+10"
            }
            if (spec.isAvailable) {
                score += 5
                reasons += "available=+5"
            } else {
                score -= 50
                reasons += "unavailable=-50"
            }

            // 6. Accuracy tie-breaker: category match to query intent
            // If query mentions weather and spec is weather tool, boost
            if (query.isNotBlank()) {
                val q = query.lowercase()
                val haystack = (spec.name + " " + spec.description + " " + spec.supportedTasks.joinToString(" ")).lowercase()
                var hits = 0
                for (tok in q.split(Regex("\\s+"))) {
                    if (tok.length >= 3 && tok in haystack) hits++
                }
                score += hits * 2
                if (hits > 0) reasons += "queryHits=$hits"
            }

            RankedTool(spec, score, reasons)
        }.sortedByDescending { it.score }.also { ranked ->
            Timber.i("ToolRanker: ranked ${candidates.size} candidates for '${query.take(40)}' -> ${ranked.joinToString(", ") { "${it.spec.name}:${"%.1f".format(it.score)}" }}")
        }
    }

    /** Convenience: pick single best */
    fun best(candidates: List<ToolSpec>, query: String = ""): ToolSpec? = rank(candidates, query).firstOrNull()?.spec
}
