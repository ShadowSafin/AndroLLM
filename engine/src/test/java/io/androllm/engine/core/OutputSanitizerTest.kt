package io.androllm.engine.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [OutputSanitizer] — the central sanitization layer that strips
 * internal reasoning artifacts, parser tokens and unfinished tool-call
 * markers from model output BEFORE it reaches the UI.
 *
 * The marker styles below cover the families this app supports:
 *  - Gemma 3  ( `<|tool_call|>{"function_name":...}<|tool_call_end|>` )
 *  - Gemma 4  ( `<|tool_call>call: name{}<tool_call|>` )
 *  - Qwen 2.5/3 ( thinking/response channel markers )
 *  - Llama-style templates ( `<start_of_turn>` / `<end_of_turn>` )
 *  - ChatML templates ( `<|im_start|>` / `<|im_end|>` )
 *  - Generic XML-ish artifacts ( `<tool_call>`, `<function_call>`,
 *    `<reasoning>`, partial/unfinished variants )
 */
class OutputSanitizerTest {

    // ---------------------------------------------------------------- final text

    @Test
    fun `strips unfinished tool call marker from answer tail`() {
        val clean = OutputSanitizer.sanitize("Hi! How can I help you<tool_call>")
        assertEquals("Hi! How can I help you", clean)
    }

    @Test
    fun `strips truncated tool call marker without closing angle bracket`() {
        assertEquals("Hi! How can I help you", OutputSanitizer.sanitize("Hi! How can I help you<tool_call"))
        assertEquals("Hi! How can I help you", OutputSanitizer.sanitize("Hi! How can I help you<tool_cal"))
    }

    @Test
    fun `strips gemma4 native tool call block including payload`() {
        val clean = OutputSanitizer.sanitize(
            "Sure, let me check.<|tool_call>call: get_battery{}<tool_call|>Your battery is at 95%."
        )
        assertEquals("Sure, let me check.Your battery is at 95%.", clean)
        assertFalse(clean.contains("tool_call"))
        assertFalse(clean.contains("get_battery"))
    }

    @Test
    fun `strips gemma3 json tool call block including payload`() {
        val clean = OutputSanitizer.sanitize(
            "<|tool_call|>{\"function_name\": \"get_weather\", \"arguments\": {\"location\": \"Current\"}}<|tool_call_end|>"
        )
        assertEquals("", clean)
    }

    @Test
    fun `strips xml style tool call block`() {
        val clean = OutputSanitizer.sanitize(
            "Let me get the time.<tool_call>get_current_time{}</tool_call>It is 10:30."
        )
        assertEquals("Let me get the time.It is 10:30.", clean)
    }

    @Test
    fun `strips function call block with payload`() {
        val clean = OutputSanitizer.sanitize(
            "<function_call>search_web{\"query\":\"weather\"}</function_call>Here is what I found."
        )
        assertEquals("Here is what I found.", clean)
    }

    @Test
    fun `strips reasoning block including its content`() {
        val clean = OutputSanitizer.sanitize(
            "<reasoning>Let me compute 2+2 step by step</reasoning>The answer is 4."
        )
        assertEquals("The answer is 4.", clean)
    }

    @Test
    fun `strips thinking block including its content`() {
        val clean = OutputSanitizer.sanitize(
            "<thinking>I should check the battery first</thinking>Your battery is at 87%."
        )
        assertEquals("Your battery is at 87%.", clean)
    }

    @Test
    fun `strips qwen thinking channel markers`() {
        val clean = OutputSanitizer.sanitize(" thinking\nLet me compute this.\n response\nThe answer is 4.")
        assertFalse(clean.contains(" thinking"))
        assertFalse(clean.contains(" response"))
        assertTrue(clean.contains("answer is 4"))
    }

    @Test
    fun `strips chatml template span wholesale`() {
        val clean = OutputSanitizer.sanitize(
            "<|im_start|>user\nHello<|im_end|>\n<|im_start|>assistant\nHi! How can I help<|im_end|>"
        )
        assertFalse(clean.contains("im_start"))
        assertFalse(clean.contains("im_end"))
        assertFalse(clean.contains("Hello"))
    }

