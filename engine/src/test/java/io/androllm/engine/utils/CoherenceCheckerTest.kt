package io.androllm.engine.utils

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for [CoherenceChecker] — the post-load probe that rejects a
 * model whose weights/tokenizer produce gibberish instead of text. Pins the
 * exact failure signatures: empty output, non-printable garbage, replacement
 * characters, and degenerate repetition.
 */
class CoherenceCheckerTest {

    @Test
    fun `coherent probe output passes`() {
        val result = CoherenceChecker.check("Hello! I am here to help you today.")
        assertTrue(result is CoherenceResult.Pass)
    }

    @Test
    fun `empty output fails`() {
        val result = CoherenceChecker.check("")
        assertTrue(result is CoherenceResult.Fail)
        assertTrue((result as CoherenceResult.Fail).reason.contains("no output"))
    }

    @Test
    fun `null output fails`() {
        assertTrue(CoherenceChecker.check(null) is CoherenceResult.Fail)
    }

    @Test
    fun `too-short output fails`() {
        assertTrue(CoherenceChecker.check("a") is CoherenceResult.Fail)
    }

    @Test
    fun `control-character garbage fails`() {
        // Binary junk with control characters is broken-tokenizer output.
        val garbage = "\u0000\u0001\u0002\u0003\u0004\u0005\u0006\u0007\b\t\n"
        assertTrue(CoherenceChecker.check(garbage) is CoherenceResult.Fail)
    }

    @Test
    fun `replacement-character garbage fails`() {
        val garbage = "\uFFFD\uFFFD\uFFFD\uFFFD\uFFFD\uFFFD\uFFFD\uFFFD"
        val result = CoherenceChecker.check(garbage)
        assertTrue(result is CoherenceResult.Fail)
        assertTrue((result as CoherenceResult.Fail).reason.contains("replacement"))
    }

    @Test
    fun `degenerate single-character repetition fails`() {
        // Classic broken-sampler output: one repeated token piece dominates.
        val result = CoherenceChecker.check("aaaaaaaaaaaaaaaaaaaaaaaa")
        assertTrue(result is CoherenceResult.Fail)
        assertTrue((result as CoherenceResult.Fail).reason.contains("repeated"))
    }

    @Test
    fun `non-printable mixture fails`() {
        val halfGarbage = "\u0001\u0001\u0001\u0001Hello there world"
        assertTrue(CoherenceChecker.check(halfGarbage) is CoherenceResult.Fail)
    }

    @Test
    fun `normal short replies still pass`() {
        assertTrue(CoherenceChecker.check("Sure!") is CoherenceResult.Pass)
        assertTrue(CoherenceChecker.check("I don't know.") is CoherenceResult.Pass)
    }
}
