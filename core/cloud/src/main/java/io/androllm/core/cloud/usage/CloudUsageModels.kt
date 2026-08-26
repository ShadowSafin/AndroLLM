package io.androllm.core.cloud.usage

import kotlinx.serialization.Serializable

/** Kind of cloud request a usage record describes. */
enum class CloudRequestKind {
    CHAT,
    EMBEDDING,
    HEALTH,
    MODELS
}

/** Normalized failure category — lets the dashboard show *why* requests fail. */
enum class CloudErrorKind {
    NONE,
    TIMEOUT,
    RATE_LIMIT,
    HTTP_ERROR,
    TRANSPORT,
    MALFORMED,
    CANCELLED
}

/**
 * One completed (or failed) cloud request, recorded by the usage meter.
 *
 * Records are the raw material of the usage dashboard: token counts, cost
 * estimate, latency (total + first token), cache behavior, tool-call count
 * and the normalized error category. Kept small and serializable so the
 * store can persist a bounded ring of them.
 */
@Serializable
data class CloudUsageRecord(
    val id: String,
    val timestampMs: Long,
    val providerId: String,
    val providerName: String,
    val modelId: String,
    val kind: CloudRequestKind = CloudRequestKind.CHAT,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val totalTokens: Long = inputTokens + outputTokens,
    /** Tokens the provider served from its own prompt cache (0 = unreported). */
    val cachedTokens: Long = 0,
    /** Estimated cost in millionths of USD (micros) — 1_000_000 = $1.00. */
    val estimatedCostMicros: Long = 0,
    val latencyMs: Long = 0,
    /** Time to first content token; null for non-streaming / failed calls. */
    val firstTokenMs: Long? = null,
    val success: Boolean = true,
    val errorKind: CloudErrorKind = CloudErrorKind.NONE,
    val errorMessage: String = "",
    /** Retries performed by the transport/pipeline for this request. */
    val retryCount: Int = 0,
    /** True when this request was served by a fallback provider. */
    val usedFallbackProvider: Boolean = false,
    /** Prompt-cache layer hit for this request's stable prefix. */
    val cacheHit: Boolean = false,
    /** Prompt tokens estimated saved by reusing the cached prefix. */
    val cacheSavedTokens: Long = 0,
    /** Number of tool calls the model requested during this turn. */
    val toolCallsCount: Int = 0,
    val finishReason: String = "",
    /** Conversation/session correlation id (empty = untracked). */
    val sessionId: String = "",
    val streamed: Boolean = true
)

/**
 * One calendar day of rolled-up usage. Daily aggregates survive long after
 * individual records rotate out, so month/year trends stay available without
 * keeping every record forever.
 */
@Serializable
data class CloudDailyAggregate(
    /** `yyyy-MM-dd` (UTC) — the rollup key. */
    val dateKey: String,
    val requests: Int = 0,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val totalTokens: Long = 0,
    val cachedTokens: Long = 0,
    val estimatedCostMicros: Long = 0,
    val successes: Int = 0,
    val failures: Int = 0,
    val retries: Int = 0,
    val rateLimitHits: Int = 0,
    val cacheHits: Int = 0,
    val cacheMisses: Int = 0,
    val cacheSavedTokens: Long = 0,
    val toolCalls: Int = 0,
    /** Sum of latencies of measured requests (divide by [latencySamples]). */
    val latencySumMs: Long = 0,
    val latencySamples: Int = 0,
    val firstTokenSumMs: Long = 0,
    val firstTokenSamples: Int = 0
) {
    val avgLatencyMs: Long get() = if (latencySamples > 0) latencySumMs / latencySamples else 0
    val avgFirstTokenMs: Long get() = if (firstTokenSamples > 0) firstTokenSumMs / firstTokenSamples else 0
}

/** Lifetime per-provider counters (survive record rotation). */
@Serializable
data class CloudProviderLifetimeStats(
    val providerId: String,
    val providerName: String = "",
    val requests: Int = 0,
    val successes: Int = 0,
    val failures: Int = 0,
    val retries: Int = 0,
    val rateLimitHits: Int = 0,
    val totalTokens: Long = 0,
    val estimatedCostMicros: Long = 0,
    val toolCalls: Int = 0,
    val cacheHits: Int = 0,
    val cacheMisses: Int = 0,
    val latencySumMs: Long = 0,
    val latencySamples: Int = 0,
    val lastRequestAtMs: Long = 0,
    val lastSuccess: Boolean = true,
    val lastError: String = ""
) {
    val avgLatencyMs: Long get() = if (latencySamples > 0) latencySumMs / latencySamples else 0
    val successRate: Float
        get() = if (requests > 0) successes.toFloat() / requests else 1f
}

