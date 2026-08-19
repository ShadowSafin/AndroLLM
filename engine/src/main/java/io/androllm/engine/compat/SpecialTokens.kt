package io.androllm.engine.compat

/**
 * The special tokens of one model family, matching the official tokenizer
 * configuration of that family (tokenizer_config.json / special_tokens_map.json
 * of the reference model). Used for decoding: every one of these strings is
 * stripped from generated text before it is shown to the user, and the stop
 * sequences are derived from the end-markers.
 *
 * A family must never reuse another family's tokens — the tokenizer, the
 * template and the special tokens belong to one family as a unit
 * ([ModelFamilyRegistry] enforces this by construction).
 */
data class SpecialTokens(
    val bos: String? = null,
    val eos: String? = null,
    val pad: String? = null,
    val unk: String? = null,
    val system: String? = null,
    val user: String? = null,
    val assistant: String? = null,
    val tool: String? = null,
    val endOfTurn: String? = null,
    val endOfMessage: String? = null,
    val extra: List<String> = emptyList()
) {
    /** Every token string that must never appear in decoded user-facing text. */
    val all: Set<String>
        get() = buildSet {
            listOf(bos, eos, pad, unk, system, user, assistant, tool, endOfTurn, endOfMessage)
                .filterNotNull()
                .forEach { add(it) }
            addAll(extra)
        }

    /**
     * All *end* markers — the strings on which generation must stop.
     *
     * Beyond the explicit eos/endOfTurn/endOfMessage markers, several
     * families use the same token as EOS and pad/unk (Qwen and Phi's
     * `<|endoftext|>`, Llama 3's `<|end_of_text|>`, DeepSeek's
     * `<|end?of?sentence|>`). Those markers terminate generation too — the
     * engine must stop on every model stop token, not only the primary EOS.
     */
    val stopSequences: List<String>
        get() = buildList {
            listOf(eos, endOfTurn, endOfMessage).filterNotNull().forEach { add(it) }
            END_OF_TEXT_MARKERS.filter { it in all }.forEach { add(it) }
        }.distinct()

    companion object {
        /** End-of-text markers that double as EOS for some families. */
        private val END_OF_TEXT_MARKERS = listOf(
            "<|endoftext|>",
            "<|end_of_text|>",
            "<|end?of?sentence|>"
        )
    }
}

/**
 * Official special tokens per family, transcribed from the reference
 * tokenizer_config.json / special_tokens_map.json of each family's canonical
 * model (Gemma 3, Qwen2.5/Qwen3, Llama 3, Phi-3/4, Mistral v0.3, DeepSeek V3,
 * SmolLM2, TinyLlama 1.1B). The strings are the exact tokens used by the
 * official chat templates — changing one here changes the rendered prompt and
 * the decode rules at the same time, which is why they live together.
 */
object SpecialTokensCatalog {

    private fun tokensOf(
        bos: String? = null,
        eos: String? = null,
        pad: String? = null,
        unk: String? = null,
        system: String? = null,
        user: String? = null,
        assistant: String? = null,
        tool: String? = null,
        endOfTurn: String? = null,
        endOfMessage: String? = null,
        extra: List<String> = emptyList()
    ) = SpecialTokens(bos, eos, pad, unk, system, user, assistant, tool, endOfTurn, endOfMessage, extra)

    val gemma: SpecialTokens = tokensOf(
        bos = "<bos>",
        eos = "<eos>",
        pad = "<pad>",
        unk = "<unk>",
        endOfTurn = "<end_of_turn>",
        extra = listOf("<start_of_turn>")
    )

    val qwen: SpecialTokens = tokensOf(
        bos = "<|im_start|>",
        eos = "<|im_end|>",
        pad = "<|endoftext|>",
        unk = "<|endoftext|>",
        system = "<|im_start|>",
        user = "<|im_start|>",
        assistant = "<|im_start|>",
        tool = "<|tool_call|>",
        endOfMessage = "<|im_end|>",
        extra = listOf("<|endoftext|>")
    )

    val llama3: SpecialTokens = tokensOf(
        bos = "<|begin_of_text|>",
        eos = "<|eot_id|>",
        pad = "<|end_of_text|>",
        unk = "<|end_of_text|>",
        system = "<|start_header_id|>",
        user = "<|start_header_id|>",
        assistant = "<|start_header_id|>",
        endOfMessage = "<|eot_id|>",
        extra = listOf("<|end_of_text|>", "<|end_header_id|>", "<|reserved_special_token_0|>")
    )

    val phi: SpecialTokens = tokensOf(
        bos = "<s>",
        eos = "<|endoftext|>",
        pad = "<|endoftext|>",
        unk = "<unk>",
        system = "<|system|>",
        user = "<|user|>",
        assistant = "<|assistant|>",
        endOfMessage = "<|endoftext|>",
        extra = listOf("<|end|>", "<|endoftext|>")
    )

    val mistral: SpecialTokens = tokensOf(
        bos = "<s>",
        eos = "</s>",
        pad = "<pad>",
        unk = "<unk>",
        user = "[INST]",
        assistant = "[/INST]",
        endOfMessage = "</s>",
        extra = listOf("[INST]", "[/INST]", "[AVAILABLE_TOOLS]", "[TOOL_CALLS]", "[TOOL_RESULTS]")
    )

    val deepseek: SpecialTokens = tokensOf(
        bos = "<|begin?of?sentence|>",
        eos = "<|EOT|>",
        pad = "<|end?of?sentence|>",
        unk = "<|EOT|>",
        user = "<|User|>",
        assistant = "<|Assistant|>",
        endOfMessage = "<|end?of?sentence|>",
        extra = listOf(
            "<|tool?calls?begin|>", "<|tool?call?begin|>", "<|tool?sep|>",
            "<|tool?call?end|>", "<|tool?calls?end|>", "<|tool?outputs?begin|>",
            "<|tool?output?begin|>", "<|tool?output?end|>", "<|tool?outputs?end|>"
        )
    )

    val smol: SpecialTokens = tokensOf(
        bos = "<|im_start|>",
        eos = "<|im_end|>",
        pad = "<|im_end|>",
        unk = "<|im_end|>",
        system = "<|im_start|>",
        user = "<|im_start|>",
        assistant = "<|im_start|>",
        endOfMessage = "<|im_end|>",
        extra = listOf("<|endoftext|>")
    )

    val tinyLlama: SpecialTokens = tokensOf(
        bos = "<s>",
        eos = "</s>",
        pad = "<pad>",
        unk = "<unk>",
        system = "<|system|>",
        user = "<|user|>",
        assistant = "<|assistant|>",
        endOfMessage = "</s>",
        extra = listOf("<|system|>", "<|user|>", "<|assistant|>")
    )

    /**
     * Generic mode: no family-specific vocabulary. Generation is terminated by
     * the container's OWN stop tokens (merged at load) plus the most common
     * Qwen-style end marker; nothing else is stripped from output.
     */
    val generic: SpecialTokens = tokensOf(eos = "<|im_end|>")

    val byFamily: Map<ModelFamily, SpecialTokens> = mapOf(
        ModelFamily.GEMMA to gemma,
        ModelFamily.QWEN2 to qwen,
        ModelFamily.QWEN2P5 to qwen,
        ModelFamily.QWEN3 to qwen,
        ModelFamily.PHI to phi,
        ModelFamily.LLAMA3 to llama3,
        ModelFamily.DEEPSEEK to deepseek,
        ModelFamily.MISTRAL to mistral,
        ModelFamily.SMOL to smol,
        ModelFamily.TINYLLAMA to tinyLlama,
        ModelFamily.GENERIC to generic
    )
}