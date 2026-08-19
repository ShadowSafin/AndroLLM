package io.androllm.core.models.catalog

import kotlinx.serialization.Serializable

/**
 * Root of a catalog JSON document. Adding a model to the app only requires
 * a metadata edit in this JSON - no code changes.
 */
@Serializable
data class CatalogFile(
    val schemaVersion: Int = 1,
    val source: String? = null,
    val generatedAt: Long = 0,
    val models: List<CatalogModel> = emptyList()
)

/**
 * A curated catalog entry for a single model artifact on a remote repository.
 * All 41 fields are metadata - nothing here is code-driven.
 */
@Serializable
data class CatalogModel(
    val id: String,
    val name: String,
    val description: String = "",
    val family: String = "",
    val architecture: String = "",
    val categories: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val license: String = "Apache-2.0",
    val author: String = "",
    val repoId: String = "",
    val revision: String = "main",
    val fileName: String = "",
    val downloadUrl: String = "",
    val sizeBytes: Long = 0,
    val parameters: String = "",
    val quantization: String = "",
    val contextLength: Int = 4096,
    val chatTemplate: String? = null,
    val minRamGb: Float = 4.0f,
    val recommendedRamGb: Float = 8.0f,
    val expectedTokSec: String? = null,
    val downloads: Long = 0,
    val likes: Long = 0,
    val trendingScore: Long = 0,
    val sha256: String? = null,
    /**
     * Optional companion artifact downloaded next to the main file — e.g. the
     * Gemma 3 `sentencepiece.model` tokenizer for the EmbeddingGemma `.tflite`.
     * Downloaded as `tokenizer.model` beside the model file.
     */
    val companionUrl: String = "",
    val publishedAt: Long = 0,
    val isGated: Boolean = false,
    val modality: String = "TEXT",
    val modelType: String? = null,
    val status: String = "STABLE",
    /**
     * The model-specific stop sequences that terminate generation. The engine
     * merges these with the family's official stop tokens automatically at
     * load — a Qwen3 entry declaring `<|im_end|>`/`<|endoftext|>` guarantees
     * generation stops even if the container's own metadata is incomplete.
     */
    val stopSequences: List<String> = emptyList(),
    val badges: List<String> = emptyList(),
    val strengths: List<String> = emptyList(),
    val weaknesses: List<String> = emptyList(),
    val notes: String? = null,
    val recommended: Boolean = false,
    val hidden: Boolean = false,

    // ---- artifact identity (catalog schema v2) ----
    /**
     * Artifact version string of the model (e.g. "1.0.0", or the upstream
     * checkpoint revision). Required for every entry.
     */
    val version: String = "",
    /**
     * The container file format — "LITERTLM" or "TFLITE". Must equal
     * [runtimeFormat] and match the [fileName] extension.
     */
    val fileFormat: String = "",
    /** The artifact MIME type ("application/x-litertlm" / "application/x-tflite"). */
    val mimeType: String = "",
    /**
     * The expected `LlmModelType` container identifier (e.g. "qwen3",
     * "fast_vlm", "lfm2") for `.litertlm` containers. Must be a registered
     * identifier. Empty for `.tflite` artifacts — they carry no LlmMetadata
     * proto. The identifier embedded in the actual container remains
     * authoritative at load time; this is the catalog's declared expectation,
     * validated against the registry at indexing.
     */
    val containerType: String? = null,

    // ---- storage-streaming runtime fields (catalog schema v2) ----
    // These separate STORAGE requirement from RUNTIME RAM requirement — the
    // core point of the streaming architecture: a 5.2 GB file does not need
    // 5.2 GB of RAM.
    val streamable: Boolean = true,
    val runtimeFormat: String = "GGUF",
    val supportedBackends: List<String> = listOf("CPU", "VULKAN"),
    /** Weight-block cache budget in MB (the streaming working set). */
    val defaultCacheMb: Long = 1024,
    /** Estimated resident RAM in MB; 0 = auto-computed at install from metadata. */
    val estimatedRuntimeRamMb: Long = 0,
    val recommendedContext: Int = 4096,
    val tensorLayout: String = "BLOCKED",
    /** Installed-model lifecycle state (mirrors ModelRuntimeState). */
    val runtimeState: String = "AVAILABLE",

    // ---- Colibrì-port schema additions ----
    /** DENSE or MOE (routed experts). */
    val denseOrMoe: String = "DENSE",
    /** Streaming model type: DENSE / MOE / STREAMING_DENSE / STREAMING_MOE. */
    val modelStreamType: String = "STREAMING_DENSE",
    /** Recommended GPU-visible memory in MB for the Vulkan backend (0 = auto). */
    val recommendedVramMb: Long = 0,
    /** Storage-speed advice: "FAST_INTERNAL", "STANDARD", "ANY". */
    val storageSpeed: String = "STANDARD",
    /** AndroLLM runtime version that can drive this artifact. */
    val runtimeVersion: String = "0.1.0",
    /** Source of the artifact (repo / upstream id) for attribution. */
    val modelSource: String? = null,
    /** Shared-expert count for MoE models (0 = none). */
    val sharedExperts: Int = 0,
    /** Routed expert count for MoE models (0 = dense). */
    val expertCount: Int = 0,

    // ---- backend compatibility flags ----
    // Declare which inference backends this artifact supports. The engine's
    // automatic selection (NPU → GPU → CPU) skips a backend the model does
    // not support and falls back silently. CPU/GPU default true; NPU defaults
    // false because LiteRT-LM NPU execution requires SoC-specific model
    // builds (e.g. the Gemma3-1B NPU editions) — an ordinary container fails
    // NPU initialization, so entries opt in only when an NPU build ships.
    val supportsCpu: Boolean = true,
    val supportsGpu: Boolean = true,
    val supportsNpu: Boolean = false,

    // ---- catalog sections & model facts (additive, LiteRT catalog) ----
    // Sections organize the catalog into the filter chips shown on the Models
    // screen: Featured / Google / Gemma / Qwen / DeepSeek / Phi / Tiny /
    // Gemma Variants / Vision / Speech / Embedding. A model may belong to
    // several sections (e.g. Gemma 4 E4B -> Featured, Google, Gemma).
    val sections: List<String> = emptyList(),
    /** Last time the model artifact was updated upstream (epoch millis). */
    val lastUpdated: Long = 0,
    /** Human-readable device recommendations, e.g. "8GB RAM phones". */
    val recommendedDevices: List<String> = emptyList(),
    /** Android NNAPI delegate support (accelerates via NPU/DSP/GPU). */
    val supportsNnapi: Boolean = false,
    /** LiteRT's GPU delegate on Android runs on Vulkan, so this mirrors GPU. */
    val supportsVulkan: Boolean = false,
    /** Accepts image input (vision/multimodal models). */
    val supportsImageInput: Boolean = false,
    /** Accepts audio input (speech/ASR models). */
    val supportsAudioInput: Boolean = false,
    /** Native function/tool calling support in the chat template. */
    val supportsToolCalling: Boolean = false,
    /** Reasoning traces / chain-of-thought behavior. */
    val supportsReasoning: Boolean = false,
    /** Produces embedding vectors (memory search / RAG). */
    val supportsEmbeddings: Boolean = false
) {
    /** Quantization tier, auto-classified from [quantization]. */
    val quantLevel: QuantLevel get() = QuantClassifier.classify(quantization)

    /** Numeric parameter count in billions (e.g. "1.5B" -> 1.5), null when unparsable. */
    val parameterCountB: Double? get() = ParameterCount.parse(parameters)

    val categoryValues: List<CatalogCategory>
        get() = categories.mapNotNull { CatalogCategory.fromValue(it) }

    val modalityValue: Modality
        get() = Modality.fromValue(modality)

    val statusValue: CatalogStatus
        get() = CatalogStatus.fromValue(status)

    /** Parsed runtime lifecycle state. */
    val runtimeStateValue: ModelRuntimeState
        get() = ModelRuntimeState.fromValue(runtimeState)

    /** Parsed backends — never more than {CPU, VULKAN}. */
    val backendValues: List<RuntimeBackend>
        get() = supportedBackends.mapNotNull { RuntimeBackend.fromValue(it) }

    /** True when the runtime can actually run this model on this device class. */
    val isStreamable: Boolean get() = streamable && backendValues.isNotEmpty()

    /** Parsed dense/MoE classification. */
    val denseOrMoeValue: DenseOrMoe
        get() = DenseOrMoe.fromValue(denseOrMoe)

    /** Parsed streaming model type (DENSE/MOE/STREAMING_DENSE/STREAMING_MOE). */
    val modelStreamTypeValue: ModelStreamType
        get() = ModelStreamType.fromValue(modelStreamType)

    /**
     * Estimated resident RAM in MB for this model under streaming: explicit
     * catalog value when set, otherwise a per-quantization share of the file
     * size (the working set, not the whole file).
     */
    val estimatedRuntimeRamMbValue: Long
        get() = if (estimatedRuntimeRamMb > 0) {
            estimatedRuntimeRamMb
        } else {
            RuntimeRamEstimator.estimateMb(this)
        }
}

