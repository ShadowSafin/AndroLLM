package io.androllm.engine.diagnostics

import io.androllm.engine.backend.BackendCapabilities
import io.androllm.engine.models.EngineDebugInfo
import io.androllm.engine.models.EngineStats
import io.androllm.engine.models.MemoryStats

/**
 * Aggregated diagnostics snapshot for the developer diagnostics panel.
 *
 * Combines engine state, performance metrics, memory telemetry, and
 * backend capabilities into a single view-model for the diagnostics UI.
 * Produced by [EngineDiagnosticsCollector] and consumed by the developer
 * settings screen.
 */
data class EngineDiagnostics(
    /** Active backend (GPU / NPU / CPU). */
    val activeBackend: String = "Unknown",
    /** Model name of the loaded artifact. */
    val modelName: String = "",
    /** Model family (Gemma, Qwen, etc.). */
    val family: String = "",
    /** Context length (tokens). */
    val contextSize: Int = 0,
    /** Memory budget as percentage of available RAM. */
    val memoryBudgetPercent: Int = 0,

    // --- Performance Metrics ---
    /** First-token latency of the last generation (ms). */
    val firstTokenLatencyMs: Long = 0,
    /** Decode throughput (tokens/sec). */
    val tokensPerSecond: Float = 0f,
    /** Average decode throughput across all generations in this session. */
    val averageTokensPerSecond: Float = 0f,
    /** Peak decode throughput observed. */
    val peakTokensPerSecond: Float = 0f,
    /** Prompt evaluation throughput (tokens/sec). */
    val promptTokensPerSecond: Float = 0f,
    /** Total generation time of the last run (ms). */
    val lastGenerationTimeMs: Long = 0,
    /** Total number of generations in this session. */
    val totalGenerations: Long = 0,

    // --- Memory Metrics ---
    /** Model file size (bytes). */
    val modelSizeBytes: Long = 0,
    /** Native heap allocated (bytes). */
    val nativeHeapBytes: Long = 0,
    /** Process PSS (bytes) — best total RAM view. */
    val processPssBytes: Long = 0,
    /** Peak process PSS observed (bytes). */
    val peakPssBytes: Long = 0,
    /** KV-cache token count (live). */
    val kvCacheTokens: Long = 0,
    /** Java heap used (bytes). */
    val javaHeapBytes: Long = 0,

    // --- Backend Info ---
    /** Backend vendor (Qualcomm, ARM, etc.). */
    val vendor: String = "",
    /** Accelerator name (Adreno, Mali, Hexagon HTP, etc.). */
    val accelerator: String = "",
    /** Runtime delegate label (XNNPACK, LiteRT GPU, LiteRT Delegate). */
    val delegate: String = "",
    /** Backend initialization time (ms). */
    val backendInitMs: Long = 0,

    // --- Device Info ---
    /** Device tier (low, medium, high). */
    val deviceTier: String = "",
    /** Hardware core count. */
    val coreCount: Int = 0,
    /** Estimated total RAM (GB). */
    val ramGb: Int = 0,
    /** Thread count used for inference. */
    val threadCount: Int = 0,

    // --- Pipeline Performance ---
    /** Model initialization time (ms). */
    val modelInitTimeMs: Double = 0.0,
    /** Container metadata read time (ms). */
    val containerReadTimeMs: Double = 0.0,
    /** Conversation creation time (ms). */
    val conversationCreateTimeMs: Double = 0.0,

    // --- Error Info ---
    /** Recent errors (last 5). */
    val recentErrors: List<String> = emptyList(),
    /** Cancellation status. */
    val isCancelled: Boolean = false,
    /** Backend fallback count. */
    val backendFallbackCount: Int = 0,
    /** Recent crash/error events from the crash guard. */
    val recentCrashEvents: List<EngineCrashGuard.CrashEvent> = emptyList(),
    /** Crash guard summary. */
    val crashSummary: String = "No crashes recorded",

    // --- Prefix Cache ---
    /** Prefix cache hit rate. */
    val prefixCacheHitRate: Float = 0f,
    /** Prefix cache hit/miss summary. */
    val prefixCacheSummary: String = "",

    // --- Buffer Pool ---
    /** Buffer pool outstanding count. */
    val bufferPoolOutstanding: Int = 0,
    /** Buffer pool summary. */
    val bufferPoolSummary: String = "",

    // --- Threading ---
    /** Maximum safe thread count for this device. */
    val maxSafeThreads: Int = 0,
    /** Performance core count estimate. */
    val performanceCores: Int = 0,

    // --- Raw Debug Info ---
    /** Full engine debug info from the last snapshot. */
    val rawDebugInfo: EngineDebugInfo? = null
) {
    /** Human-readable model size. */
    val modelSizeMb: Float get() = modelSizeBytes / (1024f * 1024f)

    /** Human-readable native heap. */
    val nativeHeapMb: Float get() = nativeHeapBytes / (1024f * 1024f)

    /** Human-readable PSS. */
    val processPssMb: Float get() = processPssBytes / (1024f * 1024f)

    /** Human-readable peak PSS. */
    val peakPssMb: Float get() = peakPssBytes / (1024f * 1024f)

    /** Summary line for the diagnostics panel header. */
    val summaryLine: String
        get() = buildString {
            append("$activeBackend | ")
            append("$modelName | ")
            append("${tokensPerSecond.toInt()} tok/s | ")
            append("${firstTokenLatencyMs}ms TTFT")
        }
}

