package io.androllm.feature.chat.export

import io.androllm.core.models.Message
import io.androllm.core.models.MessageRole
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationExporterTest {

    private val sampleMessages = listOf(
        Message(
            id = "1",
            conversationId = "conv1",
            role = MessageRole.USER,
            content = "Explain Kotlin coroutines",
            timestamp = 1600000000000L
        ),
        Message(
            id = "2",
            conversationId = "conv1",
            role = MessageRole.ASSISTANT,
            content = "Coroutines are light-weight threads for asynchronous programming.",
            timestamp = 1600000005000L
        )
    )

    @Test
    fun `export to Markdown contains title and formatting`() {
        val result = ConversationExporter.export("Test Chat", sampleMessages, ExportFormat.MARKDOWN)
        assertTrue(result.contains("# Test Chat"))
        assertTrue(result.contains("**User**"))
        assertTrue(result.contains("Explain Kotlin coroutines"))
        assertTrue(result.contains("**Assistant**"))
    }

    @Test
    fun `export to Plain Text contains raw content`() {
        val result = ConversationExporter.export("Test Chat", sampleMessages, ExportFormat.PLAIN_TEXT)
        assertTrue(result.contains("TEST CHAT"))
        assertTrue(result.contains("USER:"))
        assertTrue(result.contains("ASSISTANT:"))
    }

    @Test
    fun `export to JSON contains structured data`() {
        val result = ConversationExporter.export("Test Chat", sampleMessages, ExportFormat.JSON)
        assertTrue(result.contains("\"title\": \"Test Chat\""))
        assertTrue(result.contains("\"role\": \"user\""))
        assertTrue(result.contains("\"role\": \"assistant\""))
    }
}
