package io.androllm.engine.compat

import io.androllm.core.models.catalog.ModelMetadataRegistry

/**
 * Resolves the [ModelFamily] of a model from the strongest available evidence.
 *
 * Detection order (first match wins, strictly):
 *  1. `llm_model_type` embedded in the `.litertlm` container, mapped through
 *     the shared [ModelMetadataRegistry] (authoritative). Every identifier the
 *     runtime supports is registered — none is ever rejected; identifiers
 *     without a bespoke engine family map to [ModelFamily.GENERIC] and
 *     `generic_model` falls through to the next evidence.
 *  2. The chat template embedded in the container — matched against the
 *     official templates byte-for-byte (strong signal, also detects models
 *     whose container has generic model type).
 *  3. The catalog family (curated, registry-validated at indexing) — used only
 *     for families with a single engine mapping (e.g. TinySwallow → QWEN2P5).
 *  4. The container's declared stop tokens (family-unique marker strings).
 *  5. The model's own name / path (weakest; never used when any metadata
 *     exists).
 *  6. GENERIC fallback — a container identifier supported by the runtime but
 *     missing from the registry (or a model with no identifying metadata)
 *     resolves to [ModelFamily.GENERIC], which uses the container's own
 *     embedded template and stop tokens. Resolution NEVER throws: an
 *     unresolvable model degrades to container-template mode instead of
 *     failing the load.
 */
object ModelCompatibilityResolver {

    /** How confident we are, in descending order. */
    enum class DetectionSource(val confidence: Int) {
        CONTAINER_MODEL_TYPE(100),
        CATALOG_FAMILY(90),
        EMBEDDED_TEMPLATE(80),
        CONTAINER_STOP_TOKENS(60),
        TOKENIZER_ADDED_TOKENS(50),
        NAME_FALLBACK(20),
        GENERIC_FALLBACK(10)
    }

    data class Resolution(
        val family: ModelFamily,
        val source: DetectionSource,
        val reason: String
    ) {
        val config: ModelFamilyConfig get() = ModelFamilyRegistry.configFor(family)
    }

    fun resolve(
        container: ContainerMetadata?,
        modelName: String?,
        catalogFamily: String? = null
    ): Resolution {
        val normalizedName = modelName?.substringAfterLast('/')?.substringAfterLast('\\')

        // 1. Authoritative: the model type the converter wrote into the container.
        val typeName = container?.modelTypeName
        if (!typeName.isNullOrBlank()) {
            val family = ModelFamily.fromLlmModelType(typeName)
            if (family != null) {
                return Resolution(family, DetectionSource.CONTAINER_MODEL_TYPE, "LlmModelType '$typeName'")
            }
        }

        // 2. Strong: the embedded chat template matches one of our official ones.
        val template = container?.jinjaPromptTemplate
        if (!template.isNullOrBlank()) {
            val family = matchTemplate(template)
            if (family != null) {
                return Resolution(family, DetectionSource.EMBEDDED_TEMPLATE, "embedded chat template")
            }
        }

        // 3. Moderate: the catalog family (registry-validated at indexing).
        //    Only families with a single engine mapping resolve here — families
        //    spanning several engine families (Qwen) are disambiguated by the
        //    container type, so their spec carries no engine key and is skipped.
        if (catalogFamily != null && container != null) {
            val spec = ModelMetadataRegistry.familyFor(catalogFamily)
            val engineKey = spec?.engineFamilyKey
            if (engineKey != null) {
                val family = ModelFamily.fromEngineKey(engineKey)
                if (family != null) {
                    return Resolution(family, DetectionSource.CATALOG_FAMILY, "catalog family '$catalogFamily'")
                }
            }
        }

        // 4. Moderate: the container's stop tokens are family-unique markers.
        val stopTokens = container?.stopTokens.orEmpty()
        if (stopTokens.isNotEmpty()) {
            val family = matchStopTokens(stopTokens)
            if (family != null) {
                return Resolution(family, DetectionSource.CONTAINER_STOP_TOKENS, "stop tokens ${stopTokens.joinToString()}")
            }
        }

        // 5. Weakest: the file name. Only used when there is no metadata at all.
        val nameFamily = ModelFamily.fromName(normalizedName)
        if (nameFamily != null && container == null) {
            return Resolution(nameFamily, DetectionSource.NAME_FALLBACK, "model name '$normalizedName'")
        }

        // 6. Generic fallback — NEVER fails the load. The engine uses the
        //    container's own embedded template and stop tokens in this mode.
        return Resolution(
            ModelFamily.GENERIC,
            DetectionSource.GENERIC_FALLBACK,
            if (container == null) {
                "no container metadata and no known name — generic container-template mode"
            } else {
                "container metadata ('${typeName ?: "none"}') does not map to a registered engine family — generic container-template mode"
            }
        )
    }

    private fun matchTemplate(template: String): ModelFamily? {
        // Most specific first: Qwen3's template is a superset of Qwen2.5's.
        if (template.contains(" thinking")) return ModelFamily.QWEN3
        val normalized = template.replace("\\s+".toRegex(), "").trim()
        if (normalized == normalize(ChatTemplates.qwen3)) return ModelFamily.QWEN3
        if (normalized == normalize(ChatTemplates.qwen)) return ModelFamily.QWEN2P5
        if (normalized == normalize(ChatTemplates.gemma)) return ModelFamily.GEMMA
        if (normalized == normalize(ChatTemplates.llama3)) return ModelFamily.LLAMA3
        if (normalized == normalize(ChatTemplates.phi)) return ModelFamily.PHI
        if (normalized == normalize(ChatTemplates.mistral)) return ModelFamily.MISTRAL
        if (normalized == normalize(ChatTemplates.tinyLlama)) return ModelFamily.TINYLLAMA
        if (template.contains("<|end?of?sentence|>")) return ModelFamily.DEEPSEEK
        if (normalized == normalize(ChatTemplates.smol)) return ModelFamily.SMOL
        return null
    }

    private fun normalize(s: String) = s.replace("\\s+".toRegex(), "").trim()

    private fun matchStopTokens(stopTokens: List<String>): ModelFamily? {
        val joined = stopTokens.joinToString(" ")
        if (joined.contains("<|im_end|>")) {
            // Qwen vs SmolLM share the marker; SmolLM also declares <|endoftext|>.
            return if (joined.contains("<|endoftext|>") && stopTokens.size == 2) ModelFamily.SMOL else ModelFamily.QWEN2P5
        }
        if (joined.contains("<end_of_turn>")) return ModelFamily.GEMMA
        if (joined.contains("<|eot_id|>")) return ModelFamily.LLAMA3
        if (joined.contains("<|end?of?sentence|>") || joined.contains("<｜tool▁calls▁end｜>")) return ModelFamily.DEEPSEEK
        if (joined.contains("<|assistant|>")) return ModelFamily.TINYLLAMA
        if (joined.contains("[INST]")) return ModelFamily.MISTRAL
        if (joined.contains("<|user|>")) return ModelFamily.PHI
        return null
    }
}