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
     * Absolute ceiling for compute threads. On modern SoCs (8-12 cores),
     * inference benefits from using most cores. We leave 1-2 cores free
     * for the UI thread and system services, not a fixed 4-core cap.
     */
    private const val ABSOLUTE_MAX_THREADS = 12

    /**
     * Minimum threads for battery saver mode.
     */
    private const val MIN_BATTERY_SAVER_THREADS = 1

    /**
     * Number of cores to reserve for the UI/system (never use these
     * for inference).
     */
    private const val UI_RESERVE_CORES = 2

    /**
     * Estimated number of performance (big) cores on modern SoCs.
     * Most 8-core SoCs have 4 big + 4 little; 12-core have 4 big + 8 little.
     * We detect this conservatively.
     */
    fun performanceCoreCount(): Int {
        val cores = hardwareCores()
        // Most mobile SoCs: half the cores are performance cores.
        // On 4-core chips, all cores are performance.
        return when {
            cores <= 4 -> cores
            cores <= 6 -> cores / 2 + 1
            else -> cores / 2  // 4 on 8-core, 4 on 10-core, 6 on 12-core
        }
    }

    /**
     * Maximum safe threads for inference on this device. Uses as many
     * cores as possible while leaving enough headroom for the UI.
     *
     * Strategy: use all cores minus UI_RESERVE_CORES, but never less than
     * half the performance cores.
     */
    fun maximumSafeThreads(): Int {
        val cores = hardwareCores()
        val tier = deviceTier()
        return when (tier) {
            "low" -> maxOf(2, cores - 2).coerceAtMost(4)
            "low-medium" -> maxOf(2, cores - 2).coerceAtMost(6)
            "medium" -> maxOf(3, cores - 1).coerceAtMost(8)
            "medium-high" -> maxOf(4, cores - 1).coerceAtMost(10)
            "high" -> maxOf(4, cores - UI_RESERVE_CORES).coerceAtMost(ABSOLUTE_MAX_THREADS)
            else -> maxOf(4, cores - UI_RESERVE_CORES).coerceAtMost(ABSOLUTE_MAX_THREADS)
        }
    }

    /**
     * Recommended threads for generation. Uses the maximum safe thread
     * count to maximize tokens/sec on capable devices.
     */
    fun recommendedThreads(): Int = maximumSafeThreads()

    /**
     * Threads for specified performance profile.
     */
    fun profileThreads(profile: io.androllm.engine.models.PerformanceProfile): Int {
        val cores = hardwareCores()
        val tier = deviceTier()
        return when (profile) {
            io.androllm.engine.models.PerformanceProfile.BATTERY_SAVER -> {
                maxOf(MIN_BATTERY_SAVER_THREADS, cores / 4)
            }
            io.androllm.engine.models.PerformanceProfile.BALANCED -> {
                maxOf(3, cores - 2).coerceAtMost(8)
            }
            io.androllm.engine.models.PerformanceProfile.MAXIMUM_PERFORMANCE -> {
                maximumSafeThreads()
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
        val cores = hardwareCores()
        return when (profile) {
            io.androllm.engine.models.PerformanceProfile.BATTERY_SAVER -> 2
            io.androllm.engine.models.PerformanceProfile.BALANCED -> maxOf(3, cores / 2)
            io.androllm.engine.models.PerformanceProfile.MAXIMUM_PERFORMANCE -> maxOf(4, cores - 2)
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
     * Hardened for 7B-8B: uses ActivityManager.MemoryInfo.totalMem when
     * available (accurate), falls back to maxMemory heuristic only on error.
     */
    fun totalRamGb(context: android.content.Context? = null): Int {
        if (cachedRamGb > 0) return cachedRamGb
        val totalMem = runCatching {
            val ctx = context ?: throw IllegalStateException("no context")
            val am = ctx.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val info = android.app.ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)
            info.totalMem
        }.getOrElse {
            // Fallback: Java heap heuristic (conservative)
            Runtime.getRuntime().maxMemory() * 3
        }
        cachedRamGb = (totalMem / (1024L * 1024L * 1024L)).toInt().coerceAtLeast(2)
        return cachedRamGb
    }

    /** Overload without context for legacy callers (uses heuristic). */
    fun totalRamGbLegacy(): Int = totalRamGb(null)

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
        val perfCores = performanceCoreCount()
        val safeThreads = maximumSafeThreads()
        return "cores=$cores(p=$perfCores) tier=$tier ram=${ram}GB " +
            "threads=$safeThreads(max) rec=${recommendedThreads()} " +
            "ctx=${recommendedContextLength()} " +
            "batch=${recommendedBatchSize()} " +
            "budget=${(memoryBudgetFraction() * 100).toInt()}%"
    }
}
