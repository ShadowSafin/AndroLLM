package io.androllm.engine.models

import io.androllm.core.common.AppConstants
import io.androllm.engine.backend.BackendCapabilities
import io.androllm.engine.utils.ThreadManager
import kotlinx.serialization.Serializable

/**
 * Backend used for token generation.
 *
 * LiteRT-LM (the production runtime) executes on CPU (XNNPACK), GPU (the
 * OpenCL-based LiteRT GPU delegate) or NPU (LiteRT dispatch delegates —
 * Qualcomm Hexagon, MediaTek NeuroPilot, Google Tensor). The legacy
 * llama.cpp-era values ([LLAMA_CPP_VULKAN], [VULKAN]) are kept for
 * serializer/UI compatibility with older persisted state but are never
 * produced by the LiteRT engine.
 */
@kotlinx.serialization.Serializable
enum class BackendType {
    QUALCOMM_QNN,
    LLAMA_CPP_VULKAN,
    ONNX_RUNTIME,
    CPU,
    /** LiteRT GPU delegate (OpenCL-based on Android). */
    GPU,
    VULKAN, // Alias for LLAMA_CPP_VULKAN backward compatibility

    /**
     * Automatic backend selection: NPU → GPU → CPU, decided at model load
     * from the startup hardware probe. Never persisted as an *active*
     * backend — it resolves to a concrete backend before any engine is built.
     */
    AUTO,
    /** LiteRT NPU delegate (vendor dispatch — Qualcomm/MediaTek/Google Tensor). */
    NPU
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
    /**
     * Desired backend. AUTO (default) resolves at model load via the startup
     * probe (NPU → GPU → CPU); the engine never persists this value.
     */
    val backend: BackendType = BackendType.AUTO,
    val threads: Int = ThreadManager.recommendedThreads(),
    val maxContextLength: Int = AppConstants.Model.DEFAULT_CONTEXT_LENGTH,
    val useVulkan: Boolean = true,
    val useFlashAttention: Boolean = true,
    val profile: PerformanceProfile = PerformanceProfile.BALANCED
)

/**
 * Per-model configuration applied when loading a model artifact.
 */
