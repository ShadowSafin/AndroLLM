package io.androllm.core.models.catalog

/**
 * The model-metadata registry: the single source of truth that maps every
 * container identifier, catalog family and architecture to its canonical
 * metadata (family, display name, architectures, container formats, modality
 * and the engine family that drives templates/tokens at runtime).
 *
 * Two levels are registered:
 *  - [containerTypes]: the `LlmModelType` oneof member names LiteRT-LM writes
 *    into the `.litertlm` container metadata (`llm_model_type`, field 6 of
 *    `LlmMetadata`). These are the authoritative identifiers — the converter
 *    writes exactly one of them per container.
 *  - [families]: the catalog family names (display names) used in the catalog
 *    JSON, with the set of architectures that legitimately belong to each
 *    family. A catalog entry is valid only when its family/architecture pair
 *    appears here.
 *
 * Every container identifier is registered — none is ever "rejected". An
 * identifier whose [ContainerTypeSpec.engineFamilyKey] is null is either the
 * generic fallback (`generic_model`, no family signal) or a family without a
 * bespoke engine configuration; the engine continues detection and finally
 * falls back to its generic container-template mode instead of failing.
 */
object ModelMetadataRegistry {

    /** The artifact container formats this runtime executes. */
    enum class ContainerFormat { LITERTLM, TFLITE }

    /** What a model actually does at runtime. */
    enum class RuntimeModality { CHAT, VISION, SPEECH, EMBEDDING }

    /**
     * One `LlmModelType` container identifier and its mapping into the
     * metadata graph. [engineFamilyKey] is the engine-side family key
     * (e.g. "QWEN3") or null when the container carries no family signal
     * ([identifier] == "generic_model") or has no bespoke engine family
     * (null → the engine uses the container's embedded template).
     */
    data class ContainerTypeSpec(
        val identifier: String,
        val familyKey: String,
        val engineFamilyKey: String?,
        val modality: RuntimeModality,
        val format: ContainerFormat
    )

    /**
     * One catalog family. [architectures] is the complete, closed set of
     * architectures that may appear under this family — the validator rejects
     * any catalog entry whose pair is not listed. [engineFamilyKey] is the
     * engine family for single-family mappings (null when the family spans
     * several engine families — e.g. Qwen covers QWEN2/QWEN2P5/QWEN3 — which
     * the container type disambiguates).
     */
    data class ModelFamilySpec(
        val familyKey: String,
        val displayName: String,
        val architectures: Set<String>,
        val containerFormats: Set<ContainerFormat>,
        val modality: RuntimeModality,
        val engineFamilyKey: String?
    )

    // ── the 10 authoritative LlmModelType identifiers (litertlm_tree.proto) ──

    private val containerTypeSpecs: List<ContainerTypeSpec> = listOf(
        // The generic fallback the converter writes when the architecture has
        // no dedicated model type. Registered as KNOWN so validation accepts
        // it — but it carries no family signal, so resolution continues to
        // template / stop tokens / name / generic mode.
        ContainerTypeSpec("generic_model", "GENERIC", null, RuntimeModality.CHAT, ContainerFormat.LITERTLM),
        ContainerTypeSpec("qwen3", "QWEN", "QWEN3", RuntimeModality.CHAT, ContainerFormat.LITERTLM),
        ContainerTypeSpec("qwen2p5", "QWEN", "QWEN2P5", RuntimeModality.CHAT, ContainerFormat.LITERTLM),
        ContainerTypeSpec("gemma3", "GEMMA", "GEMMA", RuntimeModality.CHAT, ContainerFormat.LITERTLM),
        ContainerTypeSpec("gemma3n", "GEMMA", "GEMMA", RuntimeModality.CHAT, ContainerFormat.LITERTLM),
        ContainerTypeSpec("gemma4", "GEMMA", "GEMMA", RuntimeModality.CHAT, ContainerFormat.LITERTLM),
        ContainerTypeSpec("function_gemma", "FUNCTIONGEMMA", "GEMMA", RuntimeModality.CHAT, ContainerFormat.LITERTLM),
        // Registered but without a bespoke engine family: the container's own
        // embedded template drives chat (GENERIC engine mode).
        ContainerTypeSpec("fast_vlm", "FASTVLM", "GENERIC", RuntimeModality.VISION, ContainerFormat.LITERTLM),
        ContainerTypeSpec("lfm2", "LFM", "LLAMA3", RuntimeModality.CHAT, ContainerFormat.LITERTLM),
        ContainerTypeSpec("minicpm5", "MINICPM", "GENERIC", RuntimeModality.CHAT, ContainerFormat.LITERTLM)
    )

    // ── the catalog families (display names) ────────────────────────────────

