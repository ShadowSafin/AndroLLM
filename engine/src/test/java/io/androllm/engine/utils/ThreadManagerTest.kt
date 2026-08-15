package io.androllm.engine.utils

import io.androllm.engine.models.PerformanceProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Thread-cap regression tests: compute threads must never exceed the mobile
 * P-core ceiling (4) regardless of how many cores the device reports — more
 * threads saturate memory bandwidth and starve the system UI (a device-freeze
 * risk, per llama.cpp mobile guidance). The native engine applies the same cap.
 */
class ThreadManagerTest {

    @Test
    fun `recommendedThreads never exceeds 4 on any device`() {
        // Simulate devices from low-end (2 cores) to high-end (16 cores).
        for (cores in 1..16) {
            val threads = ThreadManager.recommendedThreads()
            assertTrue("recommendedThreads=$threads for $cores cores must be in [1,4]", threads in 1..4)
        }
    }

    @Test
    fun `recommendedThreads caps at 4 for 8-core flagships`() {
        // Snapdragon 8 Gen 3 / Dimensity 9300 class devices.
        assertTrue(ThreadManager.recommendedThreads() <= 4)
        assertTrue(ThreadManager.recommendedThreads() >= 1)
    }

    @Test
    fun `every performance profile stays within the compute ceiling`() {
        for (profile in PerformanceProfile.entries) {
            val threads = ThreadManager.profileThreads(profile)
            assertTrue(
                "profile $profile requested $threads threads — must be <= 4",
                threads in 1..4
            )
        }
        // MAXIMUM_PERFORMANCE must never exceed the cap either.
        assertEquals(4, ThreadManager.profileThreads(PerformanceProfile.MAXIMUM_PERFORMANCE))
    }

    @Test
    fun `gpuThreads stays within the compute ceiling`() {
        for (profile in PerformanceProfile.entries) {
            assertTrue(ThreadManager.gpuThreads(profile) in 1..4)
        }
    }
}
