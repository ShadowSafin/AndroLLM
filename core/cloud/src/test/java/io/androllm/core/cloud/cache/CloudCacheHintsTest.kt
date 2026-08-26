package io.androllm.core.cloud.cache

import io.androllm.core.cloud.model.CloudChatMessage
import io.androllm.core.cloud.model.CloudChatRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Provider-aware cache hints: explicit cache_control for Anthropic-family
 * models, prefix stabilization for automatic prefix caching, and savings
 * estimation.
 */
class CloudCacheHintsTest {

    private fun request(vararg messages: CloudChatMessage) = CloudChatRequest(
        model = "openai/gpt-4o",
        messages = messages.toList()
    )

    @Test
    fun `anthropic models get cache_control on the last system message`() {
        val decorated = CloudCacheHints.decorate(
            request(
                CloudChatMessage("system", "You are helpful."),
                CloudChatMessage("system", "Use tools wisely."),
                CloudChatMessage("user", "hi")
            ),
            modelId = "anthropic/claude-3-5-sonnet"
        )
        val systems = decorated.messages.filter { it.role == "system" }
        assertNull(systems[0].cacheControl)
        assertNotNull(systems[1].cacheControl)
        assertEquals("ephemeral", systems[1].cacheControl!!.type)
        // User messages are never marked.
        assertNull(decorated.messages.last().cacheControl)
    }

    @Test
    fun `non-anthropic models get no cache_control markers`() {
        val decorated = CloudCacheHints.decorate(
            request(
                CloudChatMessage("system", "You are helpful."),
                CloudChatMessage("user", "hi")
            ),
            modelId = "openai/gpt-4o"
        )
        assertTrue(decorated.messages.all { it.cacheControl == null })
    }

    @Test
    fun `family detection covers common provider prefixes`() {
        assertTrue(CloudCacheHints.supportsExplicitCacheControl("anthropic/claude-3-5-sonnet"))
        assertTrue(CloudCacheHints.supportsExplicitCacheControl("claude-opus-4"))
        assertFalse(CloudCacheHints.supportsExplicitCacheControl("openai/gpt-4o"))
        assertFalse(CloudCacheHints.supportsExplicitCacheControl("gemini/gemini-1.5-pro"))

        assertTrue(CloudCacheHints.supportsPrefixCaching("openai/gpt-4o"))
        assertTrue(CloudCacheHints.supportsPrefixCaching("gemini/gemini-2.0-flash"))
        assertTrue(CloudCacheHints.supportsPrefixCaching("deepseek/deepseek-chat"))
        assertTrue(CloudCacheHints.supportsPrefixCaching("ollama/llama3.1"))
    }

    @Test
    fun `stabilizePrefix trims trailing whitespace on system messages`() {
        val stabilized = CloudCacheHints.stabilizePrefix(
            listOf(
                CloudChatMessage("system", "You are helpful.   \n\n"),
                CloudChatMessage("user", "hi  ")
            )
        )
        assertEquals("You are helpful.", stabilized[0].content)
        // User content is dynamic — never touched.
        assertEquals("hi  ", stabilized[1].content)
    }

    @Test
    fun `stabilizePrefix drops consecutive duplicate system messages`() {
        val stabilized = CloudCacheHints.stabilizePrefix(
            listOf(
                CloudChatMessage("system", "Use tools."),
                CloudChatMessage("system", "Use tools."),
                CloudChatMessage("user", "hi"),
                CloudChatMessage("system", "Use tools."),
                CloudChatMessage("user", "again")
            )
        )
        // The first duplicate right after the original is dropped; the later
        // one (after a user message) is kept — only CONSECUTIVE duplicates
        // are artifacts.
        assertEquals(4, stabilized.size)
        assertEquals("system", stabilized[0].role)
        assertEquals("user", stabilized[1].role)
        assertEquals("system", stabilized[2].role)
    }

    @Test
    fun `stabilizePrefix is idempotent`() {
        val messages = listOf(
            CloudChatMessage("system", "A"),
            CloudChatMessage("system", "A"),
            CloudChatMessage("user", "q")
        )
        val once = CloudCacheHints.stabilizePrefix(messages)
        val twice = CloudCacheHints.stabilizePrefix(once)
        assertEquals(once, twice)
    }

    @Test
    fun `latency savings scale with cached tokens`() {
        assertEquals(0L, CloudCacheHints.estimateLatencySavedMs(0))
        val small = CloudCacheHints.estimateLatencySavedMs(100)
        val large = CloudCacheHints.estimateLatencySavedMs(1000)
        assertTrue(large > small)
    }

    @Test
    fun `savings estimate bundles tokens latency and cost`() {
        val estimate = CloudCacheHints.estimateSavings("openai/gpt-4o", savedTokens = 1000)
        assertEquals(1000, estimate.savedTokens)
        assertTrue(estimate.latencySavedMs > 0)
        assertTrue(estimate.costSavedMicros > 0)
    }
}
