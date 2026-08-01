package io.androllm.core.database

import io.androllm.core.models.AppSettings
import io.androllm.core.models.Conversation
import io.androllm.core.models.Message
import io.androllm.core.models.MessageRole
import io.androllm.core.models.Model
import io.androllm.core.models.ModelFormat
import io.androllm.core.models.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Tests for the entity-to-domain mappings.
 */
class EntityMappingTest {

    @Test
    fun `conversation maps to entity and back`() {
        val conversation = Conversation(
            id = "conv-1",
            title = "Hello",
            createdAt = 1000L,
            updatedAt = 2000L,
            lastMessagePreview = "Hi there",
            messageCount = 2
        )
        val entity = conversation.toEntity()
        val roundTrip = entity.toDomain()
        assertEquals(conversation, roundTrip)
        assertEquals("conv-1", entity.id)
    }

    @Test
    fun `message maps to entity and back`() {
        val message = Message(
            id = "msg-1",
            conversationId = "conv-1",
            role = MessageRole.ASSISTANT,
            content = "Hello!",
            timestamp = 3000L,
            isPending = false,
            modelId = "model-1"
        )
        val entity = message.toEntity()
        val roundTrip = entity.toDomain()
        assertEquals(message, roundTrip)
        assertEquals("ASSISTANT", entity.role)
    }

    @Test
    fun `model maps to entity and back`() {
        val model = Model(
            id = "model-1",
            name = "Test Model",
            format = ModelFormat.GGUF,
            contextLength = 8192
        )
        val entity = model.toEntity()
        val roundTrip = entity.toDomain()
        assertEquals(model, roundTrip)
        assertEquals("GGUF", entity.format)
    }

    @Test
    fun `settings map to entity and back`() {
        val settings = AppSettings(
            theme = ThemeMode.DARK,
            language = "en",
            storagePath = "/storage",
            developerMode = true,
            firstLaunch = false,
            modelPath = null
        )
        val entity = settings.toEntity()
        val roundTrip = entity.toDomain()
        assertEquals(settings, roundTrip)
        assertFalse(entity.firstLaunch)
        assertEquals("DARK", entity.theme)
    }
}