/** Lifetime per-model counters (survive record rotation). */
@Serializable
data class CloudModelLifetimeStats(
    val modelId: String,
    val requests: Int = 0,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val totalTokens: Long = 0,
    val estimatedCostMicros: Long = 0,
    val toolCalls: Int = 0,
    val latencySumMs: Long = 0,
    val latencySamples: Int = 0,
    val lastRequestAtMs: Long = 0
) {
    val avgLatencyMs: Long get() = if (latencySamples > 0) latencySumMs / latencySamples else 0
}

/** Whole-persisted-state envelope written by the usage store. */
@Serializable
data class CloudUsageState(
    val records: List<CloudUsageRecord> = emptyList(),
    val daily: List<CloudDailyAggregate> = emptyList(),
    val providerStats: Map<String, CloudProviderLifetimeStats> = emptyMap(),
    val modelStats: Map<String, CloudModelLifetimeStats> = emptyMap(),
    val updatedAtMs: Long = 0
)

/** Dashboard filter: date range + provider + model. Null = no constraint. */
data class CloudUsageFilter(
    val fromMs: Long? = null,
    val toMs: Long? = null,
    val providerId: String? = null,
    val modelId: String? = null
) {
    fun matches(record: CloudUsageRecord): Boolean {
        if (fromMs != null && record.timestampMs < fromMs) return false
        if (toMs != null && record.timestampMs > toMs) return false
        if (providerId != null && record.providerId != providerId) return false
        if (modelId != null && record.modelId != modelId) return false
        return true
    }

    companion object {
        val NONE = CloudUsageFilter()
    }
}

/** Aggregated totals over a set of records (total / today / month cards). */
data class CloudUsageTotals(
    val requests: Int = 0,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val totalTokens: Long = 0,
    val cachedTokens: Long = 0,
    val estimatedCostMicros: Long = 0,
    val successes: Int = 0,
    val failures: Int = 0,
    val retries: Int = 0,
    val rateLimitHits: Int = 0,
    val cacheHits: Int = 0,
    val cacheMisses: Int = 0,
    val cacheSavedTokens: Long = 0,
    val toolCalls: Int = 0,
    val avgLatencyMs: Long = 0,
    val avgFirstTokenMs: Long = 0
) {
    val successRate: Float get() = if (requests > 0) successes.toFloat() / requests else 1f
    val errorRate: Float get() = if (requests > 0) failures.toFloat() / requests else 0f
    val cacheHitRate: Float
        get() {
            val lookups = cacheHits + cacheMisses
            return if (lookups > 0) cacheHits.toFloat() / lookups else 0f
        }
}

/** One point of the daily trend series (tokens / cost / latency). */
data class CloudDailyPoint(
    val dateKey: String,
    val requests: Int,
    val totalTokens: Long,
    val inputTokens: Long,
    val outputTokens: Long,
    val estimatedCostMicros: Long,
    val avgLatencyMs: Long,
    val avgFirstTokenMs: Long,
    val failures: Int,
    val rateLimitHits: Int
)

/** A dashboard warning (quota close, error spike, rate-limit spike...). */
data class CloudUsageAlert(
    val id: String,
    val severity: Severity,
    val title: String,
    val detail: String
) {
    enum class Severity { INFO, WARNING, CRITICAL }
}

/**
 * Everything the usage dashboard renders, precomputed in one snapshot so the
 * UI never does heavy aggregation on the main thread.
 */
data class CloudUsageSnapshot(
    val generatedAtMs: Long,
    val currentProviderId: String = "",
    val currentProviderName: String = "",
    val currentModelId: String = "",
    val total: CloudUsageTotals = CloudUsageTotals(),
    val today: CloudUsageTotals = CloudUsageTotals(),
    val month: CloudUsageTotals = CloudUsageTotals(),
    val perProvider: List<CloudProviderLifetimeStats> = emptyList(),
    val perModel: List<CloudModelLifetimeStats> = emptyList(),
    /** Last [trendDays] days, oldest first. */
    val daily: List<CloudDailyPoint> = emptyList(),
    /** Request counts per hour of today (index 0 = 00:00 local). */
    val hourlyRequests: List<Int> = List(24) { 0 },
    val activeSessions: Int = 0,
    val lastRequest: CloudUsageRecord? = null,
    val lastProviderName: String = "",
    val alerts: List<CloudUsageAlert> = emptyList(),
    val filtered: CloudUsageTotals = CloudUsageTotals(),
    val filter: CloudUsageFilter = CloudUsageFilter.NONE,
    /** Recent records for the history list, newest first. */
    val recentRecords: List<CloudUsageRecord> = emptyList()
)
