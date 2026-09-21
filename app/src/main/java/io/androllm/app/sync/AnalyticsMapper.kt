package io.androllm.app.sync

import io.androllm.core.cloud.usage.CloudRequestKind
import io.androllm.core.cloud.usage.CloudUsageRecord
import io.androllm.core.cloud.usage.CloudUsageSnapshot
import io.androllm.core.network.identity.SourcePage
import io.androllm.core.network.identity.SyncEvent
import io.androllm.core.telemetry.DeviceMetrics
import io.androllm.core.telemetry.GenerationStat
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Phase 3 (revised) — maps the app's existing analytics surfaces onto the
 * backend sync payloads. Pure functions (no Android framework beyond data
 * classes) so the mapping rules are unit-testable.
 *
 * Sources (UI untouched — only the data these screens already render):
 * - Cloud usage dashboard → [CloudUsageRecord] (stable UUID ids, persisted ring)
 * - Developer page → [GenerationStat] (in-memory generation history)
 */

private fun Long.coerceToApiInt(): Int = coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()

private fun Long.toIsoUtc(): String = Instant.ofEpochMilli(this).toString()

private val HOUR_BUCKET_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd-HH").withZone(ZoneOffset.UTC)

/** Stable hourly bucket (`2026-09-21-14`) — retries within the hour reuse the snapshot id. */
fun snapshotBucket(sourcePage: String, nowMs: Long): String =
    "$sourcePage:${HOUR_BUCKET_FORMAT.format(Instant.ofEpochMilli(nowMs))}"

fun cloudEventType(kind: CloudRequestKind): String = when (kind) {
    CloudRequestKind.CHAT -> "chat_completed"
    CloudRequestKind.EMBEDDING -> "embedding_completed"
    CloudRequestKind.HEALTH -> "health_check"
    CloudRequestKind.MODELS -> "models_listed"
}

/** One cloud dashboard record → one sync event. Stable id = record.id (dedup key). */
fun CloudUsageRecord.toSyncEvent(): SyncEvent = SyncEvent(
    eventId = id,
    eventType = cloudEventType(kind),
    sessionId = null, // Local conversation ids are not backend sessions; attaching them would reject.
    sourcePage = SourcePage.CLOUD_USAGE_DASHBOARD,
    modelName = modelId.ifBlank { null },
    providerName = providerName.ifBlank { null },
    engineType = "cloud",
    tokensInput = inputTokens.coerceToApiInt(),
    tokensOutput = outputTokens.coerceToApiInt(),
    totalTokens = totalTokens.coerceToApiInt(),
    latencyMs = latencyMs.coerceAtLeast(0L),
    timeToFirstTokenMs = firstTokenMs?.coerceAtLeast(0L),
    success = success,
    errorCode = errorKind.name.takeIf { it != "NONE" },
    errorMessage = errorMessage.take(2000).ifBlank { null },
    metadata = buildJsonObject {
        put("kind", kind.name)
        put("cachedTokens", cachedTokens)
        put("estimatedCostMicros", estimatedCostMicros)
        put("retryCount", retryCount)
        put("usedFallbackProvider", usedFallbackProvider)
        put("cacheHit", cacheHit)
        put("cacheSavedTokens", cacheSavedTokens)
        put("toolCallsCount", toolCallsCount)
        put("finishReason", finishReason)
        put("streamed", streamed)
    },
    createdAt = timestampMs.toIsoUtc(),
)

/** Deterministic dedup key for a local generation (stable across worker retries). */
fun generationKey(stat: GenerationStat): String =
    "devgen-${stat.timestampMs}-${stat.modelName.hashCode()}-${stat.totalTokens}"

/**
 * One developer-page generation → one sync event, counted as a local chat so
 * the website dashboard accrues local tokens. A generation that produced
 * output counts as success; anything else preserves the stop reason.
 */
