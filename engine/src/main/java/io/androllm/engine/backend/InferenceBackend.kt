package io.androllm.engine.backend

import io.androllm.engine.models.BackendType
import io.androllm.engine.models.GenerationConfig
import io.androllm.engine.models.ModelLoadConfig
import kotlinx.coroutines.flow.Flow

/**
 * Basic hardware information needed by backend selectors.
 */
data class EngineHardwareInfo(
    val cpuCores: Int = 8,
    val abi: String = "arm64-v8a",
    val isVulkanSupported: Boolean = true,
    val totalRamGb: Float = 8.0f
)

/**
 * Real-time telemetry metrics produced by an inference backend execution engine.
 */
data class BackendTelemetry(
    val backendType: BackendType,
    val hardwareAcceleratorName: String,
    val isGpuAccelerated: Boolean,
    val isNpuAccelerated: Boolean,
    val offloadedLayers: Int,
    val totalLayers: Int,
    val memoryUsageMb: Float,
    val promptTimeMs: Long,
    val promptTokens: Int,
    val generationTimeMs: Long,
    val generatedTokens: Int,
    val tokensPerSecond: Float
)

/**
 * Common interface implemented by all execution backends (QNN, llama.cpp Vulkan, ONNX Runtime, CPU).
 */
interface InferenceBackend {
    val backendType: BackendType
    fun isSupported(hardwareInfo: EngineHardwareInfo): Boolean
    suspend fun loadModel(modelPath: String, config: ModelLoadConfig): Result<Unit>
    fun generate(prompt: String, config: GenerationConfig): Flow<String>
    fun cancel()
    fun unload()
    fun getTelemetry(): BackendTelemetry
}
