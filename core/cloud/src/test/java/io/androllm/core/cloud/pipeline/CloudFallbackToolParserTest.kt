package io.androllm.core.cloud.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fallback tool-call extraction for providers/models that write tool calls
 * into the answer text instead of emitting native tool_calls.
 */
class CloudFallbackToolParserTest {

    @Test
    fun `parses xml tool_call tag with json object arguments`() {
        val text = """
            Let me check the weather.
            <tool_call>{"name": "get_weather", "arguments": {"location": "Berlin"}}</tool_call>
        """.trimIndent()
        val calls = CloudFallbackToolParser.parse(text)
        assertEquals(1, calls.size)
        assertEquals("get_weather", calls[0].name)
        assertTrue(calls[0].argumentsJson.contains("Berlin"))
    }

    @Test
    fun `parses function_call tag variant`() {
        val text = """<function_call>{"name":"search_web","args":{"query":"android llm"}}</function_call>"""
        val calls = CloudFallbackToolParser.parse(text)
        assertEquals(1, calls.size)
        assertEquals("search_web", calls[0].name)
        assertTrue(calls[0].argumentsJson.contains("android llm"))
    }

    @Test
    fun `parses arguments embedded as json string`() {
        val text = """<tool_call>{"name":"send_sms","arguments":"{\"to\":\"Mom\",\"body\":\"rain soon\"}"}</function_results>"""
        // Intentionally mismatched close tag is NOT parsed by the xml regex;
        // use a proper close to test the embedded-string form.
        val proper = """<tool_call>{"name":"send_sms","arguments":"{\"to\":\"Mom\",\"body\":\"rain soon\"}"}</function_call>"""
        val calls = CloudFallbackToolParser.parse(proper)
        assertEquals(1, calls.size)
        assertEquals("send_sms", calls[0].name)
        assertTrue(calls[0].argumentsJson.contains("Mom"))
    }

    @Test
    fun `parses markdown fenced json block`() {
        val text = """
            I will search now.
            ```json
            {"name": "search_web", "arguments": {"query": "litellm caching"}}
            ```
        """.trimIndent()
        val calls = CloudFallbackToolParser.parse(text)
        assertEquals(1, calls.size)
        assertEquals("search_web", calls[0].name)
    }

    @Test
    fun `parses bare json envelope with calls array`() {
        val text = """Sure! {"calls": [{"name":"get_weather","arguments":{"location":"Paris"}}, {"name":"calculate","arguments":{"expression":"2+2"}}]}"""
        val calls = CloudFallbackToolParser.parse(text)
        assertEquals(2, calls.size)
        assertEquals("get_weather", calls[0].name)
        assertEquals("calculate", calls[1].name)
    }

    @Test
    fun `parses single bare json object in prose`() {
        val text = """Okay, running the tool now: {"tool": "get_weather", "args": {"location": "Tokyo"}} — one moment."""
        val calls = CloudFallbackToolParser.parse(text)
        assertEquals(1, calls.size)
        assertEquals("get_weather", calls[0].name)
        assertTrue(calls[0].argumentsJson.contains("Tokyo"))
    }

    @Test
    fun `normalizes provider prefixed and oddly cased names`() {
        val text = """<tool_call>{"name":"functions.Web_Search","arguments":{}}</function_call>"""
        val calls = CloudFallbackToolParser.parse(text)
        assertEquals(1, calls.size)
        assertEquals("web_search", calls[0].name)
    }

    @Test
    fun `dedupes identical repeated calls`() {
        val text = """
            <tool_call>{"name":"get_weather","arguments":{"location":"Rome"}}</function_call>
            <tool_call>{"name":"get_weather","arguments":{"location":"Rome"}}</function_call>
        """.trimIndent()
        val calls = CloudFallbackToolParser.parse(text)
        assertEquals(1, calls.size)
    }

    @Test
    fun `plain prose without tool syntax yields nothing`() {
        val calls = CloudFallbackToolParser.parse("The weather in Berlin is rainy today. Enjoy your day!")
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `malformed json yields nothing instead of throwing`() {
        val calls = CloudFallbackToolParser.parse("""<tool_call>{"name": "get_weather", "arguments": {"location": </function_call>""")
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `empty and blank input yields nothing`() {
        assertTrue(CloudFallbackToolParser.parse("").isEmpty())
        assertTrue(CloudFallbackToolParser.parse("   \n ").isEmpty())
    }

    @Test
    fun `stripToolSyntax removes the raw call from visible text`() {
        val text = """
            Checking the weather for you.
            <tool_call>{"name":"get_weather","arguments":{"location":"Berlin"}}</tool_call>
            One moment please.
        """.trimIndent()
        val cleaned = CloudFallbackToolParser.stripToolSyntax(text)
        assertFalse(cleaned.contains("tool_call"))
        assertFalse(cleaned.contains("get_weather"))
        assertTrue(cleaned.contains("Checking the weather"))
        assertTrue(cleaned.contains("One moment please."))
    }

    @Test
    fun `stripToolSyntax leaves normal text untouched`() {
        val text = "Just a normal answer with no tools."
        assertEquals(text, CloudFallbackToolParser.stripToolSyntax(text))
    }

    @Test
    fun `containsToolSyntax detects embedded calls cheaply`() {
        assertTrue(CloudFallbackToolParser.containsToolSyntax("""{"name":"x","arguments":{}}"""))
        assertTrue(CloudFallbackToolParser.containsToolSyntax("blah <tool_call>{}"))
        assertFalse(CloudFallbackToolParser.containsToolSyntax("hello world"))
    }
}