    @Test
    fun `strips llama template spans`() {
        val clean = OutputSanitizer.sanitize(
            "<start_of_turn>user\nHi</start_of_turn><start_of_turn>assistant\nHello there</start_of_turn>"
        )
        assertFalse(clean.contains("start_of_turn"))
        assertFalse(clean.contains("Hello"))
    }

    @Test
    fun `strips leftover control tokens anywhere in the text`() {
        val clean = OutputSanitizer.sanitize("<bos>Hi there<eos><pad>")
        assertEquals("Hi there", clean)
    }

    @Test
    fun `strips unclosed block with payload to end of text`() {
        val clean = OutputSanitizer.sanitize("Let me check.<tool_call>call: get_battery{}")
        assertEquals("Let me check.", clean)
    }

    @Test
    fun `strips partial mid string control token`() {
        val clean = OutputSanitizer.sanitize("Result: 42\n<tool_call\nnext line content")
        assertFalse(clean.contains("tool_call"))
    }

    @Test
    fun `strips orphan close tag`() {
        val clean = OutputSanitizer.sanitize("Answer follows.</tool_call>")
        assertEquals("Answer follows.", clean)
    }

    @Test
    fun `strips nested tool call block with closing tag`() {
        val clean = OutputSanitizer.sanitize(
            "Opening.<tool_call><function_name>get_battery</function_name></tool_call>Done."
        )
        assertEquals("Opening.Done.", clean)
    }

    // ------------------------------------------------------------ normal text

    @Test
    fun `preserves normal prose with comparisons`() {
        assertEquals("2 < 3", OutputSanitizer.sanitize("2 < 3"))
    }

    @Test
    fun `preserves ordinary html-like tags that are not control tags`() {
        assertEquals("<b>bold</b>", OutputSanitizer.sanitize("<b>bold</b>"))
        assertEquals("<code>val x = 1</code>", OutputSanitizer.sanitize("<code>val x = 1</code>"))
    }

    @Test
    fun `preserves the plain words thinking and response`() {
        val clean = OutputSanitizer.sanitize("I was thinking about your response.")
        assertEquals("I was thinking about your response.", clean)
    }

    @Test
    fun `preserves user chat text`() {
        assertEquals("Hi! How can I help you today?", OutputSanitizer.sanitize("Hi! How can I help you today?"))
    }

    // ---------------------------------------------------------------- blank

    @Test
    fun `detects control token only output as blank`() {
        assertTrue(OutputSanitizer.isBlankAfterSanitization("<tool_call>call: get_battery{}</tool_call>"))
        assertTrue(OutputSanitizer.isBlankAfterSanitization("<|tool_call|>{\"name\":\"x\"}<|tool_call_end|>"))
        assertTrue(OutputSanitizer.isBlankAfterSanitization("<|im_start|>assistant<|im_end|>"))
        assertFalse(OutputSanitizer.isBlankAfterSanitization("Hi! How can I help you?"))
    }

    @Test
    fun `returns empty for blank input`() {
        assertEquals("", OutputSanitizer.sanitize(""))
        assertEquals("", OutputSanitizer.sanitize("   "))
    }

    // ------------------------------------------------------------- streaming

    @Test
    fun `streaming holds back unfinished tool call marker`() {
        assertEquals("Hi! How can I help you", OutputSanitizer.streamingReady("Hi! How can I help you<tool_call"))
    }

    @Test
    fun `streaming holds back complete open tag until its block closes`() {
        assertEquals("Sure", OutputSanitizer.streamingReady("Sure<tool_call>call: get_battery{}"))
        assertEquals(
            "Suredone",
            OutputSanitizer.streamingReady("Sure<tool_call>call: get_battery{}<tool_call|>done")
        )
    }

    @Test
    fun `streaming holds back partial token until it closes or the stream ends`() {
        // No closing `>` yet — held back even when more text arrived.
        assertEquals("A", OutputSanitizer.streamingReady("A<|im_star"))
        assertEquals("A", OutputSanitizer.streamingReady("A<|im_starB"))
        // Once the token completes AND its block closes, the span is stripped.
        assertEquals("AC", OutputSanitizer.streamingReady("A<|im_start|>B<|im_end|>C"))
    }

