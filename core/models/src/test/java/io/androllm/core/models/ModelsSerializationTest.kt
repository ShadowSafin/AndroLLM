package io.androllm.core.models

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for model serialization.
 */
class ModelsSerializationTest {

    private val json = Json { encodeDefaults = true }

    @Test
    fun `model serializes and deserializes`() {
        val model = Model(
            id = "model-1",
            name = "Llama 3 8B",
            format = ModelFormat.GGUF,
            quantization = "q4_k_m",
            isDownloaded = true
        )
        val encoded = json.encodeToString(Model.serializer(), model)
        val decoded = json.decodeFromString(Model.serializer(), encoded)
        assertEquals(model, decoded)
    }

    @Test
    fun `conversation serializes and deserializes`() {
        val conversation = Conversation(
            id = "conv-1",
            title = "Math help",
            createdAt = 1000L,
            updatedAt = 2000L,
            lastMessagePreview = "Sure!",
            messageCount = 5
        )
        val encoded = json.encodeToString(Conversation.serializer(), conversation)
        val decoded = json.decodeFromString(Conversation.serializer(), encoded)
        assertEquals(conversation, decoded)
    }

    @Test
    fun `message serializes and deserializes with enum`() {
        val message = Message(
            id = "msg-1",
            conversationId = "conv-1",
            role = MessageRole.USER,
            content = "What is 2+2?",
            timestamp = 1234L
        )
        val encoded = json.encodeToString(Message.serializer(), message)
        val decoded = json.decodeFromString(Message.serializer(), encoded)
        assertEquals(message, decoded)
        assertEquals("user", json.parseToJsonElement(encoded).jsonObject["role"]?.jsonPrimitive?.content)
    }
}