@Serializable
data class ModelLoadConfig(
    val contextLength: Int = 0,
    val gpuLayers: Int = -1,
    /**
     * Explicit backend request. Null (default) means automatic selection:
     * [BackendType.AUTO] behavior driven by the startup probe (NPU → GPU →
     * CPU, silently skipping backends the model or device cannot use), or
     * the legacy [gpuLayers] == 0 CPU-forcing convention. [BackendType.CPU]
     * is the equivalent of the old `gpuLayers = 0` debug override.
     */
    val backend: BackendType? = null,
    val batchSize: Int = 2048,
    val threads: Int = ThreadManager.recommendedThreads(),
    val profile: PerformanceProfile = PerformanceProfile.BALANCED,
    /**
     * Opt-in CPU-vs-GPU correctness validation at load time. Loading a second
     * full copy of the model on CPU doubles peak RAM and adds minutes to the
     * load, so it defaults to OFF; the native result is diagnostic-only and
     * never changes the active backend.
     */
    val runBackendValidation: Boolean = false,
    /**
     * Post-load coherence self-test: after the model loads, a short
     * temperature-0 probe generation runs and its output is checked for
     * tokenizer/weight corruption (blank, non-printable garbage, degenerate
     * repetition). A failing model is unloaded and reported instead of
     * producing gibberish in chat. Cheap (~12 tokens); ON by default.
     */
    val runSelfTest: Boolean = true
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
    // Effectively unlimited: the native engine clamps the actual budget to
    // the model's context window (nCtx - prompt), so generation stops at the
    // model's natural end (EOS) or when context fills — never at this cap.
    val maxTokens: Int = 65536,
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
    /**
     * Enables thinking blocks in chat templates that support it (Qwen2.5/Qwen3).
     * `false` (the default) is the safe choice for all other models; the native
     * template renderer threads this into the Jinja `enable_thinking` variable.
     */
    val enableThinking: Boolean = false,
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
 * A single chat message used to drive the model's chat template
 * (applied internally by LiteRT-LM).
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
    val generatedTokens: Long = 0,
    /**
     * True when [delta] is reasoning/thinking text (Qwen3/Gemma3 thinking
     * models) rather than the final decoded answer. Thinking text streams so
     * the UI shows live progress, but it is never persisted as part of the
     * assistant message.
     */
    val isThinking: Boolean = false
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
    val family: String = "",
    /** Accelerator vendor of the ACTIVE backend (e.g. "Qualcomm", "ARM"). */
    val vendor: String = "",
    /** Accelerator block (e.g. "Hexagon HTP", "Adreno"). */
    val accelerator: String = "",
    /** Runtime delegate label (e.g. "LiteRT Delegate"). */
    val delegate: String = "",
    /** Time to build the native engine on this backend, in ms. */
    val backendInitMs: Long = 0L,
    /** True when the family emits native `<|tool_call|>` markers (chat layer skips the compat planner). */
    val nativeToolMarkers: Boolean = false,
    /**
     * Upper bound (chars) the chat layer applies to the tool-advertisement
     * system message for this model's family (see ModelFamily); small models
     * degrade to empty/garbage output with a long tool list.
     */
    val toolAdvertisementCapChars: Int = Int.MAX_VALUE,
    val templateSource: String = "",
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
    val family: String = "",
    val tokenizerModel: String = "",
    val backend: String = "",
    val gpuName: String = "",
    val gpuDriverVersion: String = "",
    val gpuApiVersion: String = "",
    // NPU diagnostics of the active backend (empty when not on NPU).
    val npuName: String = "",
    val npuVendor: String = "",
    val npuAccelerator: String = "",
    /** Runtime delegate label of the ACTIVE backend ("XNNPACK" / "LiteRT GPU" / "LiteRT Delegate"). */
    val delegate: String = "",
    /** LiteRT runtime version of the active delegate. */
    val delegateVersion: String = "",
    /** Wall-clock time to build the native engine on the active backend (ms). */
    val backendInitMs: Long = 0,
    /** Current native heap of this process at snapshot time (bytes). */
    val currentRamBytes: Long = 0,
    /** Peak tok/s observed in the last generation. */
    val peakTokensPerSecond: Float = 0f,
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
    val vulkanValidationDetail: String = "",
    // Runtime corruption recovery telemetry (see MemoryStats for semantics).
    val recoveryCount: Int = 0,
    val lastRecoveryReason: String = "",
    val cpuSessionFallback: Boolean = false,
    // Vulkan diagnostics from the last generation (native_api.cpp):
    // lastContextCreateMs — time to build a fresh llama_context (pipelines,
    //   descriptor pools, command pools, buffers) from the resident model.
    // lastCleanupMs — time to free the previous context's GPU state after EOS.
    // decodeCount / decodeAvgMs — llama_decode calls and average submit+fence
    //   wait in the last generation (fence waits are inside llama_decode).
    // vulkanDeviceLostRecoveries — VK_ERROR_DEVICE_LOST events that were
    //   caught and recovered (full backend reload) instead of crashing.
    val lastContextCreateMs: Long = 0,
    val lastCleanupMs: Long = 0,
    val decodeCount: Long = 0,
    val decodeAvgMs: Long = 0,
    val vulkanDeviceLostRecoveries: Int = 0
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
    val stopReason: String = "",
    // Backend runtime metrics of the last generation (""/0 when unknown).
    val backend: String = "",
    val delegate: String = "",
    val vendor: String = "",
    val accelerator: String = "",
    val initTimeMs: Long = 0,
    val peakTokensPerSecond: Float = 0f,
    val currentRamBytes: Long = 0
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
    val supportsNpuAcceleration: Boolean = false,
    val supportsQuantization: Boolean = true,
    val maxContextLength: Int = AppConstants.Model.DEFAULT_CONTEXT_LENGTH,
    val supportedFormats: List<String> = listOf("litertlm"),
    /**
     * Full startup hardware probe (SoC, GPU/NPU names, usable backends).
     * Populated by the engine's `initialize()`; safe default all-unknown.
     */
    val backendCapabilities: BackendCapabilities = BackendCapabilities()
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
 * One backend's result in a cross-backend comparison benchmark (Developer
 * Settings → Benchmark Backends). Runs the identical prompt on each backend
 * and reports throughput, latency, initialization time and peak RAM.
 */
@Serializable
data class BackendBenchmarkResult(
    val backend: BackendType,
    val backendLabel: String = "",
    val vendor: String = "",
    val accelerator: String = "",
    val averageTokensPerSecond: Float = 0f,
    val peakTokensPerSecond: Float = 0f,
    val promptLatencyMs: Long = 0,
    val firstTokenMs: Long = 0,
    val generationTimeMs: Long = 0,
    val initTimeMs: Long = 0,
    val peakRamBytes: Long = 0,
    val succeeded: Boolean = true,
    val error: String = ""
) {
    val peakRamMb: Float get() = peakRamBytes / (1024f * 1024f)
}

/**
 * Error raised by the inference engine.
 */
class EngineException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)
