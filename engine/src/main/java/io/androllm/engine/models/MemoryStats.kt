package io.androllm.engine.models

import kotlinx.serialization.Serializable

/**
 * Real-time memory telemetry from the native inference engine.
 *
 * Contains both CPU and GPU memory statistics for comprehensive
 * engine status reporting.
 */
@Serializable
data class MemoryStats(
    val modelSizeBytes: Long = 0L,
    val contextSizeBytes: Long = 0L,
    /**
     * Live tokens held in the conversation's KV cache (LiteRT-LM
     * `Conversation.getTokenCount()`). This is the REAL KV-cache occupancy —
     * LiteRT-LM does not expose the cache in bytes, so token count is the
     * authoritative live value. -1 = not readable on this runtime.
     */
    val kvCacheTokens: Long = -1L,
    val peakMemoryBytes: Long = 0L,
    val gpuLayersOffloaded: Int = 0,
    val totalLayers: Int = 0,
    val backend: String = "cpu",
    val loadedSinceMs: Long = 0L,
    // GPU-specific fields
    val gpuMemoryUsedBytes: Long = 0L,
    val cpuMemoryUsedBytes: Long = 0L,
    val gpuMemoryAllocatedBytes: Long = 0L,
    val gpuMemoryPeakBytes: Long = 0L,
    val gpuMemoryFreeBytes: Long = 0L,
    val gpuMemoryTotalBytes: Long = 0L,
    val gpuBufferCount: Int = 0,
    val gpuName: String = "",
    val gpuDriverVersion: String = "",
    val gpuApiVersion: String = "",
    // Live Android process-memory telemetry. LiteRT-LM does not currently
    // expose per-component allocator counters, so a zero in the legacy
    // delegate fields below means "not exposed" rather than measured zero.
    val nativeHeapAllocatedBytes: Long = 0L,
    val nativeHeapSizeBytes: Long = 0L,
    val javaHeapUsedBytes: Long = 0L,
    val javaHeapCommittedBytes: Long = 0L,
    /** Process proportional-set size; this is the best live total RAM view. */
    val processPssBytes: Long = 0L,
    val backendReason: String = "",
    val gpuInferenceVerified: Boolean = false,
    // Vulkan correctness self-test diagnostics. This is a diagnostic-only
    // result that NEVER determines the active execution backend — the runtime
    // backend is derived from [backend] + [gpuLayersOffloaded] only.
    val vulkanValidationStatus: String = "skipped", // "passed" | "failed" | "skipped"
    val vulkanValidationDetail: String = "",
    // Runtime corruption recovery telemetry. recoveryCount is how many times
    // the generation wrapper escalated a corrupted run (NaN/INF logits, invalid
    // token ids, decode failures, degenerate repetition). lastRecoveryReason
    // carries the most recent corruption detail. cpuSessionFallback is true
    // once GPU recovery failed and the session permanently serves on CPU.
    val recoveryCount: Int = 0,
    val lastRecoveryReason: String = "",
    val cpuSessionFallback: Boolean = false,
    // Vulkan diagnostics from the last generation (native_api.cpp):
    // lastContextCreateMs — time to build a fresh llama_context (pipelines,
    //   descriptor pools, command pools, buffers) from the resident model.
    // lastCleanupMs — time to free the previous context's GPU state after EOS.
    // decodeCount / decodeAvgMs — llama_decode calls and average submit+fence
    //   wait in the last generation (fence waits are inside llama_decode).
    // vulkanDeviceLostRecoveries — VK_ERROR_DEVICE_LOST events caught and
    //   recovered (full backend reload) instead of crashing.
    val lastContextCreateMs: Long = 0,
    val lastCleanupMs: Long = 0,
    val decodeCount: Long = 0,
    val decodeAvgMs: Long = 0,
    val vulkanDeviceLostRecoveries: Int = 0
) {
    /** Live native allocation when Android exposes it; otherwise legacy total. */
    val totalNativeBytes: Long get() = nativeHeapAllocatedBytes.takeIf { it > 0L }
        ?: (modelSizeBytes + contextSizeBytes)

    /** Live process RAM (PSS) when Android exposes it, otherwise native heap. */
    val totalRuntimeBytes: Long get() = processPssBytes.takeIf { it > 0L } ?: totalNativeBytes

    val hasKvCacheMetric: Boolean get() = contextSizeBytes > 0L
    /** True when the live KV-cache token counter is readable on this runtime. */
    val hasKvCacheTokenMetric: Boolean get() = kvCacheTokens >= 0L
    val hasGpuAllocatedMetric: Boolean get() = gpuMemoryAllocatedBytes > 0L
    val hasGpuUsedMetric: Boolean get() = gpuMemoryUsedBytes > 0L
    val hasGpuFreeTotalMetric: Boolean get() = gpuMemoryFreeBytes > 0L && gpuMemoryTotalBytes > 0L
    val hasGpuPeakMetric: Boolean get() = gpuMemoryPeakBytes > 0L
    val hasGpuBufferMetric: Boolean get() = gpuBufferCount > 0

    /**
     * True when runtime inference is offloaded to a GPU. LiteRT-LM reports the
     * active delegate via [backend] == "gpu" (it has no per-layer offload
     * concept); the legacy llama.cpp runtime reported "vulkan" +
     * [gpuLayersOffloaded]. Both count as GPU acceleration.
     */
    val isGpuAccelerated: Boolean get() = backend == "gpu" || (backend == "vulkan" && gpuLayersOffloaded > 0)

    /** Human-readable name of the active GPU backend ("" when on CPU). */
    val gpuBackendLabel: String get() = when {
        backend == "gpu" -> "LiteRT GPU"
        backend == "vulkan" && gpuLayersOffloaded > 0 -> "Vulkan"
        else -> ""
    }

    /** Vulkan correctness self-test passed (diagnostic only). */
    val vulkanValidationPassed: Boolean get() = vulkanValidationStatus == "passed"

    /** Vulkan correctness self-test reported a mismatch (diagnostic only). */
    val vulkanValidationFailed: Boolean get() = vulkanValidationStatus == "failed"

    /** Runtime execution mode derived from actual GPU offload, never validation. */
    val executionMode: String get() = when {
        // LiteRT delegates the whole graph — there is no hybrid split.
        backend == "gpu" -> "GPU only"
        isGpuAccelerated && cpuLayers > 0 -> "Hybrid"
        isGpuAccelerated -> "GPU only"
        else -> "CPU only"
    }

    /** True once the session permanently fell back to CPU after GPU recovery failed. */
    val isCpuSessionFallback: Boolean get() = cpuSessionFallback

    /**
     * True only when the model genuinely fell back to CPU at runtime.
     *
     * Heuristic couples to the native reason strings set in native_api.cpp
     * ("Vulkan unavailable: …" / "GPU init failed: …"). Keep those two phrases
     * stable if this warning must keep showing; a deliberate CPU load
     * ("CPU backend (no GPU offload)") is intentionally NOT a fallback.
     */
    val isCpuFallback: Boolean get() = !isGpuAccelerated && (
        backendReason.contains("unavailable", ignoreCase = true) ||
            backendReason.contains("init failed", ignoreCase = true)
        )

    val cpuLayers: Int get() = totalLayers - gpuLayersOffloaded
    val gpuLayersDisplay: String get() = when {
        backend == "gpu" -> "All ops (delegate)"
        isGpuAccelerated && totalLayers > 0 -> "$gpuLayersOffloaded / $totalLayers"
        isGpuAccelerated -> "$gpuLayersOffloaded / ?"
        else -> "0 / ?"
    }
    val gpuMemoryUsedMb: Float get() = gpuMemoryUsedBytes / (1024f * 1024f)
    val cpuMemoryUsedMb: Float get() = cpuMemoryUsedBytes / (1024f * 1024f)

    fun modelSizeMb(): Float = modelSizeBytes / (1024f * 1024f)
    fun contextSizeMb(): Float = contextSizeBytes / (1024f * 1024f)
    fun totalNativeMb(): Float = totalNativeBytes / (1024f * 1024f)
    fun totalRuntimeMb(): Float = totalRuntimeBytes / (1024f * 1024f)
    fun peakMb(): Float = peakMemoryBytes / (1024f * 1024f)
    fun gpuMemoryMb(): Float = gpuMemoryUsedMb
    fun gpuMemoryPeakMb(): Float = gpuMemoryPeakBytes / (1024f * 1024f)
    fun gpuMemoryAllocatedMb(): Float = gpuMemoryAllocatedBytes / (1024f * 1024f)
    fun gpuMemoryFreeMb(): Float = gpuMemoryFreeBytes / (1024f * 1024f)
    fun gpuMemoryTotalMb(): Float = gpuMemoryTotalBytes / (1024f * 1024f)
}
