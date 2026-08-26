package io.androllm.core.cloud.usage

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Usage tracking accuracy: totals, today/month windows, per-provider and
 * per-model rollups, success/error rates, rate-limit counting, cache
 * hit/miss accounting, tool-call counts, sessions, filters, export, clear.
 */
class CloudUsageMeterTest {

    private lateinit var store: InMemoryCloudUsageStore
    private var now = 1_700_000_000_000L
    private lateinit var meter: CloudUsageMeter

    @Before
    fun setUp() = runBlocking {
        store = InMemoryCloudUsageStore()
        meter = CloudUsageMeter(store, clock = { now }, persistDebounceMs = 10)
        meter.init()
    }

    private fun record(
        provider: String = "p1",
        providerName: String = "Proxy",
        model: String = "openai/gpt-4o",
        input: Long = 100,
        output: Long = 50,
        latency: Long = 800,
        firstToken: Long? = 200,
        success: Boolean = true,
        error: CloudErrorKind = CloudErrorKind.NONE,
        retries: Int = 0,
        fallback: Boolean = false,
        cacheHit: Boolean = false,
        cacheSaved: Long = 0,
        tools: Int = 0,
        session: String = "",
        atMs: Long = now
    ): CloudUsageRecord {
        // Build at the current clock, then pin the timestamp explicitly so
        // backdated records never shift the test clock itself.
        return meter.buildRecord(
            providerId = provider,
            providerName = providerName,
            modelId = model,
            inputTokens = input,
            outputTokens = output,
            latencyMs = latency,
            firstTokenMs = firstToken,
            success = success,
            errorKind = error,
            retryCount = retries,
            usedFallbackProvider = fallback,
            cacheHit = cacheHit,
            cacheSavedTokens = cacheSaved,
            toolCallsCount = tools,
            sessionId = session
        ).copy(timestampMs = atMs)
    }

    @Test
    fun `records aggregate tokens cost and latency`() = runBlocking {
        meter.record(record(input = 100, output = 50, latency = 600))
        meter.record(record(input = 200, output = 100, latency = 1000))

        val snapshot = meter.snapshot()
        assertEquals(2, snapshot.total.requests)
        assertEquals(300L, snapshot.total.inputTokens)
        assertEquals(150L, snapshot.total.outputTokens)
        assertEquals(450L, snapshot.total.totalTokens)
        assertEquals(800L, snapshot.total.avgLatencyMs)
        assertEquals(200L, snapshot.total.avgFirstTokenMs)
        assertTrue(snapshot.total.estimatedCostMicros > 0)
        assertEquals(1.0f, snapshot.total.successRate, 0.001f)
    }

    @Test
    fun `success and error rates track failures`() = runBlocking {
        meter.record(record(success = true))
        meter.record(record(success = false, error = CloudErrorKind.HTTP_ERROR))
        meter.record(record(success = false, error = CloudErrorKind.RATE_LIMIT))

        val totals = meter.snapshot().total
        assertEquals(3, totals.requests)
        assertEquals(1, totals.successes)
        assertEquals(2, totals.failures)
        assertEquals(1f / 3f, totals.successRate, 0.01f)
        assertEquals(2f / 3f, totals.errorRate, 0.01f)
        assertEquals(1, totals.rateLimitHits)
    }

    @Test
    fun `retries rate limits and fallbacks are counted`() = runBlocking {
        meter.record(record(retries = 2))
        meter.record(record(error = CloudErrorKind.RATE_LIMIT, success = false))
        meter.record(record(fallback = true))

        val totals = meter.snapshot().total
        assertEquals(2, totals.retries)
        assertEquals(1, totals.rateLimitHits)
    }

    @Test
    fun `cache hits misses and saved tokens are tracked`() = runBlocking {
        meter.record(record(cacheHit = true, cacheSaved = 400))
        meter.record(record(cacheHit = true, cacheSaved = 400))
        meter.record(record(cacheHit = false))

        val totals = meter.snapshot().total
        assertEquals(2, totals.cacheHits)
        assertEquals(1, totals.cacheMisses)
        assertEquals(800L, totals.cacheSavedTokens)
        assertEquals(2f / 3f, totals.cacheHitRate, 0.01f)
    }

    @Test
    fun `tool call usage is counted`() = runBlocking {
        meter.record(record(tools = 2))
        meter.record(record(tools = 1))
        meter.record(record(tools = 0))

        assertEquals(3, meter.snapshot().total.toolCalls)
    }

    @Test
    fun `per provider and per model stats accumulate`() = runBlocking {
        meter.record(record(provider = "p1", providerName = "A", model = "m1"))
        meter.record(record(provider = "p1", providerName = "A", model = "m2"))
        meter.record(record(provider = "p2", providerName = "B", model = "m1"))

        val snapshot = meter.snapshot()
        assertEquals(2, snapshot.perProvider.size)
        val p1 = snapshot.perProvider.find { it.providerId == "p1" }!!
        assertEquals(2, p1.requests)
        assertEquals("A", p1.providerName)
        val p2 = snapshot.perProvider.find { it.providerId == "p2" }!!
        assertEquals(1, p2.requests)

        assertEquals(2, snapshot.perModel.size)
        val m1 = snapshot.perModel.find { it.modelId == "m1" }!!
        assertEquals(2, m1.requests)
    }

