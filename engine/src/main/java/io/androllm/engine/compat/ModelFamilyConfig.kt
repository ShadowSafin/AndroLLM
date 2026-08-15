package io.androllm.engine.compat

/**
 * Per-family generation defaults (sampler parameters and limits), mirroring the
 * reference values the family ships with. These are the starting point for the
 * engine's `SamplerConfig`; the user's explicit per-model overrides still win.
 */
data class GenerationDefaults(
    val topK: Int? = null,
    val topP: Float? = null,
    val temperature: Float? = null,
    val seed: Int? = null,
    val maxTokens: Int? = null
)

/**
 * The complete compatibility contract of one model family: its official chat
 * template, its special tokens, and its generation defaults. The engine only
 * ever talks to [ModelFamilyConfig] — never to individual families directly —
 * which is what keeps adding a new family a one-file change.
 */
data class ModelFamilyConfig(
    val family: ModelFamily,
    val chatTemplate: String,
    val specialTokens: SpecialTokens,
    val defaults: GenerationDefaults,
    /** The thinking channel for families that have one (Qwen3, ...), else null. */
    val thinkingChannel: ChannelSpec? = null
) {
    /** The strings at which generation must stop (EOS + end-of-turn markers). */
    val stopSequences: List<String>
        get() = specialTokens.stopSequences

    /** Every token string that must never leak into user-facing output. */
    val forbiddenInOutput: Set<String>
        get() = specialTokens.all
}