/**
 * Streaming RAM estimation: the resident working set (cache + KV + workspace),
 * never the file size. Uses a per-quantization fraction of the model bytes:
 * fewer bits per weight → less RAM per GB of storage.
 */
object RuntimeRamEstimator {
    private const val MIN_MB = 512L
    private const val MAX_MB = 16L * 1024

    fun estimateMb(model: CatalogModel): Long {
        val bytes = model.sizeBytes.coerceAtLeast(0)
        if (bytes == 0L) return MIN_MB
        val fraction = when (model.quantLevel) {
            QuantLevel.TQ1, QuantLevel.TQ2 -> 0.18
            QuantLevel.IQ1, QuantLevel.Q1 -> 0.20
            QuantLevel.IQ2, QuantLevel.Q2 -> 0.22
            QuantLevel.IQ3, QuantLevel.Q3 -> 0.25
            QuantLevel.IQ4, QuantLevel.Q4 -> 0.28
            QuantLevel.Q5 -> 0.30
            QuantLevel.Q6 -> 0.32
            QuantLevel.Q8 -> 0.35
            QuantLevel.MXFP4, QuantLevel.NVFP4 -> 0.26
            QuantLevel.F16, QuantLevel.BF16 -> 0.22
            QuantLevel.OTHER -> 0.30
        }
        val mb = (bytes * fraction / 1_000_000.0).toLong()
        return mb.coerceIn(MIN_MB, MAX_MB)
    }
}

