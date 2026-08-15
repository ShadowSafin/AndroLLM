package io.androllm.engine

import io.androllm.engine.core.NativeToolCallScanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [NativeToolCallScanner] — the parser that turns native
 * `<|tool_call|>` markers in a model's output into structured tool calls
 * (cloud-style function calling for local LiteRT models).
 */
class NativeToolCallScannerTest {

    @Test
    fun `parses gemma4 style call with empty arguments`() {
        val calls = NativeToolCallScanner.scan(
            "<|tool_call>call: get_battery{}<tool_call|>"
        )
        assertEquals(1, calls.size)
        assertEquals("get_battery", calls[0].name)
        assertEquals("{}", calls[0].argumentsJson)
    }

    @Test
    fun `parses gemma4 style call with json arguments`() {
        val calls = NativeToolCallScanner.scan(
            "<|tool_call>call: open_app{\"package\":\"com.android.chrome\"}<tool_call|>"
        )
        assertEquals(1, calls.size)
        assertEquals("open_app", calls[0].name)
        assertTrue(calls[0].argumentsJson.contains("com.android.chrome"))
    }

    @Test
    fun `parses gemma3 style function_call object`() {
        val calls = NativeToolCallScanner.scan(
            "<|tool_call|>{\"function_name\": \"get_weather\", \"arguments\": {\"location\": \"Current\"}}<|tool_call_end|>"
        )
        assertEquals(1, calls.size)
        assertEquals("get_weather", calls[0].name)
        assertTrue(calls[0].argumentsJson.contains("Current"))
    }

    @Test
    fun `parses multiple calls in one output`() {
        val raw = "Let me check.<|tool_call>call: get_battery{}<tool_call|>" +
            "<|tool_call>call: get_device_info{}<tool_call|>"
        val calls = NativeToolCallScanner.scan(raw)
        assertEquals(2, calls.size)
        assertEquals("get_battery", calls[0].name)
        assertEquals("get_device_info", calls[1].name)
    }

    @Test
    fun `handles bare name without braces`() {
        val calls = NativeToolCallScanner.scan("<|tool_call>call: get_battery<tool_call|>")
        assertEquals(1, calls.size)
        assertEquals("get_battery", calls[0].name)
        assertEquals("{}", calls[0].argumentsJson)
    }

    @Test
    fun `returns empty for plain text`() {
        assertTrue(NativeToolCallScanner.scan("Your battery is at 95%.").isEmpty())
    }

    @Test
    fun `strips markers and payload from text`() {
        val stripped = NativeToolCallScanner.strip(
            "<|tool_call>call: get_battery{}<tool_call|>Your battery is at 95%."
        )
        assertFalse(stripped.contains("tool_call"))
        assertFalse(stripped.contains("get_battery"))
        assertTrue(stripped.contains("95%"))
    }

    @Test
    fun `strip removes markers spanning whole output`() {
        assertEquals("", NativeToolCallScanner.strip("<|tool_call>call: get_battery{}<tool_call|>"))
    }

    @Test
    fun `truncated block is dropped not leaked`() {
        val raw = "<|tool_call>call: get_battery{}"
        assertTrue(NativeToolCallScanner.scan(raw).isEmpty())
        assertEquals("", NativeToolCallScanner.strip(raw))
    }
}
