package io.androllm.engine.compat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for [OutputDecoder] — the enforcement point of the family
 * decode rules. Pins the exact user-visible failure signatures this feature
 * was built against: leaked `<|im_start|>` role markers, tool-call fragments,
 * thinking-channel markers, and UTF-8 split across chunk boundaries.
 */
class OutputDecoderTest {

    private val qwen = ModelFamilyRegistry.configFor(ModelFamily.QWEN2P5)
    private val qwen3 = ModelFamilyRegistry.configFor(ModelFamily.QWEN3)
    private val gemma = ModelFamilyRegistry.configFor(ModelFamily.GEMMA)
    private val deepseek = ModelFamilyRegistry.configFor(ModelFamily.DEEPSEEK)

    // ---- user-symptom strings ------------------------------------------------

    @Test
    fun `leaked im_start role markers are stripped`() {
        // The exact symptom reported before this feature: the model emitted
        // raw template markers mid-generation.
        val raw = "The battery is at 87%. <|im_start|>assistant\n<|im_end|>"
        val clean = OutputDecoder(qwen).clean(raw)
        assertFalse(clean.contains("<|im_start|>"))
        assertFalse(clean.contains("<|im_end|>"))
        assertTrue(clean.contains("87%"))
    }

    @Test
    fun `tool-call fragments are stripped`() {
        val raw = "The battery is at 87%. <|tool_call|>{\"name\":\"get_battery\"}<|im_end|>"
        val clean = OutputDecoder(qwen).clean(raw)
        assertFalse(clean.contains("<|tool_call|>"))
        assertTrue(clean.contains("87%"))
    }

    @Test
    fun `stop sequence cuts the output at the first occurrence`() {
        val clean = OutputDecoder(qwen).clean("First part<|im_end|>Second part")
        assertEquals("First part", clean)
    }

    @Test
    fun `thinking markers are stripped for qwen3`() {
        val raw = "<think>\nLet me compute this.\n</think>\nThe answer is 4."
        val clean = OutputDecoder(qwen3).clean(raw)
        assertFalse(clean.contains("<think>"))
        assertFalse(clean.contains("</think>"))
        assertTrue(clean.contains("answer is 4"))
    }

    @Test
    fun `gemma turn markers are stripped`() {
        val clean = OutputDecoder(gemma).clean(
            "The sky is blue.<end_of_turn><eos>"
        )
        assertFalse(clean.contains("<end_of_turn>"))
        assertFalse(clean.contains("<eos>"))
        assertTrue(clean.contains("blue"))
    }

    @Test
    fun `deepseek markers are stripped`() {
        val raw = "Sure!<|Assistant|><|EOT|>"
        val clean = OutputDecoder(deepseek).clean(raw)
        assertFalse(clean.contains("<|Assistant|>"))
        assertFalse(clean.contains("<|EOT|>"))
        assertTrue(clean.contains("Sure"))
    }

    // ---- UTF-8 chunk safety --------------------------------------------------

    @Test
    fun `incomplete trailing multibyte sequence is held across chunks`() {
        val decoder = OutputDecoder(qwen)
        val full = "Hello \uD83D\uDE00 world".toByteArray(Charsets.UTF_8)
        val split = full.size - 2
        val first = decoder.feed(full.copyOfRange(0, split))
        assertFalse(first.endsWith("\uFFFD"))
        val second = decoder.feed(full.copyOfRange(split, full.size))
        assertEquals("Hello \uD83D\uDE00 world", first + second)
    }

    @Test
    fun `feed then finish joins cleanly`() {
        val decoder = OutputDecoder(qwen)
        val full = "Answer: 42<|im_end|>".toByteArray(Charsets.UTF_8)
        val half = full.size / 2
        val a = decoder.feed(full.copyOfRange(0, half))
        val b = decoder.feed(full.copyOfRange(half, full.size))
        assertEquals("Answer: 42", (a + b).trim())
        assertEquals("", decoder.finish())
    }

    @Test
    fun `ascii chunks pass through untouched`() {
        val decoder = OutputDecoder(qwen)
        assertEquals("plain text", decoder.clean("plain text"))
    }

    // ---- model-specific stop sequences (catalog metadata) ------------------

    @Test
    fun `extra stop sequence cuts the output`() {
        // A model-specific stop token from the catalog (e.g. a container that
        // declares its own EOS) must terminate the text even when the family
        // default does not contain it.
        val decoder = OutputDecoder(qwen, extraStopSequences = listOf("<|extra_eos|>"))
        val clean = decoder.clean("The answer is 42<|extra_eos|>garbage after")
        assertEquals("The answer is 42", clean)
    }

    @Test
    fun `extra stop sequence never leaks into the output`() {
        val decoder = OutputDecoder(gemma, extraStopSequences = listOf("<custom_stop>"))
        val clean = decoder.clean("Done.<custom_stop><end_of_turn>")
        assertFalse(clean.contains("<custom_stop>"))
        assertFalse(clean.contains("<end_of_turn>"))
        assertEquals("Done.", clean)
    }

    @Test
    fun `family stops still apply alongside extra stops`() {
        val decoder = OutputDecoder(qwen, extraStopSequences = listOf("<custom_stop>"))
        assertEquals("A", decoder.clean("A<|im_end|>B<custom_stop>C"))
    }

    @Test
    fun `blank extra stop sequences are ignored`() {
        val decoder = OutputDecoder(qwen, extraStopSequences = listOf("", " "))
        assertEquals("plain text", decoder.clean("plain text"))
    }

    @Test
    fun `duplicate stops are harmless`() {
        val decoder = OutputDecoder(qwen, extraStopSequences = listOf("<|im_end|>", "<|im_end|>"))
        assertEquals("A", decoder.clean("A<|im_end|>B"))
    }
}