/** Dense vs MoE classification of a model. */
enum class DenseOrMoe(val label: String) {
    DENSE("Dense"),
    MOE("MoE");

    val isMoe: Boolean get() = this == MOE

    companion object {
        fun fromValue(value: String): DenseOrMoe =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) || it.label.equals(value, ignoreCase = true) }
                ?: DENSE
    }
}

/**
 * Streaming model type. STREAMING_* entries are the AndroLLM runtime's core
 * offering: weights live primarily on storage and stream through a bounded
 * RAM/VRAM cache. MOE types route experts on demand (Colibrì's strongest
 * concept) — a large MoE can run with a small resident set.
 */
enum class ModelStreamType(val label: String) {
    DENSE("Dense"),
    MOE("MoE"),
    STREAMING_DENSE("Streaming Dense"),
    STREAMING_MOE("Streaming MoE");

    val isStreaming: Boolean get() = this == STREAMING_DENSE || this == STREAMING_MOE
    val isMoe: Boolean get() = this == MOE || this == STREAMING_MOE

    companion object {
        fun fromValue(value: String): ModelStreamType =
            entries.firstOrNull {
                it.name.equals(value, ignoreCase = true) || it.label.equals(value, ignoreCase = true)
            } ?: STREAMING_DENSE
    }
}

/**
 * Canonical catalog section names. A model's [CatalogModel.sections] may
 * contain several of these; the Models screen renders one filter chip per
 * section (plus All / Installed / Downloaded).
 */
object CatalogSections {
    const val FEATURED = "Featured"
    const val GOOGLE = "Google"
    const val GEMMA = "Gemma"
    const val QWEN = "Qwen"
    const val DEEPSEEK = "DeepSeek"
    const val PHI = "Phi"
    const val TINY = "Tiny"
    const val GEMMA_VARIANTS = "Gemma Variants"
    const val VISION = "Vision"
    const val EMBEDDING = "Embedding"
    const val SPEECH = "Speech"

    /** All section chips in display order. */
    val ALL: List<String> = listOf(
        FEATURED, GOOGLE, GEMMA, QWEN, DEEPSEEK, PHI, TINY,
        GEMMA_VARIANTS, VISION, EMBEDDING, SPEECH
    )
}

/** Display categories used to organize catalog models. */
enum class CatalogCategory(val label: String) {
    RECOMMENDED("Recommended"),
    CHAT("General Chat"),
    REASONING("Reasoning"),
    CODE("Code"),
    MATH("Math"),
    FUNCTION_CALLING("Function Calling"),
    TOOL_USE("Tool Use"),
    AGENTIC("Agentic"),
    MULTILINGUAL("Multilingual"),
    EMBEDDING("Embedding"),
    VISION("Vision"),
    AUDIO("Audio"),
    CREATIVE_WRITING("Creative Writing"),
    SUMMARIZATION("Summarization"),
    RAG("RAG"),
    INSTRUCT("Instruction Tuned"),
    BASE("Base / Pretrained"),
    FINANCE("Finance"),
    LEGAL("Legal"),
    MEDICAL("Medical"),
    LIGHTWEIGHT("Lightweight");

    companion object {
        fun fromValue(value: String): CatalogCategory? =
            entries.firstOrNull { it.name == value || it.label == value }
    }
}

