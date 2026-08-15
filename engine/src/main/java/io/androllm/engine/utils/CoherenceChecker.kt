package io.androllm.engine.utils

/**
 * Outcome of a probe-output coherence check.
 */
sealed interface CoherenceResult {
    data object Pass : CoherenceResult
    data class Fail(val reason: String) : CoherenceResult
}

/**
 * Decides whether a short probe generation ("Hi" at temperature 0, ~12 tokens)
 * produced coherent text — or the classic corruption signatures: empty output,
 * non-printable/replacement-character garbage (broken tokenizer or weights),
 * or degenerate single-token repetition (broken sampling/weights).
 *
 * Pure and side-effect free so the exact thresholds are unit-tested. Used by
 * the post-load model self-test: a model whose probe output fails is unloaded
 * immediately instead of generating gibberish in chat.
 */
object CoherenceChecker {

    /** Minimum fraction of printable characters for the output to be text. */
    private const val MIN_PRINTABLE_RATIO = 0.6

    /** Maximum fraction of the Unicode replacement char (U+FFFD). */
    private const val MAX_REPLACEMENT_RATIO = 0.1

    /** Outputs where one token piece makes up more than this fraction are degenerate. */
    private const val MAX_TOP_CHAR_FREQUENCY = 0.7

    /**
     * Minimum probe length in characters — a single-word "Hi" reply is normal
     * for some models, but a probe that returns almost nothing while claiming
     * to have generated 12 tokens is suspicious.
     */
    private const val MIN_TEXT_LENGTH = 2

    fun check(text: String?): CoherenceResult {
        val output = text?.trim().orEmpty()
        if (output.isEmpty()) {
            return CoherenceResult.Fail("model produced no output")
        }
        if (output.length < MIN_TEXT_LENGTH) {
            return CoherenceResult.Fail("output too short to be coherent: \"$output\"")
        }

        var printable = 0
        var replacement = 0
        var control = 0
        for (ch in output) {
            when {
                ch == '\uFFFD' -> replacement++
                ch.code < 0x20 && ch.code != '\n'.code && ch.code != '\t'.code && ch.code != '\r'.code -> control++
                !ch.isISOControl() -> printable++
            }
        }
        val printableRatio = printable.toDouble() / output.length
        val replacementRatio = replacement.toDouble() / output.length
        if (control > 0) {
            return CoherenceResult.Fail("output contains control characters")
        }
        if (replacementRatio > MAX_REPLACEMENT_RATIO) {
            return CoherenceResult.Fail("output contains replacement-character garbage (broken tokenizer)")
        }
        if (printableRatio < MIN_PRINTABLE_RATIO) {
            return CoherenceResult.Fail("output is not printable text")
        }

        // Degenerate repetition: the same character dominating the output
        // (e.g. "aaaaaaaa", ")))...", "██████") signals a broken sampler/weights.
        if (output.length >= 8) {
            val mostFrequent = output.groupingBy { it }.eachCount().maxByOrNull { it.value }?.value ?: 0
            if (mostFrequent.toDouble() / output.length > MAX_TOP_CHAR_FREQUENCY) {
                return CoherenceResult.Fail("output degenerates into repeated characters")
            }
        }
        return CoherenceResult.Pass
    }
}
