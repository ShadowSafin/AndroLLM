package io.androllm.engine.backend

import io.androllm.engine.models.PerformanceProfile
import io.androllm.engine.utils.ThreadManager

/**
 * Device-class-specific performance presets that tune the engine for
 * maximum speed on each class of Android device.
 *
 * Each profile configures thread count, batch size, context length,
 * memory budget, streaming update rate, and backend preference to
 * match the device's capabilities.
 */
object PerformanceProfiles {

    data class Profile(
        val name: String,
        val threadCount: Int,
        val batchSize: Int,
        val contextLength: Int,
        val memoryBudgetFraction: Float,
        /** Minimum interval between UI streaming updates (ms). */
        val streamingUpdateIntervalMs: Long,
        /** Maximum output tokens per streaming run. */
        val maxStreamingTokens: Int,
        /** Whether to enable interpreter warmup after model load. */
        val enableWarmup: Boolean,
        /** Whether to cache decoded output between turns. */
        val cacheDecodedOutput: Boolean
    )

    /** Profile for low-end phones (3-4 cores, 2-3 GB RAM). */
    val LOW_END = Profile(
        name = "low-end",
        threadCount = 2,
        batchSize = 512,
        contextLength = 2048,
        memoryBudgetFraction = 0.45f,
        streamingUpdateIntervalMs = 32L,  // ~30 FPS — reduces recomposition storms
        maxStreamingTokens = 4096,
        enableWarmup = false,  // Skip warmup to save battery
        cacheDecodedOutput = true
    )

    /** Profile for mid-range phones (6-8 cores, 4-6 GB RAM). */
    val MID_RANGE = Profile(
        name = "mid-range",
        threadCount = 3,
        batchSize = 1024,
        contextLength = 4096,
        memoryBudgetFraction = 0.60f,
        streamingUpdateIntervalMs = 16L,  // ~60 FPS
        maxStreamingTokens = 8192,
        enableWarmup = true,
        cacheDecodedOutput = true
    )

    /** Profile for flagship phones (8+ cores, 8+ GB RAM). */
    val FLAGSHIP = Profile(
        name = "flagship",
        threadCount = 4,
        batchSize = 2048,
        contextLength = 8192,
        memoryBudgetFraction = 0.70f,
        streamingUpdateIntervalMs = 16L,  // 60 FPS
        maxStreamingTokens = 8192,
        enableWarmup = true,
        cacheDecodedOutput = true
    )

    /** Profile optimized for GPU inference. */
    val GPU_OPTIMIZED = Profile(
        name = "gpu",
        threadCount = 2,  // GPU does the heavy lifting
        batchSize = 2048,
        contextLength = 8192,
        memoryBudgetFraction = 0.65f,
        streamingUpdateIntervalMs = 16L,
        maxStreamingTokens = 8192,
        enableWarmup = true,
        cacheDecodedOutput = true
    )

    /** Profile optimized for NPU inference. */
    val NPU_OPTIMIZED = Profile(
        name = "npu",
        threadCount = 2,  // NPU handles compute
        batchSize = 2048,
        contextLength = 8192,
        memoryBudgetFraction = 0.65f,
        streamingUpdateIntervalMs = 16L,
        maxStreamingTokens = 8192,
        enableWarmup = true,
        cacheDecodedOutput = true
    )

    /** Profile optimized for CPU-only inference. */
    val CPU_OPTIMIZED = Profile(
        name = "cpu",
        threadCount = ThreadManager.recommendedThreads(),
        batchSize = 1024,
        contextLength = 4096,
        memoryBudgetFraction = 0.55f,
        streamingUpdateIntervalMs = 32L,  // Lower FPS to save CPU for inference
        maxStreamingTokens = 8192,
        enableWarmup = true,
        cacheDecodedOutput = true
    )

    /**
     * Returns the best profile for the current device based on the
     * device tier and the active backend.
     */
    fun forDevice(): Profile {
        val tier = ThreadManager.deviceTier()
        return when {
            tier == "low" -> LOW_END
            tier == "low-medium" -> LOW_END
            tier == "medium" -> MID_RANGE
            else -> FLAGSHIP
        }
    }

    /**
     * Returns the best profile for a specific backend type.
     */
    fun forBackend(backend: io.androllm.engine.models.BackendType): Profile {
        return when (backend) {
            io.androllm.engine.models.BackendType.GPU -> GPU_OPTIMIZED
            io.androllm.engine.models.BackendType.NPU -> NPU_OPTIMIZED
            io.androllm.engine.models.BackendType.CPU -> CPU_OPTIMIZED
            else -> forDevice()
        }
    }

    /**
     * Returns the best profile combining device class and backend.
     * The backend-specific tuning overrides device defaults where relevant.
     */
    fun optimal(backend: io.androllm.engine.models.BackendType): Profile {
        val device = forDevice()
        val backendProfile = forBackend(backend)
        // Use device's thread count (it knows the core layout) but
        // backend-specific batch size and streaming rate.
        return device.copy(
            name = "${device.name}+${backendProfile.name}",
            batchSize = backendProfile.batchSize,
            streamingUpdateIntervalMs = backendProfile.streamingUpdateIntervalMs,
            maxStreamingTokens = backendProfile.maxStreamingTokens
        )
    }
}
