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
    val backendReason: String = "",
    val gpuInferenceVerified: Boolean = false,
    // Vulkan correctness self-test diagnostics. This is a diagnostic-only
    // result that NEVER determines the active execution backend — the runtime
    // backend is derived from [backend] + [gpuLayersOffloaded] only.
    val vulkanValidationStatus: String = "skipped", // "passed" | "failed" | "skipped"
    val vulkanValidationDetail: String = ""
) {
    val totalNativeBytes: Long get() = modelSizeBytes + contextSizeBytes

    /** True only when the runtime inference pipeline is offloaded to Vulkan. */
    val isGpuAccelerated: Boolean get() = backend == "vulkan" && gpuLayersOffloaded > 0

    /** Vulkan correctness self-test passed (diagnostic only). */
    val vulkanValidationPassed: Boolean get() = vulkanValidationStatus == "passed"

    /** Vulkan correctness self-test reported a mismatch (diagnostic only). */
    val vulkanValidationFailed: Boolean get() = vulkanValidationStatus == "failed"

    /** Runtime execution mode derived from actual GPU offload, never validation. */
    val executionMode: String get() = when {
        isGpuAccelerated && cpuLayers > 0 -> "Hybrid"
        isGpuAccelerated -> "GPU only"
        else -> "CPU only"
    }

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
    val gpuLayersDisplay: String get() = if (isGpuAccelerated && totalLayers > 0) "$gpuLayersOffloaded / $totalLayers" else if (isGpuAccelerated) "$gpuLayersOffloaded / ?" else "0 / ?"
    val gpuMemoryUsedMb: Float get() = gpuMemoryUsedBytes / (1024f * 1024f)
    val cpuMemoryUsedMb: Float get() = cpuMemoryUsedBytes / (1024f * 1024f)

    fun modelSizeMb(): Float = modelSizeBytes / (1024f * 1024f)
    fun contextSizeMb(): Float = contextSizeBytes / (1024f * 1024f)
    fun totalNativeMb(): Float = totalNativeBytes / (1024f * 1024f)
    fun peakMb(): Float = peakMemoryBytes / (1024f * 1024f)
    fun gpuMemoryMb(): Float = gpuMemoryUsedMb
    fun gpuMemoryPeakMb(): Float = gpuMemoryPeakBytes / (1024f * 1024f)
    fun gpuMemoryAllocatedMb(): Float = gpuMemoryAllocatedBytes / (1024f * 1024f)
    fun gpuMemoryFreeMb(): Float = gpuMemoryFreeBytes / (1024f * 1024f)
    fun gpuMemoryTotalMb(): Float = gpuMemoryTotalBytes / (1024f * 1024f)
}