    @Test
    fun `streaming emits complete blocks cleanly`() {
        assertEquals("Hi", OutputSanitizer.streamingReady("Hi<|tool_call>call: x{}<tool_call|>"))
    }

    @Test
    fun `streaming never leaks payload before the close marker`() {
        assertEquals("Let me check", OutputSanitizer.streamingReady("Let me check<|tool_call>call: get_battery{}"))
    }

    @Test
    fun `streaming result is a stable prefix of the final sanitized text`() {
        val accumulated = "Sure, let me check.<|tool_call>call: get_battery{}<tool_call|>It is at 95%."
        val streamed = OutputSanitizer.streamingReady(accumulated)
        val finalText = OutputSanitizer.sanitize(accumulated)
        assertTrue(finalText.startsWith(streamed.trim()))
    }

    // -------------------------------------------- trailing tokenizer artifacts

    @Test
    fun `strips byte fallback char glued to the end of a response`() {
        assertEquals("Hi! How can I help you", OutputSanitizer.sanitize("Hi! How can I help youи_"))
        assertEquals("you", OutputSanitizer.sanitize("youи"))
    }

    @Test
    fun `strips trailing underscore fragment`() {
        assertEquals("Sure", OutputSanitizer.sanitize("Sure_"))
        assertEquals("text", OutputSanitizer.sanitize("text__"))
        assertEquals("5", OutputSanitizer.sanitize("5_"))
    }

    @Test
    fun `strips replacement character from invalid utf8 sequence`() {
        assertEquals("Hello", OutputSanitizer.sanitize("Hello\uFFFD"))
        assertEquals("Hello", OutputSanitizer.sanitize("Hello\uFFFD\uFFFD"))
    }

    @Test
    fun `removes replacement character anywhere in the text`() {
        assertEquals("Hello", OutputSanitizer.sanitize("Hel\uFFFDlo"))
    }

    @Test
    fun `strips stray angle bracket fragments from the tail`() {
        assertEquals("Sure!", OutputSanitizer.sanitize("Sure!<"))
        assertEquals("Sure!", OutputSanitizer.sanitize("Sure!<"))
        assertEquals("a", OutputSanitizer.sanitize("a|>"))
        assertEquals("a", OutputSanitizer.sanitize("a<|"))
        assertEquals("x", OutputSanitizer.sanitize("x</"))
    }

    @Test
    fun `strips trailing sentencepiece word start marker`() {
        assertEquals("text", OutputSanitizer.sanitize("text\u2581"))
    }

    @Test
    fun `removes sentencepiece byte fallback hex tokens anywhere`() {
        assertEquals("sure", OutputSanitizer.sanitize("<0x5B>sure<0x5D>"))
        assertEquals("", OutputSanitizer.sanitize("<0x0A>"))
    }

    @Test
    fun `preserves legitimate multilingual endings`() {
        assertEquals("Привет", OutputSanitizer.sanitize("Привет"))
        assertEquals("Книга и", OutputSanitizer.sanitize("Книга и_"))
        assertEquals("Спасибо!", OutputSanitizer.sanitize("Спасибо!"))
    }

    @Test
    fun `preserves urls and closed html endings`() {
        assertEquals("https://example.com/", OutputSanitizer.sanitize("https://example.com/"))
        assertEquals("2 < 3", OutputSanitizer.sanitize("2 < 3"))
        assertEquals("<b>bold</b>", OutputSanitizer.sanitize("<b>bold</b>"))
    }

    @Test
    fun `streaming holds back trailing artifacts until the stream ends`() {
        assertEquals("Hi! How can I help you", OutputSanitizer.streamingReady("Hi! How can I help youи_"))
        assertEquals("Sure!", OutputSanitizer.streamingReady("Sure!<"))
        assertEquals("Sure!", OutputSanitizer.streamingReady("Sure!_"))
        assertEquals("Hello", OutputSanitizer.streamingReady("Hello\uFFFD"))
    }

    @Test
    fun `streaming releases artifacts once real text follows`() {
        // The artifact becomes mid-text once more text arrives: "и_" is a
        // valid string in that position, so the whole buffer is emitted.
        assertEquals("youи_!", OutputSanitizer.streamingReady("youи_!"))
        assertEquals("a|>b", OutputSanitizer.streamingReady("a|>b"))
    }
}