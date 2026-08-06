package io.androllm.core.cloud.network

import io.androllm.core.cloud.model.CloudStreamEvent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingParserTest {

    /** Turns a fixed list of lines into a suspend line provider. */
    private fun linesOf(lines: List<String>): suspend () -> String? {
        val iterator = lines.iterator()
        return { if (iterator.hasNext()) iterator.next() else null }
    }

    @Test
    fun `parses single content delta`() {
        val payload = """{"choices":[{"delta":{"content":"Hello"}}]}"""
        val parsed = StreamingParser.parsePayload(payload)
        assertEquals(StreamingParser.Parsed.Content("Hello"), parsed)
    }

    @Test
    fun `parses multi-word deltas across events`() = runTest {
        val events = mutableListOf<CloudStreamEvent>()
        val lines = linesOf(
            listOf(
                """data: {"choices":[{"delta":{"content":"Hello"}}]}""",
                "",
                """data: {"choices":[{"delta":{"content":" world"}}]}""",
                "",
                "data: [DONE]",
                ""
            )
        )
        val keepGoing = StreamingParser.consumeLines(lines) { payload ->
            StreamingParser.toStreamEvent(payload)?.let { events.add(it) }
            StreamingParser.parsePayload(payload)
        }
        assertEquals(false, keepGoing)
        assertEquals(
            listOf(CloudStreamEvent.Delta("Hello"), CloudStreamEvent.Delta(" world"), CloudStreamEvent.Done),
            events
        )
    }

    @Test
    fun `parses usage from final chunk`() {
        val payload = """{"choices":[{"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":9,"completion_tokens":3,"total_tokens":12}}"""
        val parsed = StreamingParser.parsePayload(payload)
        assertTrue(parsed is StreamingParser.Parsed.UsageInfo)
        val usage = (parsed as StreamingParser.Parsed.UsageInfo).usage
        assertEquals(9L, usage.prompt_tokens)
        assertEquals(3L, usage.completion_tokens)
        assertEquals(12L, usage.total_tokens)
    }

    @Test
    fun `tolerates truncated or malformed JSON`() {
        assertNull(StreamingParser.toStreamEvent("""{"choices":[{"delta":{"con"""))
        assertNull(StreamingParser.toStreamEvent("not json at all"))
    }

    @Test
    fun `role-only deltas are ignored`() {
        val payload = """{"choices":[{"delta":{"role":"assistant"}}]}"""
        assertEquals(StreamingParser.Parsed.Ignored, StreamingParser.parsePayload(payload))
    }

    @Test
    fun `ignores comments keep-alives and event metadata`() = runTest {
        val events = mutableListOf<CloudStreamEvent>()
        val lines = linesOf(
            listOf(
                ": keep-alive",
                "event: message",
                """data: {"choices":[{"delta":{"content":"A"}}]}""",
                "",
                ": ping",
                "data: [DONE]",
                ""
            )
        )
        StreamingParser.consumeLines(lines) { payload ->
            StreamingParser.toStreamEvent(payload)?.let { events.add(it) }
            StreamingParser.parsePayload(payload)
        }
        assertEquals(
            listOf(CloudStreamEvent.Delta("A"), CloudStreamEvent.Done),
            events
        )
    }

    @Test
    fun `handles multiline data payloads`() {
        val payload = """{"choices":[{"delta":{"content":"line1\nline2"}}]}"""
        val parsed = StreamingParser.parsePayload(payload)
        assertEquals(StreamingParser.Parsed.Content("line1\nline2"), parsed)
    }

    @Test
    fun `flushes trailing event without closing blank line`() = runTest {
        val events = mutableListOf<CloudStreamEvent>()
        val lines = linesOf(
            listOf(
                """data: {"choices":[{"delta":{"content":"Trailing"}}]}"""
                // no blank line terminator
            )
        )
        val keepGoing = StreamingParser.consumeLines(lines) { payload ->
            StreamingParser.toStreamEvent(payload)?.let { events.add(it) }
            StreamingParser.parsePayload(payload)
        }
        assertTrue(keepGoing)
        assertEquals(listOf(CloudStreamEvent.Delta("Trailing")), events)
    }

    @Test
    fun `parses reasoning deltas`() {
        val payload = """{"choices":[{"delta":{"reasoning_content":"thinking step"}}]}"""
        val parsed = StreamingParser.parsePayload(payload)
        assertEquals(StreamingParser.Parsed.Reasoning("thinking step"), parsed)
        assertEquals(
            CloudStreamEvent.Reasoning("thinking step"),
            StreamingParser.toStreamEvent(payload)
        )
    }

    @Test
    fun `parses tool call deltas with fragmented arguments`() {
        val first = """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"get_weather","arguments":"{\"city\":"}}]}}]}"""
        val second = """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"Berlin\"}"}}]}}]}"""
        assertEquals(
            StreamingParser.Parsed.ToolCall(index = 0, id = "call_1", name = "get_weather", arguments = "{\"city\":"),
            StreamingParser.parsePayload(first)
        )
        assertEquals(
            StreamingParser.Parsed.ToolCall(index = 0, id = null, name = null, arguments = "\"Berlin\"}"),
            StreamingParser.parsePayload(second)
        )
    }

    @Test
    fun `content delta takes precedence over reasoning in same chunk`() {
        val payload = """{"choices":[{"delta":{"reasoning_content":"hidden","content":"visible"}}]}"""
        assertEquals(StreamingParser.Parsed.Content("visible"), StreamingParser.parsePayload(payload))
    }
}
