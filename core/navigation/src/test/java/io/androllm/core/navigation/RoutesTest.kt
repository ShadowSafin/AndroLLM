package io.androllm.core.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for route construction.
 */
class RoutesTest {

    @Test
    fun `chat detail route includes conversation id`() {
        assertEquals("chat/conv-123", Routes.chatDetail("conv-123"))
    }

    @Test
    fun `model detail route includes model id`() {
        assertEquals("models/model-456", Routes.modelDetail("model-456"))
    }

    @Test
    fun `route constants match argument names`() {
        assertEquals("conversationId", Routes.ARG_CONVERSATION_ID)
        assertEquals("modelId", Routes.ARG_MODEL_ID)
    }
}
