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

    @Test
    fun `layer-aware KV cache matches the llama cpp formula`() {
        // 2 (K+V) * 28 layers * 4096 ctx * 4 kv-heads * 128 head-dim * 2 bytes (f16)
        val kv = MemoryEstimator.estimateKvCacheBytes(
            contextLength = 4096,
            blockCount = 28,
            headCountKv = 4,
            keyLength = 128
        )
        assertEquals(2L * 28 * 4096 * 4 * 128 * 2, kv)
    }

    @Test
    fun `layer-aware KV cache falls back when geometry is unknown`() {
        val known = MemoryEstimator.estimateKvCacheBytes(4096, 28, 4, 128)
        val unknown = MemoryEstimator.estimateKvCacheBytes(4096, 0, 0, 0)
        assertEquals(MemoryEstimator.estimateContextMemory(4096), unknown)
        assertTrue("layer-accurate estimate must differ from the heuristic", known != unknown)
    }

    @Test
    fun `KV cache grows linearly with context length`() {
        val c1k = MemoryEstimator.estimateKvCacheBytes(1024, 28, 4, 128)
        val c4k = MemoryEstimator.estimateKvCacheBytes(4096, 28, 4, 128)
        assertEquals(c1k * 4, c4k)
    }

    @Test
    fun `small model must not report multi-gigabyte requirements`() {
        // REGRESSION: a ~600MB Q8 0.5B model with no header geometry previously
        // estimated ~3.7GB (flat 96KB/token fallback × the 32k train context).
        // At the default 4096 context with a size-aware fallback the footprint
        // must stay well under 1.2GB.
        val fileSize = 600L * 1024 * 1024
        val footprint = MemoryEstimator.estimateTotalFootprint(fileSizeBytes = fileSize, contextLength = 4096)
        val estimatedMb = footprint / (1024L * 1024L)
        assertTrue(
            "600MB model must estimate < 1.2GB, got ${estimatedMb}MB",
            footprint < 1_200_000_000L
        )
        val weights = MemoryEstimator.estimateWeightsMemory(fileSize)
        assertTrue("footprint must be dominated by the weights", footprint > weights)
    }

    @Test
    fun `size-aware kv fallback scales with model size`() {
        val small = MemoryEstimator.fallbackKvBytesPerToken(600L * 1024 * 1024)     // 0.5B Q8
        val big = MemoryEstimator.fallbackKvBytesPerToken(5L * 1024 * 1024 * 1024)  // 8B Q4
        assertTrue(small > 0)
        assertTrue("bigger model must have a bigger KV fallback", small < big)
        // The old flat fallback was 96KB — the size-aware one must be far lower
        // for small models.
        assertTrue("small-model fallback must be far below the old 96KB", small < 96L * 1024)
    }

    @Test
    fun `total footprint includes weights kv and compute scratch`() {
        val footprint = MemoryEstimator.estimateTotalFootprint(
            fileSizeBytes = 1_000_000_000L,
            contextLength = 4096,
            blockCount = 28,
            headCountKv = 4,
            keyLength = 128
        )
        val weights = MemoryEstimator.estimateWeightsMemory(1_000_000_000L)
        val kv = MemoryEstimator.estimateKvCacheBytes(4096, 28, 4, 128)
        val scratch = MemoryEstimator.estimateComputeScratchBytes(weights)
        assertEquals(weights + kv + scratch, footprint)
        // Compute scratch is a meaningful share of the footprint.
        assertTrue(footprint > weights)
    }
}
