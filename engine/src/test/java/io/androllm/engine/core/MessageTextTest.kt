package io.androllm.engine.core

import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Message
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [MessageText] — the extraction layer that guarantees ONLY plain
 * text content of a LiteRT-LM [Message] can reach the stream/UI. This is the
 * fix for responses rendering as raw JSON
 * (`[{"type":"text","text":"Hello"}]` instead of `Hello`).
 */
class MessageTextTest {

    private fun message(vararg contents: Content): Message =
        Message.of(contents.toList())

    @Test
    fun `extracts plain text from a text-only message`() {
        val m = message(Content.Text("Hello"))
        assertEquals("Hello", MessageText.from(m))
        assertEquals("Hello", MessageText.extract(m))
    }

    @Test
    fun `concatenates multiple text parts without JSON syntax`() {
        val m = message(Content.Text("Hello"), Content.Text(" world"))
        val extracted = MessageText.extract(m)
        assertEquals("Hello world", extracted)
        // Regression guard: toString() renders the JSON array — the extractor
        // must never return that shape.
        assertTrue(!extracted.contains("["))
        assertTrue(!extracted.contains("\"type\""))
    }

    @Test
    fun `drops tool response and binary parts`() {
        val m = message(
            Content.Text("Battery status: "),
            Content.ToolResponse("get_battery", mapOf("level" to 95)),
            Content.Text("95%")
        )
        val extracted = MessageText.extract(m)
        assertEquals("Battery status: 95%", extracted)
        assertTrue(!extracted.contains("ToolResponse"))
        assertTrue(!extracted.contains("get_battery"))
    }

    @Test
    fun `empty message extracts to empty string`() {
        assertEquals("", MessageText.extract(message()))
    }

    // ------------------------------------------------- serialized fragments

    @Test
    fun `flattens a serialized contents array`() {
        val fragment = """[{"type":"text","text":"Hello"}]"""
        assertEquals("Hello", MessageText.cleanSerialized(fragment))
    }

    @Test
    fun `flattens a serialized multi-element array`() {
        val fragment = """[{"type":"text","text":"Hi"},{"type":"text","text":" there!"}]"""
        assertEquals("Hi there!", MessageText.cleanSerialized(fragment))
    }

    @Test
    fun `leaves ordinary prose untouched`() {
        val prose = "Here is a list [1, 2, 3] of items"
        assertEquals(prose, MessageText.cleanSerialized(prose))
    }

    @Test
    fun `leaves partial JSON untouched`() {
        assertEquals("""[{"type":"te""", MessageText.cleanSerialized("""[{"type":"te"""))
    }

    @Test
    fun `decodes escaped characters in the text field`() {
        val fragment = """[{"type":"text","text":"line1\nline2 \"quoted\""}]"""
        assertEquals("line1\nline2 \"quoted\"", MessageText.cleanSerialized(fragment))
    }
}
