package io.androllm.engine.utils

import android.os.Build
import android.os.Process
import java.util.concurrent.atomic.AtomicInteger

/**
 * Determines sensible thread counts for the target device.
 *
 * Threading is device-class-adaptive: the thread count, batch size, and
 * memory budget are chosen based on the device's RAM, core count, and
 * thermal class rather than using a one-size-fits-all approach.
 */
object ThreadManager {

    /** Cached hardware core count (computed once, never changes at runtime). */
    private val cachedCores: AtomicInteger = AtomicInteger(0)

    /** Cached device tier (computed once). */
    @Volatile
    private var cachedTier: String? = null

    /** Cached total RAM in GB (computed once). */
    @Volatile
    private var cachedRamGb: Int = 0

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
     * Number of hardware cores, at least 2. Cached after first call.
     */
    fun hardwareCores(): Int {
        val cached = cachedCores.get()
        if (cached > 0) return cached
        val cores = maxOf(2, Runtime.getRuntime().availableProcessors())
        cachedCores.set(cores)
        return cores
    }

    /**
     * Hard ceiling for compute threads. On 8-core SoCs this deliberately
     * leaves the efficiency cores and at least one performance core free for
     * the UI. The native engine applies the same cap (MAX_COMPUTE_THREADS)
     * as a second line of defense.
     */
    private const val MAX_COMPUTE_THREADS = 4

    /**
     * Minimum threads for battery saver mode.
     */
    private const val MIN_BATTERY_SAVER_THREADS = 1

    /**
     * Recommended threads for generation: capped at the mobile P-core sweet
     * spot (4). Device-class-adaptive: low-end devices use fewer threads to
     * avoid thermal throttling.
     */
    fun recommendedThreads(): Int {
        val cores = hardwareCores()
        val tier = deviceTier()
        return when (tier) {
            "low" -> maxOf(1, minOf(2, cores - 1))
            "medium" -> maxOf(2, minOf(3, cores - 1))
            else -> when {
                cores >= 4 -> MAX_COMPUTE_THREADS
                else -> maxOf(1, cores - 1)
            }
        }
    }

    /**
     * Threads for specified performance profile. Even MAXIMUM_PERFORMANCE is
     * capped at [MAX_compute_THREADS] — going beyond it never helps on phones
     * and risks starving the system.
     */
    fun profileThreads(profile: io.androllm.engine.models.PerformanceProfile): Int {
        val cores = hardwareCores()
        val tier = deviceTier()
        return when (profile) {
            io.androllm.engine.models.PerformanceProfile.BATTERY_SAVER -> {
                val base = maxOf(MIN_BATTERY_SAVER_THREADS, cores / 3)
                base.coerceAtMost(MAX_COMPUTE_THREADS)
            }
            io.androllm.engine.models.PerformanceProfile.BALANCED -> recommendedThreads()
            io.androllm.engine.models.PerformanceProfile.MAXIMUM_PERFORMANCE -> {
                // On low-end devices even "max performance" should be conservative
                if (tier == "low") minOf(2, MAX_COMPUTE_THREADS) else MAX_COMPUTE_THREADS
            }
        }
    }

    /**
     * Optimal CPU thread count when GPU acceleration is active.
     * GPU inference uses the GPU for compute but still needs CPU threads
     * for tokenizer, sampling, and I/O. Fewer CPU threads when GPU is active
     * reduces contention.
     */
    fun gpuThreads(profile: io.androllm.engine.models.PerformanceProfile): Int {
        return when (profile) {
            io.androllm.engine.models.PerformanceProfile.BATTERY_SAVER -> 1
            io.androllm.engine.models.PerformanceProfile.BALANCED -> 2
            io.androllm.engine.models.PerformanceProfile.MAXIMUM_PERFORMANCE -> 4
        }
    }

    /**
     * Threads used while loading/quantizing: all cores are safe here since
     * loading is a one-time operation and we want it to complete quickly.
     */
    fun loadingThreads(): Int = hardwareCores()

    /**
     * Device tier label used for logging and default config.
     * Cached after first computation.
     */
    fun deviceTier(): String {
        cachedTier?.let { return it }
        val cores = hardwareCores()
        val ramGb = totalRamGb()
        val tier = when {
            cores >= 8 && ramGb >= 6 -> "high"
            cores >= 6 && ramGb >= 4 -> "medium-high"
            cores >= 4 && ramGb >= 3 -> "medium"
            cores >= 3 -> "low-medium"
            else -> "low"
        }
        cachedTier = tier
        return tier
    }

    /**
     * Total device RAM in GB (cached). Used for memory budget decisions.
     */
    fun totalRamGb(): Int {
        if (cachedRamGb > 0) return cachedRamGb
        val bytes = Runtime.getRuntime().maxMemory()
        // maxMemory() returns Java heap limit, not total device RAM.
        // Use a conservative estimate based on the heap limit.
        // Real device RAM is typically 2-4x the max heap.
        val estimatedRamBytes = bytes * 3
        cachedRamGb = (estimatedRamBytes / (1024L * 1024L * 1024L)).toInt().coerceAtLeast(2)
        return cachedRamGb
    }

    /**
     * Memory budget fraction: what fraction of available RAM the engine
     * should target. Low-end devices get a smaller budget to leave room
     * for the OS and other apps.
     */
    fun memoryBudgetFraction(): Float {
        return when (deviceTier()) {
            "low" -> 0.45f
            "low-medium" -> 0.55f
            "medium" -> 0.65f
            "medium-high" -> 0.70f
            "high" -> 0.75f
            else -> 0.65f
        }
    }

    /**
     * Recommended context length based on device class.
     * Low-end devices should use shorter contexts to reduce KV-cache memory.
     */
    fun recommendedContextLength(): Int {
        return when (deviceTier()) {
            "low" -> 2048
            "low-medium" -> 3072
            "medium" -> 4096
            "medium-high" -> 4096
            "high" -> 8192
            else -> 4096
        }
    }

    /**
     * Prefetch depth: how many tokens to pre-decode ahead.
     * Higher values improve throughput but increase memory usage.
     */
    fun recommendedPrefetchDepth(): Int {
        return when (deviceTier()) {
            "low" -> 1
            "low-medium" -> 2
            "medium" -> 3
            else -> 4
        }
    }

    /**
     * Batch size for prompt processing. Larger batches improve throughput
     * but increase peak memory.
     */
    fun recommendedBatchSize(): Int {
        return when (deviceTier()) {
            "low" -> 512
            "low-medium" -> 1024
            "medium" -> 2048
            else -> 2048
        }
    }

    /**
     * True when this device can realistically run 3B-parameter models.
     */
    fun canRunSmallModels(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        return hardwareCores() >= 4 && totalRamGb() >= 3
    }

    /**
     * True when this device can realistically run 7B+ models.
     */
    fun canRunLargeModels(): Boolean {
        return hardwareCores() >= 6 && totalRamGb() >= 6
    }

    /**
     * Returns a human-readable summary of the device's threading profile.
     * Useful for diagnostics and the developer screen.
     */
    fun threadingProfile(): String {
        val cores = hardwareCores()
        val tier = deviceTier()
        val ram = totalRamGb()
        return "cores=$cores tier=$tier ram=${ram}GB " +
            "threads=${recommendedThreads()} " +
            "ctx=${recommendedContextLength()} " +
            "batch=${recommendedBatchSize()} " +
            "budget=${(memoryBudgetFraction() * 100).toInt()}%"
    }
}
