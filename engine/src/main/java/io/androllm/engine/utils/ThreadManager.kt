package io.androllm.engine.utils

import android.os.Build

/**
 * Determines sensible thread counts for the target device.
 */
object ThreadManager {

    /**
     * Number of hardware cores, at least 2.
     */
    fun hardwareCores(): Int =
        maxOf(2, Runtime.getRuntime().availableProcessors())

    /**
     * Recommended threads for generation: one per core, but reserve
     * one core for the UI/main thread.
     */
    fun recommendedThreads(): Int {
        val cores = hardwareCores()
        return when {
            cores >= 8 -> 6
            cores >= 6 -> 4
            cores >= 4 -> 4
            else -> maxOf(1, cores - 1)
        }
    }

    /**
     * Threads for specified performance profile.
     */
    fun profileThreads(profile: io.androllm.engine.models.PerformanceProfile): Int {
        val cores = hardwareCores()
        return when (profile) {
            io.androllm.engine.models.PerformanceProfile.BATTERY_SAVER -> maxOf(1, cores / 2)
            io.androllm.engine.models.PerformanceProfile.BALANCED -> recommendedThreads()
            io.androllm.engine.models.PerformanceProfile.MAXIMUM_PERFORMANCE -> maxOf(2, minOf(8, cores))
        }
    }

    /**
     * Optimal CPU thread count when GPU acceleration is active.
     */
    fun gpuThreads(profile: io.androllm.engine.models.PerformanceProfile): Int {
        return when (profile) {
            io.androllm.engine.models.PerformanceProfile.BATTERY_SAVER -> 2
            io.androllm.engine.models.PerformanceProfile.BALANCED -> 4
            io.androllm.engine.models.PerformanceProfile.MAXIMUM_PERFORMANCE -> 4
        }
    }

    /**
     * Threads used while loading/quantizing: all cores are safe here.
     */
    fun loadingThreads(): Int = hardwareCores()

    /**
     * Device tier label used for logging and default config.
     */
    fun deviceTier(): String {
        val cores = hardwareCores()
        val ramGb = Runtime.getRuntime().maxMemory() / (1024 * 1024 * 1024)
        return when {
            cores >= 8 && ramGb >= 4 -> "high"
            cores >= 4 -> "medium"
            else -> "low"
        }
    }

    /**
     * True when this device can realistically run 3B-parameter models.
     */
    fun canRunSmallModels(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        return hardwareCores() >= 4
    }
}
