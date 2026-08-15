package io.androllm.engine.compat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for [StopSequenceTracker] — the detection half of the
 * stop-token bug fix: the native decoder must terminate the moment a model
 * stop sequence completes, even when the sequence is split across the
 * per-token fragments LiteRT-LM delivers.
 */
class StopSequenceTrackerTest {

    // ---- detection ----------------------------------------------------------

    @Test
    fun `gemma stops on end_of_turn in a single fragment`() {
        val tracker = StopSequenceTracker(listOf("<end_of_turn>", "<eos>"))
        assertNull(tracker.feed("The sky is blue"))
        assertEquals("<end_of_turn>", tracker.feed("<end_of_turn>"))
        assertTrue(tracker.isStopped)
        assertEquals(15, tracker.stopStartIndex)
    }

    @Test
    fun `qwen stops on im_end split across fragments`() {
        val tracker = StopSequenceTracker(listOf("<|im_end|>", "<|endoftext|>"))
        assertNull(tracker.feed("The answer is 42<|im_"))
        assertEquals("<|im_end|>", tracker.feed("end|>"))
        // Stop began at the start of the partial in the previous fragment.
        assertEquals(16, tracker.stopStartIndex)
    }

    @Test
    fun `llama stops on eot_id split across many fragments`() {
        val tracker = StopSequenceTracker(listOf("<|eot_id|>"))
        // Feed everything except the final ">" — must not fire early.
        for (piece in listOf("<|", "eot", "_", "id", "|")) {
            assertNull(tracker.feed(piece))
        }
        assertFalse(tracker.isStopped)
        assertEquals("<|eot_id|>", tracker.feed(">"))
        assertTrue(tracker.isStopped)
        assertEquals(0, tracker.stopStartIndex)
    }

    @Test
    fun `deepseek stops on end of sentence marker`() {
        val tracker = StopSequenceTracker(listOf("<|EOT|>", "<|end?of?sentence|>"))
        assertNull(tracker.feed("Sure!"))
        assertEquals("<|EOT|>", tracker.feed("<|EOT|>"))
        assertEquals(5, tracker.stopStartIndex)
    }

    @Test
    fun `first stop wins`() {
        val tracker = StopSequenceTracker(listOf("<|im_end|>", "<|endoftext|>"))
        tracker.feed("A")
        assertEquals("<|im_end|>", tracker.feed("<|im_end|>"))
        // Later fragments (post-stop garbage) are ignored.
        assertEquals("<|im_end|>", tracker.feed("<|endoftext|>garbage"))
        assertEquals(1, tracker.stopStartIndex)
    }

    @Test
    fun `no false positive on lookalike text`() {
        val tracker = StopSequenceTracker(listOf("<end_of_turn>", "<|im_end|>"))
        // "end_of_turn" without the closing ">" — a partial that never
        // completes must never fire the stop, however many fragments carry it.
        tracker.feed("The sky is blue.<end_of_tur")
        tracker.feed("n is not the end here")
        tracker.feed(".")
        assertFalse(tracker.isStopped)
        assertNull(tracker.matched)
    }

    @Test
    fun `tail window rolls past old text without losing detection`() {
        val tracker = StopSequenceTracker(listOf("<|im_end|>"))
        // Feed far more than the 64-char window before the stop.
        val filler = "The quick brown fox jumps over the lazy dog. ".repeat(20)
        assertNull(tracker.feed(filler))
        assertNull(tracker.feed("Answer: 42<|im_"))
        assertEquals("<|im_end|>", tracker.feed("end|>"))
        // Index counts from the very start of the stream.
        assertEquals(910L, tracker.stopStartIndex)
    }

    @Test
    fun `empty and blank stop sequences never match`() {
        val tracker = StopSequenceTracker(listOf("", " "))
        assertNull(tracker.feed("anything"))
        assertFalse(tracker.isStopped)
        assertEquals(0, tracker.holdbackLength)
    }

    // ---- holdback -----------------------------------------------------------

    @Test
    fun `holdback is longest stop minus one`() {
        assertEquals(12, StopSequenceTracker(listOf("<end_of_turn>")).holdbackLength)
        assertEquals(9, StopSequenceTracker(listOf("<|im_end|>")).holdbackLength)
        assertEquals(
            12,
            StopSequenceTracker(listOf("<|im_end|>", "<end_of_turn>", "x")).holdbackLength
        )
    }

    @Test
    fun `stop start index is correct when the stop begins inside a fragment`() {
        val tracker = StopSequenceTracker(listOf("</s>"))
        assertNull(tracker.feed("Hello world, this is a test. </s"))
        assertEquals("</s>", tracker.feed(">"))
        assertEquals(29L, tracker.stopStartIndex)
    }
}