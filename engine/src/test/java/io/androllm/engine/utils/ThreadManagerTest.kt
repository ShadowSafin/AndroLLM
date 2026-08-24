package io.androllm.engine.utils

import io.androllm.engine.models.PerformanceProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Thread allocation tests for the device-adaptive ThreadManager.
 *
 * The engine now uses the maximum safe number of cores for inference,
 * scaling by device tier and leaving only enough headroom for UI/system.
 */
class ThreadManagerTest {

    @Test
    fun `recommendedThreads stays within absolute ceiling`() {
        val threads = ThreadManager.recommendedThreads()
        assertTrue("recommendedThreads=$threads must be <= 12", threads in 1..12)
    }

    @Test
    fun `recommendedThreads is at least 2`() {
        assertTrue("recommendedThreads must be >= 2", ThreadManager.recommendedThreads() >= 2)
    }

    @Test
    fun `maximumSafeThreads never exceeds absolute ceiling`() {
        val threads = ThreadManager.maximumSafeThreads()
        assertTrue("maximumSafeThreads=$threads must be <= 12", threads in 1..12)
    }

    @Test
    fun `maximumSafeThreads is at least 2`() {
        assertTrue("maximumSafeThreads must be >= 2", ThreadManager.maximumSafeThreads() >= 2)
    }

    @Test
    fun `every performance profile stays within absolute ceiling`() {
        for (profile in PerformanceProfile.entries) {
            val threads = ThreadManager.profileThreads(profile)
            assertTrue(
                "profile $profile requested $threads threads — must be <= 12",
                threads in 1..12
            )
        }
    }

    @Test
    fun `BATTERY_SAVER uses fewer threads than MAXIMUM_PERFORMANCE`() {
        val saver = ThreadManager.profileThreads(PerformanceProfile.BATTERY_SAVER)
        val maxPerf = ThreadManager.profileThreads(PerformanceProfile.MAXIMUM_PERFORMANCE)
        assertTrue("BATTERY_SAVER ($saver) must use fewer threads than MAXIMUM_PERFORMANCE ($maxPerf)", saver <= maxPerf)
    }

    @Test
    fun `BALANCED uses moderate thread count`() {
        val balanced = ThreadManager.profileThreads(PerformanceProfile.BALANCED)
        assertTrue("BALANCED threads=$balanced must be in [2,8]", balanced in 2..8)
    }

    @Test
    fun `gpuThreads stays within absolute ceiling`() {
        for (profile in PerformanceProfile.entries) {
            val gpuThreads = ThreadManager.gpuThreads(profile)
            assertTrue("gpuThreads for $profile = $gpuThreads must be in [1,10]", gpuThreads in 1..10)
        }
    }

    @Test
    fun `hardwareCores returns at least 2`() {
        assertTrue("hardwareCores must be >= 2", ThreadManager.hardwareCores() >= 2)
    }

    @Test
    fun `hardwareCores is cached consistently`() {
        val first = ThreadManager.hardwareCores()
        val second = ThreadManager.hardwareCores()
        assertEquals(first, second)
    }

    @Test
    fun `performanceCoreCount is positive`() {
        assertTrue("performanceCoreCount must be >= 1", ThreadManager.performanceCoreCount() >= 1)
    }

    @Test
    fun `performanceCoreCount is less than or equal to hardwareCores`() {
        assertTrue(
            "performanceCoreCount must be <= hardwareCores",
            ThreadManager.performanceCoreCount() <= ThreadManager.hardwareCores()
        )
    }

    @Test
    fun `loadingThreads equals hardwareCores`() {
        assertEquals(ThreadManager.hardwareCores(), ThreadManager.loadingThreads())
    }

    @Test
    fun `threadingProfile produces readable output`() {
        val profile = ThreadManager.threadingProfile()
        assertTrue("threadingProfile must contain cores", "cores=" in profile)
        assertTrue("threadingProfile must contain tier", "tier=" in profile)
        assertTrue("threadingProfile must contain threads", "threads=" in profile)
    }
}
