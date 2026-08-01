package io.androllm.engine

import io.androllm.engine.utils.MemoryEstimator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the memory estimator.
 */
class MemoryEstimatorTest {

    @Test
    fun `weights memory scales with file size`() {
        val small = MemoryEstimator.estimateWeightsMemory(100_000_000L)
        val large = MemoryEstimator.estimateWeightsMemory(1_000_000_000L)
        assertEquals(105_000_000L, small)
        assertEquals(1_050_000_000L, large)
    }

    @Test
    fun `context memory scales with context length`() {
        val c1k = MemoryEstimator.estimateContextMemory(1024)
        val c4k = MemoryEstimator.estimateContextMemory(4096)
        assertEquals(c1k * 4, c4k)
    }

    @Test
    fun `total memory includes weights and kv cache`() {
        val total = MemoryEstimator.estimateTotalMemory(500_000_000L, 2048)
        assertEquals(500_000_000L * 105 / 100 + MemoryEstimator.estimateContextMemory(2048), total)
    }

    @Test
    fun `small models fit in heap`() {
        assertTrue(MemoryEstimator.fitsInHeap(200_000_000L, 1024))
    }

    @Test
    fun `huge models do not fit`() {
        assertFalse(MemoryEstimator.fitsInHeap(10_000_000_000L, 4096))
    }
}
