package io.androllm.engine.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [GenerationLoopGuard] — pathological-generation detection
 * (same token forever / same phrase forever) that terminates a runaway
 * decode within seconds instead of burning the whole token budget.
 */
class GenerationLoopGuardTest {

    @Test
    fun `normal varied prose never triggers`() {
        val guard = GenerationLoopGuard()
        // Numbered lines guarantee every sentence is unique, so no exact
        // cycle exists in the rolling window.
        var fed = 0
        var line = 0
        while (fed < 6000) {
            val fragment = "Answer number ${line++} explains topic ${line * 7} in detail. "
            assertFalse("triggered at char $fed on normal text", guard.feed(fragment))
            fed += fragment.length
        }
    }

    @Test
    fun `identical fragment loop triggers`() {
        val guard = GenerationLoopGuard()
        var triggered = false
        for (i in 0 until GenerationLoopGuard.MAX_IDENTICAL_FRAGMENTS + 5) {
            if (guard.feed("blah")) {
                triggered = true
                break
            }
        }
        assertTrue(triggered)
        assertNotNull(guard.detail)
    }

    @Test
    fun `phrase cycle triggers even when fragments differ`() {
        val guard = GenerationLoopGuard()
        val unit = "Help me I am stuck "
        var triggered = false
        outer@ for (i in 0 until 200) {
            // Split each repetition into two DIFFERENT fragments so the
            // identical-fragment detector cannot fire — only the phrase
            // detector can catch this shape.
            val a = unit.take(10)
            val b = unit.drop(10)
            if (guard.feed(a) || guard.feed(b)) {
                triggered = true
                break@outer
            }
            if (i > 60) {
                // The window holds >= MIN_UNIT_REPEATS repetitions by now.
            }
        }
        assertTrue(triggered)
    }

    @Test
    fun `short punctuation-only repeats do not trigger`() {
        val guard = GenerationLoopGuard()
        // Healthy prose legitimately repeats spaces, dots and newlines.
        repeat(GenerationLoopGuard.MAX_IDENTICAL_FRAGMENTS + 10) {
            assertFalse(guard.feed("."))
            assertFalse(guard.feed(" "))
        }
    }

    @Test
    fun `guard latches after detection`() {
        val guard = GenerationLoopGuard()
        repeat(GenerationLoopGuard.MAX_IDENTICAL_FRAGMENTS + 2) { guard.feed("loop") }
        assertTrue(guard.isLooping)
        // Further feeds keep reporting the latch without resetting.
        assertTrue(guard.feed("anything"))
        assertTrue(guard.isLooping)
    }

    @Test
    fun `empty and blank fragments are ignored`() {
        val guard = GenerationLoopGuard()
        repeat(100) {
            assertFalse(guard.feed(""))
            assertFalse(guard.feed("   "))
        }
    }
}
