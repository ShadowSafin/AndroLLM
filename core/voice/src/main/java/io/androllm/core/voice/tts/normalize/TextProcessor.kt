package io.androllm.core.voice.tts.normalize

/**
 * A single stage of the text-normalization pipeline.
 *
 * Every stage is independently testable and stateless by contract: it must
 * produce identical output for identical input and never depend on pipeline
 * order internally (order is owned by [TextNormalizationPipeline]).
 */
fun interface TextProcessor {
    fun process(input: String): String
}