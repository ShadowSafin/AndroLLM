package io.androllm.core.memory.context

import io.androllm.core.memory.MemoryCategory
import io.androllm.core.memory.model.Memory
import io.androllm.core.memory.model.MemorySearchResult
import io.androllm.core.memory.model.MemorySummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextBuilderTest {

    private val builder = ContextBuilder()

    private fun memory(content: String) = Memory(
        id = "m1",
        category = MemoryCategory.PREFERENCES,
        content = content,
        importance = 3,
        createdAt = 0L,
        updatedAt = 0L
    )

    private fun summary(text: String) = MemorySummary(
        id = "s1",
        conversationId = "c1",
        summary = text,
        messageCount = 10,
        createdAt = 0L,
        updatedAt = 0L
    )

    @Test
    fun `builds text with memories and summaries`() {
        val text = builder.buildSystemText(
            memories = listOf(MemorySearchResult(memory("User prefers Kotlin"), 0.91f)),
            summaries = listOf(summary("Working on a weather app")),
            maxMemories = 5,
            maxSummaries = 2
        )
        assertTrue(text.contains("Relevant memories"))
        assertTrue(text.contains("User prefers Kotlin"))
        assertTrue(text.contains("PREFERENCES"))
        assertTrue(text.contains("Conversation summaries"))
        assertTrue(text.contains("weather app"))
    }

    @Test
    fun `respects max limits`() {
        val memories = (1..10).map { MemorySearchResult(memory("M$it"), 0.5f + it / 100f) }
        val text = builder.buildSystemText(memories, emptyList(), maxMemories = 3, maxSummaries = 0)
        assertFalse(text.contains("M4"))
        assertTrue(text.contains("M1"))
    }

    @Test
    fun `returns empty when nothing to inject`() {
        assertEquals("", builder.buildSystemText(emptyList(), emptyList(), 5, 2))
    }
}
