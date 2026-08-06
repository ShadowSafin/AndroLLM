package io.androllm.core.cloud.network

import kotlin.random.Random

/**
 * Backoff policy for LiteLLM requests. Retries happen on transport failures
 * and the HTTP statuses LiteLLM proxies surface for rate limits / server
 * hiccups: 408 (timeout), 429 (rate limited), 500/502/503/504 (gateway).
 *
 * Each retry waits `initialDelayMs * factor^(attempt-1) + jitter` so bursts
 * of parallel requests don't thundering-herd the proxy.
 */
class RetryPolicy(
    val maxAttempts: Int = 3,
    val initialDelayMs: Long = 500,
    val maxDelayMs: Long = 15_000,
    val factor: Double = 2.0,
    val jitterMs: Long = 200
) {

    init {
        require(maxAttempts >= 1) { "maxAttempts must be >= 1" }
        require(initialDelayMs >= 0 && maxDelayMs >= initialDelayMs) { "invalid delay bounds" }
    }

    /**
     * Backoff to sleep before retrying the failed [failedAttempt] (1-based).
     * Returns 0 when [failedAttempt] is the last allowed attempt (nothing to
     * wait for) or the caller asked for a non-positive attempt.
     */
    fun delayMsForAttempt(failedAttempt: Int): Long {
        require(failedAttempt >= 1) { "attempt must be >= 1" }
        if (failedAttempt >= maxAttempts) return 0
        var base = initialDelayMs.toDouble()
        repeat(failedAttempt - 1) { base *= factor }
        val capped = base.coerceAtMost(maxDelayMs.toDouble())
        val jitter = if (jitterMs > 0) Random.nextLong(-jitterMs, jitterMs) else 0L
        return (capped + jitter).coerceAtLeast(0.0).toLong()
    }
}
