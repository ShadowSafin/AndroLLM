package io.androllm.core.cloud.usage

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sync trigger hook: every recorded request must fire [CloudUsageMeter.onRecorded]
 * without ever breaking recording itself.
 */
class CloudUsageMeterListenerTest {

    private fun meter(): CloudUsageMeter =
        CloudUsageMeter(InMemoryCloudUsageStore(), persistDebounceMs = 0)

    private fun record(meter: CloudUsageMeter) {
        meter.record(
            meter.buildRecord(
                providerId = "openai",
                providerName = "OpenAI",
                modelId = "gpt-4o",
                kind = CloudRequestKind.CHAT,
                inputTokens = 10,
                outputTokens = 20,
            )
        )
    }

    @Test
    fun `record fires onRecorded`() {
        val meter = meter()
        val latch = CountDownLatch(1)
        meter.onRecorded = { latch.countDown() }
        record(meter)
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertEquals(1, meter.snapshot().recentRecords.size)
    }

    @Test
    fun `throwing listener never breaks recording`() {
        val meter = meter()
        meter.onRecorded = { throw IllegalStateException("boom") }
        record(meter)
        assertEquals(1, meter.snapshot().recentRecords.size)
    }

    @Test
    fun `burst of records fires listener per record`() {
        val meter = meter()
        val count = AtomicInteger(0)
        meter.onRecorded = { count.incrementAndGet() }
        repeat(5) { record(meter) }
        assertEquals(5, count.get())
    }
}
