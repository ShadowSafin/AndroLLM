package io.androllm.engine.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [ModelResourceGuard.checkAgainst] — the pure decision logic behind
 * the pre-load RAM gate. All tests use explicit available-RAM values so no
 * Android framework is required; the production [ModelResourceGuard.check]
 * merely feeds it live `availMem`/low-memory state.
 */
class ModelResourceGuardTest {

    // A 1B-class Q4 model: ~700 MB file, 4k context, 28 layers, GQA 4/128.
    private val smallModelBytes = 700L * 1024 * 1024

    // An 8B-class Q4 model: ~4.9 GB file, 8k context, 32 layers, GQA 8/128.
    private val bigModelBytes = 4_900L * 1024 * 1024

    @Test
    fun `small model fits on a 6 GB device`() {
        val result = ModelResourceGuard().checkAgainst(
            availableBytes = 3L * 1024 * 1024 * 1024, // 3 GB free
            lowMemory = false,
            fileSizeBytes = smallModelBytes,
            contextLength = 4096,
            blockCount = 28,
            headCountKv = 4,
            keyLength = 128
        )
        assertTrue("small model must fit: $result", result is ResourceCheck.Allowed)
    }

    @Test
    fun `big model is refused on a low-RAM device with a numeric reason`() {
        val result = ModelResourceGuard().checkAgainst(
            availableBytes = 3L * 1024 * 1024 * 1024,
            lowMemory = false,
            fileSizeBytes = bigModelBytes,
            contextLength = 8192,
            blockCount = 32,
            headCountKv = 8,
            keyLength = 128
        )
        assertTrue("8B model must be refused on 3 GB free", result is ResourceCheck.Insufficient)
        val insufficient = result as ResourceCheck.Insufficient
        assertTrue("reason must carry the numbers", insufficient.reason.contains("MB"))
        assertTrue(insufficient.neededBytes > insufficient.availableBytes)
    }

    @Test
    fun `low-memory state refuses even a small model`() {
        val result = ModelResourceGuard().checkAgainst(
            availableBytes = 4L * 1024 * 1024 * 1024,
            lowMemory = true,
            fileSizeBytes = smallModelBytes,
            contextLength = 4096,
            blockCount = 28,
            headCountKv = 4,
            keyLength = 128
        )
        assertTrue("low-memory flag must refuse any load", result is ResourceCheck.Insufficient)
        assertTrue((result as ResourceCheck.Insufficient).reason.contains("low"))
    }

    @Test
    fun `layer-accurate geometry makes the decision more conservative than the fallback`() {
        val guard = ModelResourceGuard()
        // 16k context on 2 GB free: the REAL GQA geometry (28 layers × 4 kv
        // heads × 128 dim) sizes the KV cache at ~940 MB → refused; the
        // size-aware fallback (~14 KB/token for a 700 MB file) estimates far
        // less → allowed. They must diverge — that divergence is the point of
        // parsing the header geometry.
        val withGeometry = guard.checkAgainst(
            availableBytes = 2L * 1024 * 1024 * 1024,
            lowMemory = false,
            fileSizeBytes = smallModelBytes,
            contextLength = 16384,
            blockCount = 28,
            headCountKv = 4,
            keyLength = 128
        )
        val withoutGeometry = guard.checkAgainst(
            availableBytes = 2L * 1024 * 1024 * 1024,
            lowMemory = false,
            fileSizeBytes = smallModelBytes,
            contextLength = 16384,
            blockCount = 0,
            headCountKv = 0,
            keyLength = 0
        )
        assertTrue("real geometry must refuse a 16k context on 2 GB free", withGeometry is ResourceCheck.Insufficient)
        assertTrue("size-aware fallback must be less conservative", withoutGeometry is ResourceCheck.Allowed)
    }

    @Test
    fun `estimate footprint is composed from weights kv and scratch`() {
        val guard = ModelResourceGuard()
        val footprint = guard.estimateFootprint(
            fileSizeBytes = smallModelBytes,
            contextLength = 4096,
            blockCount = 28,
            headCountKv = 4,
            keyLength = 128
        )
        val weights = MemoryEstimator.estimateWeightsMemory(smallModelBytes)
        assertTrue("footprint must exceed weights alone", footprint > weights)
    }
}
