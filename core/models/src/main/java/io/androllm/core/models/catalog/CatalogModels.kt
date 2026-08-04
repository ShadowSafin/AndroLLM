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
 * A curated catalog entry for a single GGUF artifact on a remote repository.
 * All 37 fields are metadata - nothing here is code-driven.
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
    val publishedAt: Long = 0,
    val isGated: Boolean = false,
    val modality: String = "TEXT",
    val modelType: String? = null,
    val status: String = "STABLE",
    val badges: List<String> = emptyList(),
    val strengths: List<String> = emptyList(),
    val weaknesses: List<String> = emptyList(),
    val notes: String? = null,
    val recommended: Boolean = false,
    val hidden: Boolean = false
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