fun GenerationStat.toSyncEvent(): SyncEvent {
    val succeeded = generatedTokens > 0
    return SyncEvent(
        eventId = generationKey(this),
        eventType = "chat_completed",
        sessionId = null,
        sourcePage = SourcePage.DEVELOPER_PAGE,
        modelName = modelName.ifBlank { null },
        providerName = null,
        engineType = "local",
        tokensInput = promptTokens.coerceToApiInt(),
        tokensOutput = generatedTokens.coerceToApiInt(),
        totalTokens = totalTokens.coerceToApiInt(),
        latencyMs = totalTimeMs.coerceAtLeast(0L),
        timeToFirstTokenMs = firstTokenMs.coerceAtLeast(0L),
        success = succeeded,
        errorCode = if (succeeded) null else stopReason.take(128).ifBlank { "no_output" },
        errorMessage = null,
        metadata = buildJsonObject {
            put("tokensPerSecond", tokensPerSecond)
            put("stopReason", stopReason)
        },
        createdAt = timestampMs.toIsoUtc(),
    )
}

/**
 * Page-level cloud dashboard rollup. Raw records travel as events, so the
 * history list is excluded here to keep the payload small.
 */
fun buildCloudSnapshotPayload(snapshot: CloudUsageSnapshot): JsonObject = buildJsonObject {
    put("generatedAtMs", snapshot.generatedAtMs)
    put("currentProviderId", snapshot.currentProviderId)
    put("currentProviderName", snapshot.currentProviderName)
    put("currentModelId", snapshot.currentModelId)
    put("activeSessions", snapshot.activeSessions)
    putJsonTotals("total", snapshot.total.requests, snapshot.total.inputTokens, snapshot.total.outputTokens,
        snapshot.total.totalTokens, snapshot.total.successes, snapshot.total.failures,
        snapshot.total.avgLatencyMs, snapshot.total.avgFirstTokenMs, snapshot.total.estimatedCostMicros)
    putJsonTotals("today", snapshot.today.requests, snapshot.today.inputTokens, snapshot.today.outputTokens,
        snapshot.today.totalTokens, snapshot.today.successes, snapshot.today.failures,
        snapshot.today.avgLatencyMs, snapshot.today.avgFirstTokenMs, snapshot.today.estimatedCostMicros)
    put("alertCount", snapshot.alerts.size)
}

/** Page-level developer telemetry summary. */
fun buildDeveloperSnapshotPayload(
    generations: List<GenerationStat>,
    deviceMetrics: DeviceMetrics?,
    currentModel: String,
): JsonObject = buildJsonObject {
    put("generationCount", generations.size)
    put("totalTokens", generations.sumOf { it.totalTokens })
    val tps = generations.map { it.tokensPerSecond }.filter { it > 0 }
    put("avgTokensPerSecond", if (tps.isEmpty()) 0.0 else tps.average())
    val lat = generations.map { it.totalTimeMs }.filter { it > 0 }
    put("avgLatencyMs", if (lat.isEmpty()) 0 else lat.average().toLong())
    put("currentModel", currentModel)
    if (deviceMetrics != null) {
        put("deviceModel", deviceMetrics.deviceModel)
        put("totalRamMb", deviceMetrics.totalRamMb)
        put("usedRamMb", deviceMetrics.usedRamMb)
        put("cpuCores", deviceMetrics.cpuCores)
        put("freeStorageBytes", deviceMetrics.freeStorageBytes)
    }
}

private fun kotlinx.serialization.json.JsonObjectBuilder.putJsonTotals(
    key: String,
    requests: Int,
    inputTokens: Long,
    outputTokens: Long,
    totalTokens: Long,
    successes: Int,
    failures: Int,
    avgLatencyMs: Long,
    avgFirstTokenMs: Long,
    estimatedCostMicros: Long,
) {
    put(key, buildJsonObject {
        put("requests", requests)
        put("inputTokens", inputTokens)
        put("outputTokens", outputTokens)
        put("totalTokens", totalTokens)
        put("successes", successes)
        put("failures", failures)
        put("avgLatencyMs", avgLatencyMs)
        put("avgFirstTokenMs", avgFirstTokenMs)
        put("estimatedCostMicros", estimatedCostMicros)
    })
}
