package io.androllm.engine.utils

import android.os.Build
import android.os.Process

/**
 * Determines sensible thread counts for the target device.
 */
object ThreadManager {

    /**
     * Runs [block] with the calling thread demoted to the background cgroup,
     * restoring the previous priority afterwards.
     *
     * llama.cpp spawns its compute threads FROM the calling thread, which on
     * Android inherit the caller's scheduler priority. Demoting the caller
     * during native inference therefore demotes the whole compute pool: the UI
     * thread (foreground cgroup) always wins the CPU, so the app stays at 60
     * FPS and the device never appears frozen while tokens are generated.
     * The cost is a modest token-speed hit — the correct trade for "never
     * freeze the device".
     */
    inline fun <T> withBackgroundInferencePriority(block: () -> T): T {
        val tid = Process.myTid()
        val previous = runCatching { Process.getThreadPriority(tid) }.getOrDefault(0)
        runCatching { Process.setThreadPriority(tid, Process.THREAD_PRIORITY_BACKGROUND) }
        return try {
            block()
        } finally {
            runCatching { Process.setThreadPriority(tid, previous) }
        }
    }

    /**
     * Number of hardware cores, at least 2.
     */
    fun hardwareCores(): Int =
        maxOf(2, Runtime.getRuntime().availableProcessors())

    /**
     * Hard ceiling for compute threads. llama.cpp's mobile guidance is 2-4
     * physical P-cores: more threads saturate memory bandwidth AND spin at
     * default (foreground) priority on cores the system UI needs — a device-
     * freeze risk, not a speedup. The native engine applies the same cap
     * (MAX_COMPUTE_THREADS) as a second line of defense.
     */
    private const val MAX_COMPUTE_THREADS = 4

    /**
     * Recommended threads for generation: capped at the mobile P-core sweet
     * spot (4). On 8-core SoCs this deliberately leaves the efficiency cores
     * and at least one performance core free for the UI.
     */
    fun recommendedThreads(): Int {
        val cores = hardwareCores()
        return when {
            cores >= 4 -> MAX_COMPUTE_THREADS
            else -> maxOf(1, cores - 1)
        }
    }

    /**
     * Threads for specified performance profile. Even MAXIMUM_PERFORMANCE is
     * capped at [MAX_COMPUTE_THREADS] — going beyond it never helps on phones
     * and risks starving the system.
     */
    fun profileThreads(profile: io.androllm.engine.models.PerformanceProfile): Int {
        val cores = hardwareCores()
        return when (profile) {
            io.androllm.engine.models.PerformanceProfile.BATTERY_SAVER -> maxOf(1, cores / 2).coerceAtMost(MAX_COMPUTE_THREADS)
            io.androllm.engine.models.PerformanceProfile.BALANCED -> recommendedThreads()
            io.androllm.engine.models.PerformanceProfile.MAXIMUM_PERFORMANCE -> MAX_COMPUTE_THREADS
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
