package io.androllm.core.cloud.usage

import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * The cloud usage meter: every cloud request (chat, embedding, health,
 * model discovery) is recorded here exactly once, and the dashboard reads
 * precomputed [CloudUsageSnapshot]s from [snapshots].
 *
 * Reliability contract:
 * - [record] NEVER throws — usage accounting must not be able to crash the
 *   cloud pipeline. Every failure path degrades to a log line.
 * - Persistence is debounced and atomic ([FileCloudUsageStore]); a failed
 *   write only means the newest records are re-counted on next launch.
 * - Aggregation is bounded: a ring of the last [MAX_RECORDS] records plus
 *   90 days of daily rollups and lifetime per-provider/per-model counters.
 */
class CloudUsageMeter(
    private val store: CloudUsageStore,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val persistDebounceMs: Long = 2_000
) {

    companion object {
        /** Ring buffer of raw records kept for the history + trend charts. */
        const val MAX_RECORDS = 1_000

        /** Daily rollups kept for long-range trends. */
        const val MAX_DAILY_DAYS = 90

        /** A session counts as "active" while it produced requests in this window. */
        val ACTIVE_SESSION_WINDOW_MS = TimeUnit.MINUTES.toMillis(10)

        /** Alert thresholds. */
        const val ERROR_SPIKE_MIN_REQUESTS = 5
        const val ERROR_SPIKE_MIN_FAILURE_RATE = 0.30f
        const val RATE_LIMIT_SPIKE_HITS = 3
        const val QUOTA_LOW_REMAINING = 50L

        private val DATE_KEY_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.of("UTC"))
    }

    private var state = CloudUsageState()
    private var loaded = false

    private val _snapshots = MutableStateFlow(CloudUsageSnapshot(generatedAtMs = 0))
    /** Latest dashboard snapshot (unfiltered). Refreshed on every record. */
    val snapshots: StateFlow<CloudUsageSnapshot> = _snapshots.asStateFlow()

    private var saveJob: Job? = null
    private var dirty = false

    /** Loads persisted state and publishes the first snapshot. Idempotent. */
    suspend fun init() {
        if (loaded) return
        state = runCatching { store.load() }.getOrElse {
            Timber.w(it, "CloudUsageMeter: store load failed — starting fresh")
            CloudUsageState()
        }
        loaded = true
        publishSnapshot()
        Timber.d("CloudUsageMeter: loaded ${state.records.size} records, ${state.daily.size} daily rollups")
    }

    // ── Recording ─────────────────────────────────────────────────────────

    /**
     * Records one completed cloud request. Safe to call from any thread and
     * guaranteed not to throw — the pipeline treats usage as fire-and-forget.
     */
    fun record(record: CloudUsageRecord) {
        runCatching {
            synchronized(this) {
                val records = (state.records + record).takeLast(MAX_RECORDS)
                val daily = upsertDaily(state.daily, record)
                val providerStats = updateProviderStats(state.providerStats, record)
                val modelStats = updateModelStats(state.modelStats, record)
                state = CloudUsageState(
                    records = records,
                    daily = daily,
                    providerStats = providerStats,
                    modelStats = modelStats,
                    updatedAtMs = clock()
                )
            }
            publishSnapshot()
            scheduleSave()
            Timber.d(
                "CloudUsageMeter: recorded %s/%s tokens=%d latency=%dms ok=%s cache=%s tools=%d",
                record.providerName, record.modelId, record.totalTokens,
                record.latencyMs, record.success, record.cacheHit, record.toolCallsCount
            )
        }.onFailure { e ->
            Timber.w(e, "CloudUsageMeter: record failed — usage counters may lag")
        }
    }

    /** Convenience factory: builds a record with cost already estimated. */
    fun buildRecord(
        providerId: String,
        providerName: String,
        modelId: String,
        kind: CloudRequestKind = CloudRequestKind.CHAT,
        inputTokens: Long = 0,
        outputTokens: Long = 0,
        cachedTokens: Long = 0,
        latencyMs: Long = 0,
        firstTokenMs: Long? = null,
        success: Boolean = true,
        errorKind: CloudErrorKind = CloudErrorKind.NONE,
        errorMessage: String = "",
        retryCount: Int = 0,
        usedFallbackProvider: Boolean = false,
        cacheHit: Boolean = false,
        cacheSavedTokens: Long = 0,
        toolCallsCount: Int = 0,
        finishReason: String = "",
        sessionId: String = "",
        streamed: Boolean = true
    ): CloudUsageRecord {
        val total = if (inputTokens + outputTokens > 0) inputTokens + outputTokens else 0
        return CloudUsageRecord(
            id = UUID.randomUUID().toString(),
            timestampMs = clock(),
            providerId = providerId,
            providerName = providerName,
            modelId = modelId,
            kind = kind,
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            totalTokens = total,
            cachedTokens = cachedTokens,
            estimatedCostMicros = CloudPricing.estimateCostMicros(modelId, inputTokens, outputTokens),
            latencyMs = latencyMs,
            firstTokenMs = firstTokenMs,
            success = success,
            errorKind = errorKind,
            errorMessage = errorMessage.take(200),
            retryCount = retryCount,
            usedFallbackProvider = usedFallbackProvider,
            cacheHit = cacheHit,
            cacheSavedTokens = cacheSavedTokens,
            toolCallsCount = toolCallsCount,
            finishReason = finishReason,
            sessionId = sessionId,
            streamed = streamed
        )
    }

    // ── Queries ───────────────────────────────────────────────────────────

    /** Computes a filtered snapshot for the dashboard's current filter. */
    fun snapshot(filter: CloudUsageFilter = CloudUsageFilter.NONE): CloudUsageSnapshot {
        val base = _snapshots.value
        if (filter == CloudUsageFilter.NONE) return base
        return runCatching {
            val now = clock()
            val matching = state.records.filter { filter.matches(it) }
            base.copy(
                filter = filter,
                filtered = aggregate(matching),
                recentRecords = matching.asReversed().take(100),
                generatedAtMs = now
            )
        }.getOrElse { base }
    }

    /** Raw records (newest first) for the detailed history view. */
    fun records(filter: CloudUsageFilter = CloudUsageFilter.NONE, limit: Int = 200): List<CloudUsageRecord> =
        runCatching {
            state.records.asReversed().filter { filter.matches(it) }.take(limit)
        }.getOrDefault(emptyList())

    /**
     * Exports the recorded usage as CSV (one row per record) for the
     * dashboard's export action. Never throws — returns an empty CSV header
     * on any failure.
     */
    fun exportCsv(filter: CloudUsageFilter = CloudUsageFilter.NONE): String = runCatching {
        val rows = state.records.filter { filter.matches(it) }
        buildString {
            appendLine(
                "timestamp_iso,provider_id,provider_name,model_id,kind,input_tokens,output_tokens," +
                    "total_tokens,cached_tokens,estimated_cost_usd,latency_ms,first_token_ms,success," +
                    "error_kind,retry_count,used_fallback,cache_hit,cache_saved_tokens,tool_calls," +
                    "finish_reason,session_id"
            )
            for (r in rows) {
                val iso = Instant.ofEpochMilli(r.timestampMs).toString()
                appendLine(
                    listOf(
                        iso,
                        csv(r.providerId), csv(r.providerName), csv(r.modelId), r.kind.name,
                        r.inputTokens, r.outputTokens, r.totalTokens, r.cachedTokens,
                        "%.6f".format(r.estimatedCostMicros / 1_000_000.0),
                        r.latencyMs, r.firstTokenMs ?: "", r.success,
                        r.errorKind.name, r.retryCount, r.usedFallbackProvider,
                        r.cacheHit, r.cacheSavedTokens, r.toolCallsCount,
                        csv(r.finishReason), csv(r.sessionId)
                    ).joinToString(",")
                )
            }
        }
    }.getOrElse { "timestamp_iso,error\n,\"export failed\"\n" }

    /** Writes the CSV export to [dir] and returns the file (null on failure). */
    fun exportCsvTo(dir: File, filter: CloudUsageFilter = CloudUsageFilter.NONE): File? = runCatching {
        if (!dir.exists()) dir.mkdirs()
        val stamp = Instant.ofEpochMilli(clock()).toString().replace(":", "-").take(19)
        val out = File(dir, "cloud-usage-$stamp.csv")
        out.writeText(exportCsv(filter), Charsets.UTF_8)
        out
    }.getOrElse { e ->
        Timber.w(e, "CloudUsageMeter: CSV export failed")
        null
    }

    /** Clears all usage data (records, rollups, lifetime counters). */
    suspend fun clear() {
        runCatching {
            synchronized(this) { state = CloudUsageState() }
            store.clear()
            publishSnapshot()
            Timber.i("CloudUsageMeter: usage data cleared")
        }.onFailure { e -> Timber.w(e, "CloudUsageMeter: clear failed") }
    }

    /** Forces a persistence flush (app backgrounding / shutdown). */
    suspend fun flush() {
        saveJob?.cancel()
        saveJob = null
        if (dirty) saveNow()
    }

    // ── Internals ─────────────────────────────────────────────────────────

    private fun scheduleSave() {
        dirty = true
        if (saveJob?.isActive == true) return
        saveJob = scope.launch {
            delay(persistDebounceMs)
            saveNow()
        }
    }

    private suspend fun saveNow() {
        val snapshotState = synchronized(this) { state }
        runCatching { store.save(snapshotState) }
            .onSuccess { dirty = false }
            .onFailure { e -> Timber.w(e, "CloudUsageMeter: persist failed") }
    }

    private fun dateKey(timestampMs: Long): String =
        DATE_KEY_FORMAT.format(Instant.ofEpochMilli(timestampMs))

    private fun upsertDaily(daily: List<CloudDailyAggregate>, r: CloudUsageRecord): List<CloudDailyAggregate> {
        val key = dateKey(r.timestampMs)
        val existing = daily.find { it.dateKey == key } ?: CloudDailyAggregate(dateKey = key)
        val updated = existing.copy(
            requests = existing.requests + 1,
            inputTokens = existing.inputTokens + r.inputTokens,
            outputTokens = existing.outputTokens + r.outputTokens,
            totalTokens = existing.totalTokens + r.totalTokens,
            cachedTokens = existing.cachedTokens + r.cachedTokens,
            estimatedCostMicros = existing.estimatedCostMicros + r.estimatedCostMicros,
            successes = existing.successes + if (r.success) 1 else 0,
            failures = existing.failures + if (!r.success) 1 else 0,
            retries = existing.retries + r.retryCount,
            rateLimitHits = existing.rateLimitHits + if (r.errorKind == CloudErrorKind.RATE_LIMIT) 1 else 0,
            cacheHits = existing.cacheHits + if (r.cacheHit) 1 else 0,
            cacheMisses = existing.cacheMisses + if (!r.cacheHit && r.kind == CloudRequestKind.CHAT) 1 else 0,
            cacheSavedTokens = existing.cacheSavedTokens + r.cacheSavedTokens,
            toolCalls = existing.toolCalls + r.toolCallsCount,
            latencySumMs = existing.latencySumMs + if (r.latencyMs > 0) r.latencyMs else 0,
            latencySamples = existing.latencySamples + if (r.latencyMs > 0) 1 else 0,
            firstTokenSumMs = existing.firstTokenSumMs + (r.firstTokenMs ?: 0),
            firstTokenSamples = existing.firstTokenSamples + if (r.firstTokenMs != null) 1 else 0
        )
        val merged = if (existing == daily.find { it.dateKey == key }) {
            daily.map { if (it.dateKey == key) updated else it }
        } else daily + updated
        return merged.sortedBy { it.dateKey }.takeLast(MAX_DAILY_DAYS)
    }

    private fun updateProviderStats(
        stats: Map<String, CloudProviderLifetimeStats>,
        r: CloudUsageRecord
    ): Map<String, CloudProviderLifetimeStats> {
        val existing = stats[r.providerId] ?: CloudProviderLifetimeStats(
            providerId = r.providerId, providerName = r.providerName
        )
        val updated = existing.copy(
            providerName = r.providerName.ifBlank { existing.providerName },
            requests = existing.requests + 1,
            successes = existing.successes + if (r.success) 1 else 0,
            failures = existing.failures + if (!r.success) 1 else 0,
            retries = existing.retries + r.retryCount,
            rateLimitHits = existing.rateLimitHits + if (r.errorKind == CloudErrorKind.RATE_LIMIT) 1 else 0,
            totalTokens = existing.totalTokens + r.totalTokens,
            estimatedCostMicros = existing.estimatedCostMicros + r.estimatedCostMicros,
            toolCalls = existing.toolCalls + r.toolCallsCount,
            cacheHits = existing.cacheHits + if (r.cacheHit) 1 else 0,
            cacheMisses = existing.cacheMisses + if (!r.cacheHit && r.kind == CloudRequestKind.CHAT) 1 else 0,
            latencySumMs = existing.latencySumMs + if (r.latencyMs > 0) r.latencyMs else 0,
            latencySamples = existing.latencySamples + if (r.latencyMs > 0) 1 else 0,
            lastRequestAtMs = r.timestampMs,
            lastSuccess = r.success,
            lastError = if (r.success) "" else r.errorMessage.ifBlank { r.errorKind.name }
        )
        return stats + (r.providerId to updated)
    }

    private fun updateModelStats(
        stats: Map<String, CloudModelLifetimeStats>,
        r: CloudUsageRecord
    ): Map<String, CloudModelLifetimeStats> {
        if (r.modelId.isBlank()) return stats
        val existing = stats[r.modelId] ?: CloudModelLifetimeStats(modelId = r.modelId)
        val updated = existing.copy(
            requests = existing.requests + 1,
            inputTokens = existing.inputTokens + r.inputTokens,
            outputTokens = existing.outputTokens + r.outputTokens,
            totalTokens = existing.totalTokens + r.totalTokens,
            estimatedCostMicros = existing.estimatedCostMicros + r.estimatedCostMicros,
            toolCalls = existing.toolCalls + r.toolCallsCount,
            latencySumMs = existing.latencySumMs + if (r.latencyMs > 0) r.latencyMs else 0,
            latencySamples = existing.latencySamples + if (r.latencyMs > 0) 1 else 0,
            lastRequestAtMs = r.timestampMs
        )
        return stats + (r.modelId to updated)
    }

    private fun aggregate(records: List<CloudUsageRecord>): CloudUsageTotals {
        if (records.isEmpty()) return CloudUsageTotals()
        var input = 0L; var output = 0L; var total = 0L; var cached = 0L; var cost = 0L
        var successes = 0; var failures = 0; var retries = 0; var rateLimits = 0
        var cacheHits = 0; var cacheMisses = 0; var cacheSaved = 0L; var toolCalls = 0
        var latencySum = 0L; var latencySamples = 0; var firstTokenSum = 0L; var firstTokenSamples = 0
        for (r in records) {
            input += r.inputTokens; output += r.outputTokens; total += r.totalTokens
            cached += r.cachedTokens; cost += r.estimatedCostMicros
            if (r.success) successes++ else failures++
            retries += r.retryCount
            if (r.errorKind == CloudErrorKind.RATE_LIMIT) rateLimits++
            if (r.kind == CloudRequestKind.CHAT) {
                if (r.cacheHit) cacheHits++ else cacheMisses++
            }
            cacheSaved += r.cacheSavedTokens
            toolCalls += r.toolCallsCount
            if (r.latencyMs > 0) { latencySum += r.latencyMs; latencySamples++ }
            r.firstTokenMs?.let { firstTokenSum += it; firstTokenSamples++ }
        }
        return CloudUsageTotals(
            requests = records.size,
            inputTokens = input,
            outputTokens = output,
            totalTokens = total,
            cachedTokens = cached,
            estimatedCostMicros = cost,
            successes = successes,
            failures = failures,
            retries = retries,
            rateLimitHits = rateLimits,
            cacheHits = cacheHits,
            cacheMisses = cacheMisses,
            cacheSavedTokens = cacheSaved,
            toolCalls = toolCalls,
            avgLatencyMs = if (latencySamples > 0) latencySum / latencySamples else 0,
            avgFirstTokenMs = if (firstTokenSamples > 0) firstTokenSum / firstTokenSamples else 0
        )
    }

    private fun publishSnapshot() {
        runCatching {
            val now = clock()
            val records = state.records
            val todayKey = dateKey(now)
            val monthPrefix = todayKey.take(7) // yyyy-MM
            val todayRecords = records.filter { dateKey(it.timestampMs) == todayKey }
            val monthRecords = records.filter { dateKey(it.timestampMs).startsWith(monthPrefix) }

            val dailyPoints = state.daily.takeLast(14).map {
                CloudDailyPoint(
                    dateKey = it.dateKey,
                    requests = it.requests,
                    totalTokens = it.totalTokens,
                    inputTokens = it.inputTokens,
                    outputTokens = it.outputTokens,
                    estimatedCostMicros = it.estimatedCostMicros,
                    avgLatencyMs = it.avgLatencyMs,
                    avgFirstTokenMs = it.avgFirstTokenMs,
                    failures = it.failures,
                    rateLimitHits = it.rateLimitHits
                )
            }

            val hourly = IntArray(24)
            val localDayStart = java.time.LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
            for (r in todayRecords) {
                val hour = ((r.timestampMs - localDayStart) / 3_600_000L).toInt()
                if (hour in 0..23) hourly[hour]++
            }

            val activeSessions = records
                .filter { it.sessionId.isNotBlank() && now - it.timestampMs <= ACTIVE_SESSION_WINDOW_MS }
                .map { it.sessionId }
                .distinct()
                .count()

            val last = records.lastOrNull()
            _snapshots.value = CloudUsageSnapshot(
                generatedAtMs = now,
                total = aggregate(records),
                today = aggregate(todayRecords),
                month = aggregate(monthRecords),
                perProvider = state.providerStats.values.sortedByDescending { it.requests },
                perModel = state.modelStats.values.sortedByDescending { it.requests },
                daily = dailyPoints,
                hourlyRequests = hourly.toList(),
                activeSessions = activeSessions,
                lastRequest = last,
                lastProviderName = last?.providerName.orEmpty(),
                alerts = computeAlerts(records, now),
                filtered = aggregate(records),
                filter = CloudUsageFilter.NONE,
                recentRecords = records.asReversed().take(100)
            )
        }.onFailure { e ->
            Timber.w(e, "CloudUsageMeter: snapshot computation failed")
        }
    }

    private fun computeAlerts(records: List<CloudUsageRecord>, now: Long): List<CloudUsageAlert> {
        val alerts = mutableListOf<CloudUsageAlert>()
        val lastHour = records.filter { now - it.timestampMs <= 3_600_000L }

        // Error spike: >= 5 requests in the last hour with > 30% failures.
        if (lastHour.size >= ERROR_SPIKE_MIN_REQUESTS) {
            val failures = lastHour.count { !it.success }
            val rate = failures.toFloat() / lastHour.size
            if (rate >= ERROR_SPIKE_MIN_FAILURE_RATE) {
                alerts += CloudUsageAlert(
                    id = "error-spike",
                    severity = CloudUsageAlert.Severity.CRITICAL,
                    title = "Error spike",
                    detail = "${(rate * 100).toInt()}% of the last ${lastHour.size} cloud requests failed."
                )
            }
        }

        // Rate-limit spike: >= 3 rate-limited requests in the last hour.
        val rateLimits = lastHour.count { it.errorKind == CloudErrorKind.RATE_LIMIT }
        if (rateLimits >= RATE_LIMIT_SPIKE_HITS) {
            alerts += CloudUsageAlert(
                id = "rate-limit-spike",
                severity = CloudUsageAlert.Severity.WARNING,
                title = "Rate limits hit",
                detail = "$rateLimits requests were rate-limited in the last hour. Consider slowing down or switching provider."
            )
        }

        // Fallback pressure: provider failures caused fallbacks recently.
        val fallbacks = lastHour.count { it.usedFallbackProvider }
        if (fallbacks >= 2) {
            alerts += CloudUsageAlert(
                id = "fallback-pressure",
                severity = CloudUsageAlert.Severity.WARNING,
                title = "Primary provider struggling",
                detail = "$fallbacks requests fell back to another provider in the last hour."
            )
        }
        return alerts
    }

    private fun csv(value: String): String =
        if (value.contains(',') || value.contains('"') || value.contains('\n')) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else value
}