/** Quantization tier. IQ5/IQ6 do not exist in llama.cpp - such strings classify as [OTHER]. */
enum class QuantLevel(val label: String, val rank: Int) {
    TQ1("TQ1 (ternary)", 1),
    TQ2("TQ2 (ternary)", 2),
    Q1("Q1", 3),
    IQ1("IQ1", 4),
    Q2("Q2", 5),
    IQ2("IQ2", 6),
    Q3("Q3", 7),
    IQ3("IQ3", 8),
    Q4("Q4", 9),
    IQ4("IQ4", 10),
    Q5("Q5", 11),
    Q6("Q6", 12),
    Q8("Q8", 13),
    MXFP4("MXFP4", 14),
    NVFP4("NVFP4", 15),
    F16("F16", 16),
    BF16("BF16", 17),
    OTHER("Other", 18)
}

enum class Modality {
    TEXT,
    VISION,
    AUDIO,
    MULTIMODAL,
    EMBEDDING;

    companion object {
        fun fromValue(value: String): Modality =
            entries.firstOrNull { it.name == value } ?: TEXT
    }
}

enum class CatalogStatus {
    STABLE,
    BETA,
    EXPERIMENTAL,
    ARCHIVED;

    companion object {
        fun fromValue(value: String): CatalogStatus =
            entries.firstOrNull { it.name == value } ?: STABLE
    }
}

/**
 * Maps GGUF quantization strings (as they appear in file names and llama.h)
 * to coarse [QuantLevel] tiers for filtering and recommendation.
 */
object QuantClassifier {
    private val KNOWN = mapOf(
        "Q1_0" to QuantLevel.Q1,
        "Q2_0" to QuantLevel.Q2,
        "Q2_K" to QuantLevel.Q2,
        "Q2_K_S" to QuantLevel.Q2,
        "Q3_K" to QuantLevel.Q3,
        "Q3_K_S" to QuantLevel.Q3,
        "Q3_K_M" to QuantLevel.Q3,
        "Q3_K_L" to QuantLevel.Q3,
        "Q4_0" to QuantLevel.Q4,
        "Q4_1" to QuantLevel.Q4,
        "Q4_K" to QuantLevel.Q4,
        "Q4_K_S" to QuantLevel.Q4,
        "Q4_K_M" to QuantLevel.Q4,
        "Q5_0" to QuantLevel.Q5,
        "Q5_1" to QuantLevel.Q5,
        "Q5_K" to QuantLevel.Q5,
        "Q5_K_S" to QuantLevel.Q5,
        "Q5_K_M" to QuantLevel.Q5,
        "Q6_K" to QuantLevel.Q6,
        "Q8_0" to QuantLevel.Q8,
        "IQ1_S" to QuantLevel.IQ1,
        "IQ1_M" to QuantLevel.IQ1,
        "IQ2_XXS" to QuantLevel.IQ2,
        "IQ2_XS" to QuantLevel.IQ2,
        "IQ2_S" to QuantLevel.IQ2,
        "IQ2_M" to QuantLevel.IQ2,
        "IQ3_XXS" to QuantLevel.IQ3,
        "IQ3_XS" to QuantLevel.IQ3,
        "IQ3_S" to QuantLevel.IQ3,
        "IQ3_M" to QuantLevel.IQ3,
        "IQ4_XS" to QuantLevel.IQ4,
        "IQ4_NL" to QuantLevel.IQ4,
        "TQ1_0" to QuantLevel.TQ1,
        "TQ2_0" to QuantLevel.TQ2,
        "MXFP4" to QuantLevel.MXFP4,
        "NVFP4" to QuantLevel.NVFP4,
        "F16" to QuantLevel.F16,
        "FP16" to QuantLevel.F16,
        "BF16" to QuantLevel.BF16
    )

    private fun normalize(value: String): String =
        value.trim().uppercase().replace('-', '_').replace(' ', '_').replace("__", "_")

    fun classify(quantization: String): QuantLevel =
        KNOWN[normalize(quantization)] ?: QuantLevel.OTHER

    fun isKnown(quantization: String): Boolean =
        KNOWN.containsKey(normalize(quantization))

    fun rankOf(quantization: String): Int = classify(quantization).rank
}

/** Parses parameter strings like "1.5B", "8B", "407M", "0.5B" into billions. */
object ParameterCount {
    private val PATTERN = Regex("""(\d+(?:\.\d+)?)\s*([BM])""", RegexOption.IGNORE_CASE)

    fun parse(parameters: String): Double? {
        val match = PATTERN.find(parameters.trim()) ?: return null
        val value = match.groupValues[1].toDoubleOrNull() ?: return null
        val multiplier = if (match.groupValues[2].uppercase() == "M") 0.001 else 1.0
        return value * multiplier
    }
}