    @Test
    fun `today and month windows use the clock`() = runBlocking {
        // Two requests "today", one request 40 days ago (outside the month).
        val today = now
        meter.record(record(atMs = today))
        meter.record(record(atMs = today - 3_600_000))
        meter.record(record(atMs = today - 40L * 24 * 3_600_000))

        val snapshot = meter.snapshot()
        assertEquals(3, snapshot.total.requests)
        assertEquals(2, snapshot.today.requests)
        assertEquals(2, snapshot.month.requests)
    }

    @Test
    fun `active sessions count distinct recent session ids`() = runBlocking {
        meter.record(record(session = "conv-1"))
        meter.record(record(session = "conv-1"))
        meter.record(record(session = "conv-2"))
        // An old session outside the active window.
        meter.record(record(session = "conv-old", atMs = now - 60 * 60_000))

        assertEquals(2, meter.snapshot().activeSessions)
    }

    @Test
    fun `last request and provider are exposed`() = runBlocking {
        meter.record(record(providerName = "First"))
        meter.record(record(providerName = "Second", success = false, error = CloudErrorKind.TIMEOUT))

        val snapshot = meter.snapshot()
        assertNotNull(snapshot.lastRequest)
        assertEquals("Second", snapshot.lastProviderName)
        assertFalse(snapshot.lastRequest!!.success)
        assertEquals(CloudErrorKind.TIMEOUT, snapshot.lastRequest!!.errorKind)
    }

    @Test
    fun `filter narrows records by provider model and range`() = runBlocking {
        meter.record(record(provider = "p1", model = "m1"))
        meter.record(record(provider = "p2", model = "m1"))
        meter.record(record(provider = "p1", model = "m2"))

        val byProvider = meter.snapshot(CloudUsageFilter(providerId = "p1"))
        assertEquals(2, byProvider.filtered.requests)

        val byModel = meter.snapshot(CloudUsageFilter(modelId = "m1"))
        assertEquals(2, byModel.filtered.requests)

        val byBoth = meter.snapshot(CloudUsageFilter(providerId = "p1", modelId = "m2"))
        assertEquals(1, byBoth.filtered.requests)

        val noneInRange = meter.snapshot(CloudUsageFilter(fromMs = now + 1_000_000))
        assertEquals(0, noneInRange.filtered.requests)
    }

    @Test
    fun `error spike alert fires on high failure rate`() = runBlocking {
        repeat(5) { meter.record(record(success = false, error = CloudErrorKind.HTTP_ERROR)) }

        val alerts = meter.snapshot().alerts
        assertTrue(alerts.any { it.id == "error-spike" })
    }

    @Test
    fun `rate limit spike alert fires after repeated 429s`() = runBlocking {
        repeat(3) { meter.record(record(success = false, error = CloudErrorKind.RATE_LIMIT)) }

        val alerts = meter.snapshot().alerts
        assertTrue(alerts.any { it.id == "rate-limit-spike" })
    }

    @Test
    fun `csv export contains one row per record`() = runBlocking {
        meter.record(record(model = "m1"))
        meter.record(record(model = "m2", success = false, error = CloudErrorKind.RATE_LIMIT))

        val csv = meter.exportCsv()
        val lines = csv.trim().lines()
        assertEquals(3, lines.size) // header + 2 rows
        assertTrue(lines[0].startsWith("timestamp_iso,provider_id"))
        assertTrue(csv.contains("m1"))
        assertTrue(csv.contains("RATE_LIMIT"))
    }

    @Test
    fun `clear wipes records rollups and counters`() = runBlocking {
        meter.record(record())
        meter.record(record())
        meter.clear()

        val snapshot = meter.snapshot()
        assertEquals(0, snapshot.total.requests)
        assertTrue(snapshot.perProvider.isEmpty())
        assertTrue(snapshot.perModel.isEmpty())
        assertNull(snapshot.lastRequest)
    }

    @Test
    fun `record never throws even on absurd input`() {
        // The usage meter must never crash the pipeline.
        meter.record(
            meter.buildRecord(
                providerId = "",
                providerName = "",
                modelId = "",
                inputTokens = -5,
                outputTokens = Long.MAX_VALUE / 2,
                latencyMs = -1,
                errorMessage = "x".repeat(5000)
            )
        )
        // No exception = pass; snapshot must still compute.
        assertNotNull(meter.snapshot())
    }

    @Test
    fun `state persists across meter instances`() = runBlocking {
        meter.record(record(input = 111, output = 22))
        meter.flush()

        val second = CloudUsageMeter(store, clock = { now })
        second.init()
        assertEquals(1, second.snapshot().total.requests)
        assertEquals(133L, second.snapshot().total.totalTokens)
    }
}
