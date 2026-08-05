package io.androllm.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests the pure history-buffer helper used by [TelemetryRepository].
 */
class TelemetryHistoryTest {

    private fun sample(timestampMs: Long): TelemetrySample = TelemetrySample(
        timestampMs = timestampMs,
        tokensPerSecond = 1f,
        ramUsedMb = 1f,
        ramTotalMb = 8f,
        gpuMemoryMb = 0f,
        kvCacheMb = 0f,
        promptTokens = 0L,
        generatedTokens = 0L,
        isGenerating = false
    )

    @Test
    fun `append keeps history within max size`() {
        var history = emptyList<TelemetrySample>()
        repeat(10) { i ->
            history = appendToHistory(history, sample(i.toLong()), max = 5)
        }
        assertEquals(5, history.size)
        // Only the last 5 samples survive.
        assertEquals(listOf(5L, 6L, 7L, 8L, 9L), history.map { it.timestampMs })
    }

    @Test
    fun `append keeps order when under the limit`() {
        var history = emptyList<Int>()
        repeat(3) { i -> history = appendToHistory(history, i, max = 10) }
        assertEquals(listOf(0, 1, 2), history)
    }

    @Test
    fun `append with max 1 keeps only the newest`() {
        var history = appendToHistory(emptyList(), 1, max = 1)
        history = appendToHistory(history, 2, max = 1)
        assertEquals(listOf(2), history)
    }

    @Test
    fun `append with empty history returns single sample`() {
        val result = appendToHistory(emptyList(), 42, max = 5)
        assertEquals(listOf(42), result)
    }
}