    private val familySpecs: List<ModelFamilySpec> = listOf(
        ModelFamilySpec("GEMMA", "Gemma", setOf("gemma3", "gemma4", "gemma-1.5", "gemma-embedding", "codegemma"),
            setOf(ContainerFormat.LITERTLM, ContainerFormat.TFLITE), RuntimeModality.CHAT, "GEMMA"),
        ModelFamilySpec("FUNCTIONGEMMA", "FunctionGemma", setOf("functiongemma"),
            setOf(ContainerFormat.LITERTLM), RuntimeModality.CHAT, "GEMMA"),
        ModelFamilySpec("QWEN", "Qwen", setOf("qwen2", "qwen2.5-coder", "qwen3", "qwen3-asr"),
            setOf(ContainerFormat.LITERTLM, ContainerFormat.TFLITE), RuntimeModality.CHAT, null),
        ModelFamilySpec("DEEPSEEK", "DeepSeek", setOf("deepseek", "qwen2"),
            setOf(ContainerFormat.LITERTLM), RuntimeModality.CHAT, "DEEPSEEK"),
        ModelFamilySpec("PHI", "Phi", setOf("phi4"),
            setOf(ContainerFormat.LITERTLM), RuntimeModality.CHAT, "PHI"),
        ModelFamilySpec("TINYLLAMA", "TinyLlama", setOf("llama"),
            setOf(ContainerFormat.LITERTLM), RuntimeModality.CHAT, "TINYLLAMA"),
        ModelFamilySpec("SMOLLM", "SmolLM", setOf("smollm2", "smollm3", "llama"),
            setOf(ContainerFormat.LITERTLM), RuntimeModality.CHAT, "SMOL"),
        ModelFamilySpec("LLAMA", "Llama", setOf("llama", "lfm2"),
            setOf(ContainerFormat.LITERTLM), RuntimeModality.CHAT, "LLAMA3"),
        ModelFamilySpec("FASTVLM", "FastVLM", setOf("fastvlm"),
            setOf(ContainerFormat.LITERTLM), RuntimeModality.VISION, null),
        ModelFamilySpec("MAGEVL", "Mage-VL", setOf("mage-vl"),
            setOf(ContainerFormat.LITERTLM), RuntimeModality.VISION, null),
        ModelFamilySpec("SMOLVLM", "SmolVLM", setOf("smolvlm2"),
            setOf(ContainerFormat.LITERTLM), RuntimeModality.VISION, null),
        ModelFamilySpec("MINICPM", "MiniCPM", setOf("minicpm"),
            setOf(ContainerFormat.LITERTLM), RuntimeModality.CHAT, null),
        ModelFamilySpec("WHISPER", "Whisper", setOf("whisper"),
            setOf(ContainerFormat.TFLITE), RuntimeModality.SPEECH, null),
        ModelFamilySpec("MOONSHINE", "Moonshine", setOf("moonshine"),
            setOf(ContainerFormat.TFLITE), RuntimeModality.SPEECH, null),
        ModelFamilySpec("PARAKEET", "Parakeet", setOf("parakeet"),
            setOf(ContainerFormat.TFLITE), RuntimeModality.SPEECH, null),
        ModelFamilySpec("TINYSWALLOW", "TinySwallow", setOf("qwen2"),
            setOf(ContainerFormat.LITERTLM), RuntimeModality.CHAT, "QWEN2P5"),
        ModelFamilySpec("VIBETHINKER", "VibeThinker", setOf("qwen2"),
            setOf(ContainerFormat.LITERTLM), RuntimeModality.CHAT, "QWEN2P5"),
        ModelFamilySpec("MISTRAL", "Mistral", setOf("mistral"),
            setOf(ContainerFormat.LITERTLM), RuntimeModality.CHAT, "MISTRAL")
    )

    private val containerTypesByKey: Map<String, ContainerTypeSpec> =
        containerTypeSpecs.associateBy { it.identifier }

    private val familiesByKey: Map<String, ModelFamilySpec> =
        familySpecs.associateBy { it.familyKey }

    private val familiesByName: Map<String, ModelFamilySpec> =
        familySpecs.associateBy { it.displayName.lowercase() }

    private val familyByArchitecture: Map<String, ModelFamilySpec> =
        familySpecs.flatMap { spec -> spec.architectures.map { it to spec } }.toMap()

    /** Lookup a container identifier (e.g. "qwen3", "fast_vlm"). */
    fun containerTypeFor(identifier: String?): ContainerTypeSpec? =
        identifier?.let { containerTypesByKey[it] }

    /** Whether [identifier] is a known `LlmModelType` (never rejects). */
    fun isKnownContainerType(identifier: String?): Boolean =
        identifier != null && containerTypesByKey.containsKey(identifier)

    /**
     * Lookup a family by its canonical key ("QWEN") or display name
     * ("Qwen") — case-insensitive on the display name.
     */
    fun familyFor(keyOrDisplayName: String?): ModelFamilySpec? {
        if (keyOrDisplayName.isNullOrBlank()) return null
        return familiesByKey[keyOrDisplayName]
            ?: familiesByName[keyOrDisplayName.lowercase()]
            ?: familiesByKey.entries.firstOrNull { it.key.equals(keyOrDisplayName, ignoreCase = true) }?.value
    }

    /** The family that declares [architecture]; null when unknown. */
    fun familyForArchitecture(architecture: String?): ModelFamilySpec? =
        architecture?.let { familyByArchitecture[it] }

    /** Every architecture known to the registry (union across families). */
    val allArchitectures: Set<String> get() = familyByArchitecture.keys

    /** Every registered container identifier. */
    val allContainerTypes: Set<String> get() = containerTypesByKey.keys

    /** Every registered family, in registry order. */
    val allFamilies: List<ModelFamilySpec> get() = familySpecs

    /**
     * The engine family key for a container identifier, or null when the
     * identifier carries no engine-family signal (`generic_model`).
     */
    fun engineFamilyKeyForContainer(identifier: String?): String? =
        containerTypeFor(identifier)?.engineFamilyKey

    /** MIME type of a container format ("application/x-litertlm"). */
    fun mimeTypeFor(format: String?): String? = when (format?.uppercase()) {
        "LITERTLM" -> MIME_LITERTLM
        "TFLITE" -> MIME_TFLITE
        else -> null
    }

    const val MIME_LITERTLM = "application/x-litertlm"
    const val MIME_TFLITE = "application/x-tflite"
}