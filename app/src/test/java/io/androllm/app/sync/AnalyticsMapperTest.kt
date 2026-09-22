package io.androllm.app.sync

import io.androllm.core.cloud.usage.CloudErrorKind
import io.androllm.core.cloud.usage.CloudRequestKind
import io.androllm.core.cloud.usage.CloudUsageRecord
import io.androllm.core.cloud.usage.CloudUsageSnapshot
import io.androllm.core.cloud.usage.CloudUsageTotals
import io.androllm.core.network.identity.SourcePage
import io.androllm.core.telemetry.DeviceMetrics
import io.androllm.core.telemetry.GenerationStat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 3 (revised) — mapping rules from the existing in-app dashboards to
 * sync payloads. Guards the dedup keys, the local/chat counting contract,
 * and the no-PII shape of page snapshots.
 */
class AnalyticsMapperTest {

    private fun cloudRecord() = CloudUsageRecord(
        id = "rec-uuid-1",
        timestampMs = 1_720_000_000_000,
        providerId = "openai",
        providerName = "OpenAI",
        modelId = "gpt-4o",
        kind = CloudRequestKind.CHAT,
        inputTokens = 100,
        outputTokens = 200,
        latencyMs = 2500,
        firstTokenMs = 800,
    )

    @Test
    fun `cloud record keeps stable id and maps cloud fields`() {
        val event = cloudRecord().toSyncEvent()
        assertEquals("rec-uuid-1", event.eventId)
        assertEquals("chat_completed", event.eventType)
        assertEquals(SourcePage.CLOUD_USAGE_DASHBOARD, event.sourcePage)
        assertEquals("cloud", event.engineType)
        assertEquals(300, event.totalTokens)
        assertEquals(800L, event.timeToFirstTokenMs)
        assertTrue(event.success)
        assertNull(event.errorCode)
        assertNull(event.sessionId) // Local ids must never ride along.
    }

    @Test
    fun `cloud failures preserve normalized error kind`() {
        val event = cloudRecord().copy(
            success = false,
            errorKind = CloudErrorKind.RATE_LIMIT,
            errorMessage = "slow down",
        ).toSyncEvent()
        assertFalse(event.success)
        assertEquals("RATE_LIMIT", event.errorCode)
        assertEquals("slow down", event.errorMessage)
    }

    @Test
    fun `cloud token overflow coerces instead of crashing`() {
        val event = cloudRecord().copy(inputTokens = Long.MAX_VALUE).toSyncEvent()
        assertEquals(Int.MAX_VALUE, event.tokensInput)
    }

    @Test
    fun `generation with output counts as local chat success`() {
        val stat = GenerationStat(
            timestampMs = 1_720_000_000_000,
            promptTokens = 120,
            generatedTokens = 340,
            tokensPerSecond = 22.5f,
            totalTimeMs = 2500,
            firstTokenMs = 800,
            stopReason = "stop",
            modelName = "qwen3-0.6b",
        )
        val event = stat.toSyncEvent()
        assertEquals("chat_completed", event.eventType)
        assertEquals("local", event.engineType)
        assertEquals(SourcePage.DEVELOPER_PAGE, event.sourcePage)
        assertTrue(event.success)
        assertEquals(generationKey(stat), event.eventId)
        assertEquals(generationKey(stat), generationKey(stat.copy()))
    }

    @Test
    fun `generation without output counts as failure with reason`() {
        val stat = GenerationStat(
            timestampMs = 1_720_000_000_001,
            promptTokens = 120,
            generatedTokens = 0,
            tokensPerSecond = 0f,
            totalTimeMs = 5000,
            firstTokenMs = 0,
            stopReason = "context_full",
            modelName = "qwen3-0.6b",
        )
        val event = stat.toSyncEvent()
        assertFalse(event.success)
        assertEquals("context_full", event.errorCode)
    }

    @Test
    fun `snapshot buckets are hourly and stable within the hour`() {        val hourMs = 3_600_000L
        val base = 1_720_000_000_000L / hourMs * hourMs
        assertEquals(
            snapshotBucket(SourcePage.CLOUD_USAGE_DASHBOARD, base),
            snapshotBucket(SourcePage.CLOUD_USAGE_DASHBOARD, base + 59_000),
        )
        assertTrue(
            snapshotBucket(SourcePage.CLOUD_USAGE_DASHBOARD, base) !=
                snapshotBucket(SourcePage.CLOUD_USAGE_DASHBOARD, base + hourMs)
        )
        assertTrue(
            snapshotBucket(SourcePage.CLOUD_USAGE_DASHBOARD, base) !=
                snapshotBucket(SourcePage.DEVELOPER_PAGE, base)
        )
    }

    @Test
    fun `cloud snapshot payload carries rollups without raw history`() {
        val payload = buildCloudSnapshotPayload(
            CloudUsageSnapshot(
                generatedAtMs = 1,
                total = CloudUsageTotals(requests = 10, totalTokens = 3000),
                today = CloudUsageTotals(requests = 3, totalTokens = 900),
            )
        )
        val encoded = payload.toString()
        assertTrue(encoded.contains("3000"))
        assertTrue(!encoded.contains("recentRecords"))
    }

    @Test
    fun `developer snapshot payload summarizes generations and device`() {
        val payload = buildDeveloperSnapshotPayload(
            generations = listOf(
                GenerationStat(1, 100, 200, 20f, 1000, 400, "stop", "qwen3-0.6b"),
                GenerationStat(2, 100, 200, 30f, 1000, 400, "stop", "qwen3-0.6b"),
            ),
            deviceMetrics = DeviceMetrics(
                totalRamMb = 8000, availableRamMb = 4000, cpuCores = 8,
                deviceModel = "Test Device", androidVersion = "14",
                isVulkanSupported = false, totalStorageBytes = 1, usedStorageBytes = 0,
            ),
            currentModel = "qwen3-0.6b",
        )
        val encoded = payload.toString()
        assertTrue(encoded.contains("Test Device"))
        assertTrue(encoded.contains("qwen3-0.6b"))
    }

    @Test
    fun `utc day keys group a day and roll at midnight`() {        // 2026-09-21T13:00:00Z and 23:00 share a day; +11h rolls to the 22nd.
        val day = 1_789_995_600_000L // 2026-09-21T13:00:00Z
        assertEquals("2026-09-21", utcDayKey(day))
        assertEquals("2026-09-21", utcDayKey(day + 10 * 3_600_000L))
        assertEquals("2026-09-22", utcDayKey(day + 11 * 3_600_000L))
    }

    @Test
    fun `session rejections are detected for self-healing retry`() {
        assertTrue(
            hasSessionRejection(
                listOf(
                    io.androllm.core.network.identity.BatchRejection(0, "e1", "unknown or foreign session_id"),
                )
            )
        )
        assertFalse(
            hasSessionRejection(
                listOf(
                    io.androllm.core.network.identity.BatchRejection(0, "e1", "tokens_input: too small"),
                )
            )
        )
        assertFalse(hasSessionRejection(emptyList()))
    }
}
