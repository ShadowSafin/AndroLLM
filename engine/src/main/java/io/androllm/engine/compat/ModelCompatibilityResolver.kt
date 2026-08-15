package io.androllm.engine.compat

/**
 * Resolves the [ModelFamily] of a model from the strongest available evidence.
 *
 * Detection order (first match wins, strictly):
 *  1. `llm_model_type` embedded in the `.litertlm` container (authoritative).
 *  2. The chat template embedded in the container — matched against the
 *     official templates byte-for-byte (strong signal, also detects models
 *     whose container has generic model type).
 *  3. The container's declared stop tokens (family-unique marker strings).
 *  4. The model's own name / path (weakest; never used when any metadata
 *     exists).
 *
 * Unknown families fail loudly with an actionable [ModelCompatibilityException]
 * instead of guessing.
 */
object ModelCompatibilityResolver {

    /** How confident we are, in descending order. */
    enum class DetectionSource(val confidence: Int) {
        CONTAINER_MODEL_TYPE(100),
        EMBEDDED_TEMPLATE(80),
        CONTAINER_STOP_TOKENS(60),
        TOKENIZER_ADDED_TOKENS(50),
        NAME_FALLBACK(20)
    }

    data class Resolution(
        val family: ModelFamily,
        val source: DetectionSource,
        val reason: String
    ) {
        val config: ModelFamilyConfig get() = ModelFamilyRegistry.configFor(family)
    }

    fun resolve(container: ContainerMetadata?, modelName: String?): Resolution {
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

        // 3. Moderate: the container's stop tokens are family-unique markers.
        val stopTokens = container?.stopTokens.orEmpty()
        if (stopTokens.isNotEmpty()) {
            val family = matchStopTokens(stopTokens)
            if (family != null) {
                return Resolution(family, DetectionSource.CONTAINER_STOP_TOKENS, "stop tokens ${stopTokens.joinToString()}")
            }
        }

        // 4. Weakest: the file name. Only used when there is no metadata at all.
        val nameFamily = ModelFamily.fromName(normalizedName)
        if (nameFamily != null && container == null) {
            return Resolution(nameFamily, DetectionSource.NAME_FALLBACK, "model name '$normalizedName'")
        }

        throw ModelCompatibilityException(
            if (container == null) {
                "Cannot determine the model family of '$modelName' (no container metadata and no known name). " +
                    "The supported families are: " + ModelFamily.entries.joinToString { it.displayName } + "."
            } else {
                "The model's container metadata ($typeName) does not match any supported family. " +
                    "Supported families: " + ModelFamily.entries.joinToString { it.displayName } + "."
            }
        )
    }

    private fun matchTemplate(template: String): ModelFamily? {
        // Most specific first: Qwen3's template is a superset of Qwen2.5's.
        if (template.contains("<think>")) return ModelFamily.QWEN3
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