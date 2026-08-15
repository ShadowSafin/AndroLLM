package io.androllm.engine.compat

/**
 * The parsed `litert.lm.proto.LlmMetadata` from a `.litertlm` container —
 * the metadata LiteRT-LM itself reads when it loads the model. Fields the
 * compatibility layer needs for family detection and conversation setup.
 */
data class ContainerMetadata(
    /** The container's start token (BOS), string form when the repack stored it. */
    val startToken: String? = null,
    /** The container's declared stop tokens (EOS / end-of-turn), string form. */
    val stopTokens: List<String> = emptyList(),
    /** The container's real context limit in tokens (0 = unset). */
    val maxNumTokens: Int = 0,
    /** The LlmModelType oneof member name ("qwen3", "gemma3", ... null when generic/unset). */
    val modelTypeName: String? = null,
    /** The chat template baked into the container (null when absent). */
    val jinjaPromptTemplate: String? = null,
    /** Sampler parameters baked into the container (0/0f = unset). */
    val samplerType: Int = 0,
    val samplerTopK: Int = 0,
    val samplerTopP: Float = 0f,
    val samplerTemperature: Float = 0f,
    val samplerSeed: Int = -1,
    /** Chat channels defined by the container (e.g. the thinking channel). */
    val channels: List<ChannelSpec> = emptyList(),
    /** Token ids the container declares should never be sampled. */
    val suppressTokenIds: List<Int> = emptyList()
) {
    val hasEmbeddedTemplate: Boolean get() = !jinjaPromptTemplate.isNullOrBlank()
}

/**
 * Result of reading the tokenizer sections of a `.litertlm` container.
 *
 * The container embeds the tokenizer the same way the runtime reads it:
 * either a Hugging Face `tokenizer.json` (zlib-compressed) or a SentencePiece
 * `tokenizer.model` (raw). The compatibility layer uses these bytes to enrich
 * the per-family decode rules with the container's ACTUAL special tokens
 * (added tokens), and to fail fast when a required tokenizer is absent.
 */
data class EmbeddedTokenizer(
    val kind: TokenizerKind,
    /** Decompressed `tokenizer.json` (BPE families) or raw `tokenizer.model` (SP families). */
    val bytes: ByteArray,
    /** Raw zlib-compressed payload (BPE families), for reference. */
    val compressedBytes: ByteArray? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EmbeddedTokenizer) return false
        return kind == other.kind && bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int = kind.hashCode() * 31 + bytes.contentHashCode()
}

/**
 * Thrown when a model cannot be mapped to a supported family or its tokenizer
 * configuration is incomplete. Carries an exact, actionable message (including
 * the missing file name) — the engine surfaces it as the load failure reason.
 */
class ModelCompatibilityException(message: String, cause: Throwable? = null) : Exception(message, cause)