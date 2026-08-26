package io.androllm.core.cloud.usage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Dashboard metric derivations: success/error rates, cache hit rate, daily
 * average latency/first-token, and filter matching. These back the cards and
 * charts in the usage dashboard, so their arithmetic is pinned here.
 */
class CloudUsageMetricsTest {

    @Test
    fun `success and error rates are complementary`() {
        val totals = CloudUsageTotals(requests = 10, successes = 7, failures = 3)
        assertEquals(0.7f, totals.successRate, 0.001f)
        assertEquals(0.3f, totals.errorRate, 0.001f)
    }

    @Test
    fun `empty totals report perfect success and zero error`() {
        val totals = CloudUsageTotals()
        assertEquals(1f, totals.successRate, 0.001f)
        assertEquals(0f, totals.errorRate, 0.001f)
        assertEquals(0f, totals.cacheHitRate, 0.001f)
    }

    @Test
    fun `cache hit rate divides hits by lookups`() {
        val totals = CloudUsageTotals(cacheHits = 3, cacheMisses = 1)
        assertEquals(0.75f, totals.cacheHitRate, 0.001f)
    }

    @Test
    fun `daily aggregate averages latency only over measured samples`() {
        val day = CloudDailyAggregate(
            dateKey = "2025-01-01",
            latencySumMs = 1500,
            latencySamples = 3,
            firstTokenSumMs = 300,
            firstTokenSamples = 2
        )
        assertEquals(500L, day.avgLatencyMs)
        assertEquals(150L, day.avgFirstTokenMs)
    }

    @Test
    fun `daily aggregate with no samples averages to zero`() {
        val day = CloudDailyAggregate(dateKey = "2025-01-01")
        assertEquals(0L, day.avgLatencyMs)
        assertEquals(0L, day.avgFirstTokenMs)
    }

    @Test
    fun `provider lifetime stats derive success rate and average latency`() {
        val stats = CloudProviderLifetimeStats(
            providerId = "p1",
            requests = 4,
            successes = 3,
            failures = 1,
            latencySumMs = 2000,
            latencySamples = 4
        )
        assertEquals(0.75f, stats.successRate, 0.001f)
        assertEquals(500L, stats.avgLatencyMs)
    }

    @Test
    fun `filter matches by provider model and time range`() {
        val record = CloudUsageRecord(
            id = "r1",
            timestampMs = 1_000,
            providerId = "p1",
            providerName = "P",
            modelId = "m1"
        )
        val filter = CloudUsageFilter(fromMs = 500, toMs = 1500, providerId = "p1", modelId = "m1")
        assertTrue(filter.matches(record))

        assertTrue(!CloudUsageFilter(providerId = "other").matches(record))
        assertTrue(!CloudUsageFilter(modelId = "other").matches(record))
        assertTrue(!CloudUsageFilter(fromMs = 2000).matches(record))
        assertTrue(!CloudUsageFilter(toMs = 500).matches(record))
        assertTrue(CloudUsageFilter.NONE.matches(record))
    }

    @Test
    fun `record total tokens default to input plus output`() {
        val record = CloudUsageRecord(
            id = "r", timestampMs = 0, providerId = "p", providerName = "P", modelId = "m",
            inputTokens = 10, outputTokens = 5
        )
        assertEquals(15L, record.totalTokens)
    }
}
