package io.androllm.engine.compat

/**
 * The registry that binds each [ModelFamily] to its official configuration.
 * This is the single extension point for supporting a new model family: add
 * the enum value in [ModelFamily] and one entry here — the engine, the
 * decoder and the renderer need no changes.
 */
object ModelFamilyRegistry {

    private val qwenDefaults = GenerationDefaults(topK = 20, topP = 0.8f, temperature = 0.7f)
    private val gemmaDefaults = GenerationDefaults(topK = 40, topP = 0.95f, temperature = 0.8f)
    private val llamaDefaults = GenerationDefaults(topK = null, topP = 0.9f, temperature = 0.6f)
    private val phiDefaults = GenerationDefaults(topK = null, topP = 1.0f, temperature = 0.7f)
    private val mistralDefaults = GenerationDefaults(topK = null, topP = 0.95f, temperature = 0.7f)
    private val deepseekDefaults = GenerationDefaults(topK = null, topP = 0.95f, temperature = 0.7f)
    private val smolDefaults = GenerationDefaults(topK = null, topP = 0.95f, temperature = 0.7f)
    private val tinyLlamaDefaults = GenerationDefaults(topK = null, topP = 0.95f, temperature = 0.7f)

    private val allConfigs: Map<ModelFamily, ModelFamilyConfig> = mapOf(
        ModelFamily.GEMMA to ModelFamilyConfig(
            ModelFamily.GEMMA, ChatTemplates.gemmaLenient, SpecialTokensCatalog.gemma, gemmaDefaults
        ),
        ModelFamily.QWEN2 to ModelFamilyConfig(
            ModelFamily.QWEN2, ChatTemplates.qwen, SpecialTokensCatalog.qwen, qwenDefaults
        ),
        ModelFamily.QWEN2P5 to ModelFamilyConfig(
            ModelFamily.QWEN2P5, ChatTemplates.qwen, SpecialTokensCatalog.qwen, qwenDefaults
        ),
        ModelFamily.QWEN3 to ModelFamilyConfig(
            ModelFamily.QWEN3,
            ChatTemplates.qwen3,
            SpecialTokensCatalog.qwen,
            qwenDefaults,
            thinkingChannel = ChannelSpec("thinking", "<think>", "</think>")
        ),
        ModelFamily.PHI to ModelFamilyConfig(
            ModelFamily.PHI, ChatTemplates.phi, SpecialTokensCatalog.phi, phiDefaults
        ),
        ModelFamily.LLAMA3 to ModelFamilyConfig(
            ModelFamily.LLAMA3, ChatTemplates.llama3, SpecialTokensCatalog.llama3, llamaDefaults
        ),
        ModelFamily.DEEPSEEK to ModelFamilyConfig(
            ModelFamily.DEEPSEEK, ChatTemplates.deepseek, SpecialTokensCatalog.deepseek, deepseekDefaults
        ),
        ModelFamily.MISTRAL to ModelFamilyConfig(
            ModelFamily.MISTRAL, ChatTemplates.mistral, SpecialTokensCatalog.mistral, mistralDefaults
        ),
        ModelFamily.SMOL to ModelFamilyConfig(
            ModelFamily.SMOL, ChatTemplates.smol, SpecialTokensCatalog.smol, smolDefaults
        ),
        ModelFamily.TINYLLAMA to ModelFamilyConfig(
            ModelFamily.TINYLLAMA, ChatTemplates.tinyLlama, SpecialTokensCatalog.tinyLlama, tinyLlamaDefaults
        )
    )

    /** All known families in registry order. */
    val all: List<ModelFamilyConfig>
        get() = ModelFamily.entries.map { allConfigs.getValue(it) }

    /** Lookup by family — throws for families that are not registered. */
    fun configFor(family: ModelFamily): ModelFamilyConfig = allConfigs.getValue(family)

    /**
     * Resolves the family for a model, using container metadata first and the
     * model name as a last resort. See [ModelCompatibilityResolver] for the
     * detection order and confidence scoring. Returns the full [Resolution]
     * (family + source) so callers can log/display HOW the family was detected.
     */
    fun resolve(
        container: ContainerMetadata?,
        modelName: String?
    ): ModelCompatibilityResolver.Resolution = ModelCompatibilityResolver.resolve(container, modelName)

    /** Convenience: same as [resolve] but returns only the config. */
    fun resolveConfig(
        container: ContainerMetadata?,
        modelName: String?
    ): ModelFamilyConfig = resolve(container, modelName).config
}