package io.androllm.core.tools.monitoring

import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import timber.log.Timber

/**
 * Production-grade health tracking for every tool.
 *
 * Tracks per-tool:
 * - total executions
 * - successes / failures
 * - timeouts
 * - average latency (exponential moving average)
 * - failure rate
 * - success rate
 * - last successful execution timestamp
 * - last error
 *
 * Used by [ToolRanker] to prefer healthier tools and by the retry engine
 * to decide whether to try alternative tools.
 *
 * Thread-safe: all mutations synchronized.
 */
@Singleton
class ToolHealthMonitor @Inject constructor() {

    data class HealthStats(
        val toolName: String,
        val total: Int = 0,
        val successes: Int = 0,
        val failures: Int = 0,
        val timeouts: Int = 0,
        val avgLatencyMs: Double = 0.0,
        val lastSuccessAt: Long = 0L,
        val lastFailureAt: Long = 0L,
        val lastError: String? = null
    ) {
        val successRate: Double get() = if (total == 0) 1.0 else successes.toDouble() / total
        val failureRate: Double get() = if (total == 0) 0.0 else failures.toDouble() / total
        val timeoutRate: Double get() = if (total == 0) 0.0 else timeouts.toDouble() / total
        val isHealthy: Boolean get() = successRate >= 0.7 && failureRate < 0.3
    }

    private val lock = Any()
    private val stats = mutableMapOf<String, HealthStats>()

    fun recordSuccess(toolName: String, latencyMs: Long) {
        synchronized(lock) {
            val prev = stats[toolName] ?: HealthStats(toolName = toolName)
            val newAvg = if (prev.total == 0) latencyMs.toDouble() else (prev.avgLatencyMs * 0.7 + latencyMs * 0.3)
            stats[toolName] = prev.copy(
                total = prev.total + 1,
                successes = prev.successes + 1,
                avgLatencyMs = newAvg,
                lastSuccessAt = System.currentTimeMillis()
            )
        }
        Timber.d("ToolHealthMonitor: $toolName success latency=${latencyMs}ms")
    }

    fun recordFailure(toolName: String, latencyMs: Long, error: String, isTimeout: Boolean = false) {
        synchronized(lock) {
            val prev = stats[toolName] ?: HealthStats(toolName = toolName)
            val newAvg = if (prev.total == 0) latencyMs.toDouble() else (prev.avgLatencyMs * 0.7 + latencyMs * 0.3)
            stats[toolName] = prev.copy(
                total = prev.total + 1,
                failures = prev.failures + 1,
                timeouts = prev.timeouts + if (isTimeout) 1 else 0,
                avgLatencyMs = newAvg,
                lastFailureAt = System.currentTimeMillis(),
                lastError = error.take(200)
            )
        }
        Timber.w("ToolHealthMonitor: $toolName failure timeout=$isTimeout latency=${latencyMs}ms err=${error.take(80)}")
    }

    fun getStats(toolName: String): HealthStats = synchronized(lock) {
        stats[toolName] ?: HealthStats(toolName = toolName)
    }

    fun allStats(): Map<String, HealthStats> = synchronized(lock) { stats.toMap() }

    fun isHealthy(toolName: String): Boolean = getStats(toolName).isHealthy

    /**
     * Health score 0..1 (higher = healthier). Used for ranking.
     * Factors: successRate (0.5 weight), inverse latency (0.2), recency (0.1), timeout penalty (0.2)
     */
    fun healthScore(toolName: String): Double {
        val s = getStats(toolName)
        if (s.total < 3) return 0.85 // not enough data -> assume decent
        val latencyScore = (1.0 - (s.avgLatencyMs / 10000.0).coerceIn(0.0, 0.9))
        val timeoutPenalty = 1.0 - s.timeoutRate
        return (s.successRate * 0.5 + latencyScore * 0.2 + timeoutPenalty * 0.2 + (if (s.isHealthy) 0.1 else 0.0)).coerceIn(0.0, 1.0)
    }

    fun reset(toolName: String? = null) {
        synchronized(lock) {
            if (toolName == null) stats.clear() else stats.remove(toolName)
        }
    }
}
