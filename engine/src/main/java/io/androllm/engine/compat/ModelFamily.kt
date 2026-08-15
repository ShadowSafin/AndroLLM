package io.androllm.engine.compat

/**
 * The model families supported by the LiteRT-LM compatibility layer.
 *
 * A family is a group of models that share the same tokenizer family, the same
 * official chat template, and the same special-token / stop-token conventions.
 * Every supported model must resolve to exactly one [ModelFamily]; the
 * resolution is driven by container metadata first (the LiteRT `LlmMetadata`
 * proto embedded in the `.litertlm` file) and falls back to template/stop-token
 * signatures and finally to the model name ONLY when no metadata exists.
 *
 * Adding support for a new family means adding one enum value here (plus one
 * entry in [ModelFamilyRegistry]) — never touching the engine.
 */

/**
 * Character cap for the tool advertisement on families whose smallest repacks
 * degrade with a long tool list (see [ModelFamily.toolAdvertisementCapChars]).
 * 4500 chars ≈ 1100 tokens — comfortably below the measured ~5.7K-char
 * breakpoint for a 1.5B Qwen while still advertising ~23 tools. Top-level (not
 * in the companion) because enum entries are constructed before the companion
 * object exists.
 */
private const val TOOL_AD_CAP_SMALL_MODEL = 4500

enum class ModelFamily(
    /** Canonical family name shown in the UI / diagnostics. */
    val displayName: String,
    /** Name-based aliases used only when metadata is unavailable. */
    val aliases: List<String>,
    /** The tokenizer kind the family ships with. */
    val tokenizerKind: TokenizerKind,
    /**
     * True when the family's repacks can emit native `<|tool_call|>` markers
     * during decoding (Qwen's official function calling, and the function-
     * calling Gemma repacks LiteRT-LM ships as `function_gemma`). The chat
     * layer skips the slow JSON-compat planner for these families — an
     * answer without markers is authoritative and gets committed immediately.
     */
    val nativeToolMarkers: Boolean = false,
    /**
     * Upper bound (chars) on the tool-advertisement system message this
     * family's smallest repacks can absorb before degrading to empty/garbage
     * output. Measured on-device: Qwen2.5-1.5B degrades between ~5.7K and
     * ~9.4K chars of tool-list system prompt, and Qwen3-0.6B overflows its
     * 2048-token real context with the full ~2.3K-token list. Gemma 4B
     * handles the full list. `Int.MAX_VALUE` = no family cap (context budget
     * still applies).
     */
    val toolAdvertisementCapChars: Int = Int.MAX_VALUE
) {
    GEMMA("Gemma", listOf("gemma"), TokenizerKind.SENTENCEPIECE, nativeToolMarkers = true),
    QWEN2(
        "Qwen2",
        listOf("qwen2", "qwen1.5", "qwen1.6", "qwen-vl"),
        TokenizerKind.BPE,
        nativeToolMarkers = true,
        toolAdvertisementCapChars = TOOL_AD_CAP_SMALL_MODEL
    ),
    QWEN2P5(
        "Qwen2.5",
        listOf("qwen2.5", "qwen2_5"),
        TokenizerKind.BPE,
        nativeToolMarkers = true,
        toolAdvertisementCapChars = TOOL_AD_CAP_SMALL_MODEL
    ),
    QWEN3(
        "Qwen3",
        listOf("qwen3", "qwen3-vl"),
        TokenizerKind.BPE,
        nativeToolMarkers = true,
        toolAdvertisementCapChars = TOOL_AD_CAP_SMALL_MODEL
    ),
    PHI("Phi", listOf("phi", "phi-2", "phi-3", "phi-4", "phimoe"), TokenizerKind.BPE),
    LLAMA3("Llama 3", listOf("llama-3", "llama3", "llama-3.1", "llama-3.2", "llama-3.3"), TokenizerKind.BPE),
    DEEPSEEK("DeepSeek", listOf("deepseek"), TokenizerKind.BPE),
    MISTRAL("Mistral", listOf("mistral"), TokenizerKind.BPE),
    SMOL("SmolLM", listOf("smollm", "smol"), TokenizerKind.BPE),
    TINYLLAMA("TinyLlama", listOf("tinyllama", "tinylama"), TokenizerKind.BPE);

    companion object {
        /**
         * The `LlmModelType` oneof member names LiteRT-LM writes into the
         * container metadata (`llm_model_type`, field 6 of `LlmMetadata`).
         * Unknown members must NOT be mapped here — they fall through to the
         * template/stop-token signature detection.
         */
        fun fromLlmModelType(modelTypeName: String?): ModelFamily? = when (modelTypeName) {
            "qwen3" -> QWEN3
            "qwen2p5", "qwen2_5", "qwen2.5" -> QWEN2P5
            "gemma3", "gemma3n", "gemma4", "function_gemma" -> GEMMA
            else -> null
        }

        /** Case-insensitive alias lookup — LAST resort, never used when metadata exists. */
        fun fromName(name: String?): ModelFamily? {
            val n = name?.lowercase() ?: return null
            return entries.firstOrNull { family ->
                family.aliases.any { n.contains(it) }
            }
        }
    }
}

/** Tokenizer technology a family uses. */
enum class TokenizerKind {
    /** Byte-level BPE tokenizer (`tokenizer.json` with vocab+merges). */
    BPE,
    /** SentencePiece tokenizer (`tokenizer.model`). */
    SENTENCEPIECE
}