/**
 * Collects diagnostics from the engine's various state flows and the
 * performance monitor into a single [EngineDiagnostics] snapshot.
 */
object EngineDiagnosticsCollector {

    /**
     * Produces a diagnostics snapshot from the current engine state.
     */
    fun collect(
        debugInfo: EngineDebugInfo?,
        stats: EngineStats?,
        memoryStats: MemoryStats?,
        backendCapabilities: BackendCapabilities,
        performanceStats: EngineStats?,
        deviceTier: String,
        coreCount: Int,
        ramGb: Int,
        threadCount: Int
    ): EngineDiagnostics {
        // Pipeline stage timings from the performance monitor
        val modelInitTiming = EnginePerformanceMonitor.getStats(EnginePerformanceMonitor.Stages.MODEL_INIT)
        val containerReadTiming = EnginePerformanceMonitor.getStats(EnginePerformanceMonitor.Stages.CONTAINER_READ)
        val convCreateTiming = EnginePerformanceMonitor.getStats(EnginePerformanceMonitor.Stages.CONVERSATION_CREATE)

        return EngineDiagnostics(
            activeBackend = debugInfo?.backend?.uppercase() ?: "Unknown",
            modelName = debugInfo?.generalName ?: "",
            family = debugInfo?.family ?: "",
            contextSize = debugInfo?.nCtx ?: 0,
            memoryBudgetPercent = (io.androllm.engine.utils.ThreadManager.memoryBudgetFraction() * 100).toInt(),

            // Performance
            firstTokenLatencyMs = stats?.firstTokenMs ?: 0,
            tokensPerSecond = stats?.tokensPerSecond ?: 0f,
            averageTokensPerSecond = stats?.averageTokensPerSecond ?: 0f,
            peakTokensPerSecond = stats?.peakTokensPerSecond ?: debugInfo?.peakTokensPerSecond ?: 0f,
            promptTokensPerSecond = stats?.promptTokensPerSecond ?: 0f,
            lastGenerationTimeMs = stats?.totalTimeMs ?: 0,
            totalGenerations = 0, // Would need a counter in the engine

            // Memory
            modelSizeBytes = debugInfo?.modelSizeBytes ?: 0,
            nativeHeapBytes = debugInfo?.currentRamBytes ?: memoryStats?.nativeHeapAllocatedBytes ?: 0,
            processPssBytes = memoryStats?.processPssBytes ?: 0,
            peakPssBytes = memoryStats?.peakMemoryBytes ?: 0,
            kvCacheTokens = memoryStats?.kvCacheTokens ?: -1,
            javaHeapBytes = memoryStats?.javaHeapUsedBytes ?: 0,

            // Backend
            vendor = debugInfo?.npuVendor ?: stats?.vendor ?: "",
            accelerator = debugInfo?.npuAccelerator ?: debugInfo?.gpuName ?: stats?.accelerator ?: "",
            delegate = debugInfo?.delegate ?: stats?.delegate ?: "",
            backendInitMs = debugInfo?.backendInitMs ?: stats?.initTimeMs ?: 0,

            // Device
            deviceTier = deviceTier,
            coreCount = coreCount,
            ramGb = ramGb,
            threadCount = threadCount,

            // Pipeline
            modelInitTimeMs = modelInitTiming?.avgMs ?: 0.0,
            containerReadTimeMs = containerReadTiming?.avgMs ?: 0.0,
            conversationCreateTimeMs = convCreateTiming?.avgMs ?: 0.0,

            // Crash telemetry
            recentCrashEvents = EngineCrashGuard.getRecentEvents().take(10),
            crashSummary = EngineCrashGuard.crashSummary(),

            // Prefix cache
            prefixCacheHitRate = io.androllm.engine.core.PrefixCache.stats().hitRate,
            prefixCacheSummary = io.androllm.engine.core.PrefixCache.stats().summary(),

            // Buffer pool
            bufferPoolOutstanding = io.androllm.engine.core.BufferPool.stats().outstandingBuffers,
            bufferPoolSummary = io.androllm.engine.core.BufferPool.stats().summary(),

            // Threading
            maxSafeThreads = io.androllm.engine.utils.ThreadManager.maximumSafeThreads(),
            performanceCores = io.androllm.engine.utils.ThreadManager.performanceCoreCount(),

            rawDebugInfo = debugInfo
        )
    }
}
