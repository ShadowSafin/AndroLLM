package io.androllm.engine

import io.androllm.engine.models.ChatPromptMessage
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract test for the Kotlin → native message-history handoff.
 *
 * The chat history is serialized with kotlinx.serialization before being sent to
 * the native engine (see [io.androllm.engine.jni.LlamaJniBridge.nativeApplyChatTemplate]).
 * kotlinx.serialization escapes control characters (newline, tab, CR) as JSON
 * escapes, so the native JSON parser MUST decode those escapes.
 *
 * Regression: the previous native parser dropped the backslash of `\n`/`\t`/`\r`,
 * turning every newline in the previous assistant response into a literal 'n'
 * when the history was re-rendered for the second prompt — the direct cause of
 * corrupted output on multi-turn conversations.
 */
class ChatPromptSerializationTest {

    // Same Json configuration used by the engine (RuntimeConfig.json)
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `kotlinx serialization escapes control characters in message content`() {
        val messages = listOf(
            ChatPromptMessage(role = "user", content = "Hello"),
            ChatPromptMessage(role = "assistant", content = "Line 1\nLine 2\tEnd\r\nDone \"quoted\""),
            ChatPromptMessage(role = "user", content = "What now?")
        )

        val encoded = json.encodeToString(
            ListSerializer(ChatPromptMessage.serializer()),
            messages
        )

        // The encoded JSON must contain escape sequences, not raw control chars
        assertTrue("newline must be escaped: $encoded", encoded.contains("\\n"))
        assertTrue("tab must be escaped: $encoded", encoded.contains("\\t"))
        assertTrue("CR must be escaped: $encoded", encoded.contains("\\r"))
        assertTrue("quote must be escaped: $encoded", encoded.contains("\\\""))
        assertTrue("must not contain raw control characters: $encoded",
            encoded.none { it.code < 0x20 })

        // A correct JSON parser must recover the original content byte-for-byte
        val decoded = json.decodeFromString(
            ListSerializer(ChatPromptMessage.serializer()),
            encoded
        )
        assertEquals(messages.size, decoded.size)
        assertEquals(messages[1].content, decoded[1].content)
        assertEquals("Line 1\nLine 2\tEnd\r\nDone \"quoted\"", decoded[1].content)
    }

    @Test
    fun `unicode content survives the serialization round trip`() {
        val messages = listOf(
            ChatPromptMessage(role = "assistant", content = "Café ☕ — 😀 and 中文")
        )
        val encoded = json.encodeToString(
            ListSerializer(ChatPromptMessage.serializer()),
            messages
        )
        val decoded = json.decodeFromString(
            ListSerializer(ChatPromptMessage.serializer()),
            encoded
        )
        assertEquals("Café ☕ — 😀 and 中文", decoded.first().content)
    }
}
