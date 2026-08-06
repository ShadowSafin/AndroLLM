package io.androllm.engine.models

import io.androllm.core.common.AppConstants
import io.androllm.engine.utils.ThreadManager
import kotlinx.serialization.Serializable

/**
 * Backend used for token generation.
 */
enum class BackendType {
    QUALCOMM_QNN,
    LLAMA_CPP_VULKAN,
    ONNX_RUNTIME,
    CPU,
    VULKAN // Alias for LLAMA_CPP_VULKAN backward compatibility
}

/**
 * Hardware execution performance profiles.
 */
enum class PerformanceProfile {
    BATTERY_SAVER,
    BALANCED,
    MAXIMUM_PERFORMANCE
}

/**
 * Engine-wide configuration applied at initialization.
 */
@Serializable
data class EngineConfig(
    val backend: BackendType = BackendType.VULKAN,
    val threads: Int = ThreadManager.recommendedThreads(),
    val maxContextLength: Int = AppConstants.Model.DEFAULT_CONTEXT_LENGTH,
    val useVulkan: Boolean = true,
    val useFlashAttention: Boolean = false,
    val profile: PerformanceProfile = PerformanceProfile.BALANCED
)

/**
 * Per-model configuration applied when loading a GGUF model.
 */
@Serializable
data class ModelLoadConfig(
    val contextLength: Int = 0,
    val gpuLayers: Int = -1,
    val batchSize: Int = 2048,
    val threads: Int = ThreadManager.recommendedThreads(),
    val profile: PerformanceProfile = PerformanceProfile.BALANCED
)

/**
 * Token generation parameters.
 *
 * Field names map 1:1 to the JSON config consumed by the native bridge;
 * default values match the llama.cpp defaults so omitted fields need no
 * explicit transmission.
 */
@Serializable
data class GenerationConfig(
    val maxTokens: Int = 512,
    val temperature: Float = 0.8f,
    val topP: Float = 0.95f,
    val topK: Int = 40,
    val minP: Float = 0.05f,
    val typicalP: Float = 1.0f,
    val repetitionPenalty: Float = 1.0f,
    val presencePenalty: Float = 0.0f,
    val frequencyPenalty: Float = 0.0f,
    val dryMultiplier: Float = 0.0f,
    val dryBase: Float = 1.75f,
    val dryAllowedLength: Int = 2,
    val dryPenaltyLastN: Int = -1,
    val mirostat: Int = 0,
    val mirostatTau: Float = 5.0f,
    val mirostatEta: Float = 0.1f,
    val grammar: String = "",
    val jsonSchema: String = "",
    val reuseKvCache: Boolean = true,
    val seed: Long = -1,
    val stopSequences: List<String> = emptyList(),
    /**
     * When true the native engine logs every sampled token (step, id, decoded
     * text, top-5 logits, temperature, backend) to logcat. A decode-debugging
     * aid; keep false in production to avoid log spam.
     */
    val debugTokenLogging: Boolean = false
)

/**
 * A single chat message used to render the prompt with the model's
 * GGUF chat template.
 */
@Serializable
data class ChatPromptMessage(
    val role: String,
    val content: String
)

/**
 * A single token chunk produced during streaming generation.
 */
data class StreamChunk(
    val delta: String,
    val finished: Boolean,
    val tokenCount: Long = 0,
    val generatedTokens: Long = 0
)

/**
 * Metadata of a model currently loaded in the engine.
 */
data class EngineModelInfo(
    val id: String,
    val filePath: String,
    val contextLength: Int,
    val vocabSize: Int,
    val backend: BackendType,
    val quantization: String = "",
    val chatTemplate: String? = null,
    val architecture: String = "",
    val tokenizerModel: String = "",
    val generalName: String = "",
    val kvType: String = "",
    val nBatch: Int = 0,
    val nUbatch: Int = 0,
    val nThreads: Int = 0,
    val flashAttn: String = "",
    val templateReady: Boolean = false,
    val templateError: String = ""
)

/**
 * Full native diagnostics of the loaded model and the last generation,
 * exposed through the hidden debug panel.
 */
@Serializable
data class EngineDebugInfo(
    val desc: String = "",
    val generalName: String = "",
    val architecture: String = "",
    val tokenizerModel: String = "",
    val backend: String = "",
    val gpuName: String = "",
    val gpuDriverVersion: String = "",
    val gpuApiVersion: String = "",
    val gpuLayers: Int = 0,
    val totalLayers: Int = 0,
    val nCtxTrain: Long = 0,
    val nCtx: Int = 0,
    val nBatch: Int = 0,
    val nUbatch: Int = 0,
    val nThreads: Int = 0,
    val nVocab: Int = 0,
    val kvType: String = "",
    val flashAttn: String = "",
    val quantization: String = "",
    val sampler: String = "",
    val templateReady: Boolean = false,
    val templateError: String = "",
    val templateSource: String = "",
    val bosToken: String = "",
    val eosToken: String = "",
    val addBos: Boolean = false,
    val addEos: Boolean = false,
    val promptTokens: Long = 0,
    val promptTokenIds: List<Int> = emptyList(),
    val generatedTokens: Long = 0,
    val generatedTokenIds: List<Int> = emptyList(),
    val firstTokenMs: Long = 0,
    val stopReason: String = "",
    val promptText: String = "",
    val modelSizeBytes: Long = 0,
    val contextSizeBytes: Long = 0,
    val peakMemoryBytes: Long = 0,
    val backendReason: String = "",
    val gpuInferenceVerified: Boolean = false,
    // Vulkan correctness self-test diagnostics (diagnostic only — never used
    // to determine the active execution backend).
    val vulkanValidationStatus: String = "skipped", // "passed" | "failed" | "skipped"
    val vulkanValidationDetail: String = ""
)

/**
 * Performance statistics of the last generation.
 */
@Serializable
data class EngineStats(
    val promptTokens: Long = 0,
    val generatedTokens: Long = 0,
    val promptTimeMs: Long = 0,
    val generationTimeMs: Long = 0,
    val totalTimeMs: Long = 0,
    val tokensPerSecond: Float = 0f,
    val memoryPeakBytes: Long = 0,
    val firstTokenMs: Long = 0,
    val stopReason: String = ""
)

/**
 * Static capabilities reported by an engine implementation.
 */
data class EngineCapabilities(
    val name: String,
    val version: String,
    val backend: BackendType,
    val supportsStreaming: Boolean = true,
    val supportsGpuAcceleration: Boolean = false,
    val supportsQuantization: Boolean = true,
    val maxContextLength: Int = AppConstants.Model.DEFAULT_CONTEXT_LENGTH,
    val supportedFormats: List<String> = listOf("gguf")
)

/**
 * Result of a benchmark run.
 */
@Serializable
data class BenchmarkResult(
    val iterations: Int,
    val averageTokensPerSecond: Float,
    val bestTokensPerSecond: Float,
    val averagePromptTokensPerSecond: Float
)

/**
 * Error raised by the inference engine.
 */
class EngineException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)
