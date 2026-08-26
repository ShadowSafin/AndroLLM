package io.androllm.core.cloud.pipeline

import io.androllm.core.cloud.cache.CacheInvalidationReason
import io.androllm.core.cloud.cache.PromptCache
import io.androllm.core.cloud.cache.PromptCacheContentKind
import io.androllm.core.cloud.model.CloudChatMessage
import io.androllm.core.cloud.model.CloudChatRequest
import io.androllm.core.cloud.model.CloudTool
import io.androllm.core.cloud.model.CloudToolFunction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Request planning: validation pass-through, prompt-cache reuse across
 * turns, and invalidation when the system prompt, tool schema, model,
 * provider or conversation changes.
 */
class CloudRequestPlannerTest {

    private lateinit var cache: PromptCache
    private lateinit var planner: CloudRequestPlanner

    @Before
    fun setUp() {
        cache = PromptCache(diskFile = null)
        planner = CloudRequestPlanner(cache)
    }

    private fun request(
        system: String = "You are AndroLLM.",
        user: String = "hello",
        model: String = "openai/gpt-4o",
        tools: List<CloudTool> = emptyList()
    ) = CloudChatRequest(
        model = model,
        messages = listOf(
            CloudChatMessage("system", system),
            CloudChatMessage("user", user)
        ),
        tools = tools
    )

    @Test
    fun `first turn misses cache, second turn hits`() {
        val first = planner.plan(request(), providerId = "p1", conversationId = "conv1")
        assertNotNull(first.cacheLookup)
        assertFalse(first.cacheLookup!!.hit)

        val second = planner.plan(request(user = "second question"), providerId = "p1", conversationId = "conv1")
        assertTrue(second.cacheLookup!!.hit)
        assertTrue(second.cacheLookup!!.savedTokensEstimate > 0)
        assertEquals(1, cache.stats.value.hits)
        assertEquals(1, cache.stats.value.misses)
    }

    @Test
    fun `different user content does not break the stable prefix`() {
        planner.plan(request(user = "question A"), providerId = "p1", conversationId = "c")
        val hit = planner.plan(request(user = "completely different question B"), providerId = "p1", conversationId = "c")
        assertTrue(hit.cacheLookup!!.hit)
    }

    @Test
    fun `system prompt change invalidates and misses`() {
        planner.plan(request(system = "Version 1 of the system prompt"), providerId = "p1", conversationId = "c")
        val changed = planner.plan(
            request(system = "Version 2 of the system prompt"),
            providerId = "p1",
            conversationId = "c"
        )
        assertFalse(changed.cacheLookup!!.hit)
        assertEquals(
            CacheInvalidationReason.SYSTEM_PROMPT_CHANGED.name,
            cache.stats.value.lastInvalidationReason
        )
    }

    @Test
    fun `tool schema change invalidates tool entries`() {
        val toolsA = listOf(CloudTool(function = CloudToolFunction(name = "get_weather")))
        val toolsB = listOf(
            CloudTool(function = CloudToolFunction(name = "get_weather")),
            CloudTool(function = CloudToolFunction(name = "send_sms"))
        )
        cache.put(
            io.androllm.core.cloud.cache.PromptCacheEntry(
                key = "tools:p1:openai/gpt-4o:x",
                fingerprint = "x",
                providerId = "p1",
                modelId = "openai/gpt-4o",
                kind = PromptCacheContentKind.TOOL_SCHEMA,
                estimatedTokens = 10,
                contentChars = 40,
                createdAtMs = 0,
                lastUsedAtMs = 0
            )
        )
        planner.plan(request(tools = toolsA), providerId = "p1", conversationId = "c")
        planner.plan(request(tools = toolsB), providerId = "p1", conversationId = "c")
        assertEquals(
            CacheInvalidationReason.TOOL_SCHEMA_CHANGED.name,
            cache.stats.value.lastInvalidationReason
        )
        // The manually inserted tool-schema entry was invalidated.
        assertNull(cache.probe("tools:p1:openai/gpt-4o:x"))
    }

    @Test
    fun `model change invalidates conversation prefix entries`() {
        planner.plan(request(model = "openai/gpt-4o"), providerId = "p1", conversationId = "c")
        planner.plan(request(model = "openai/gpt-4o-mini"), providerId = "p1", conversationId = "c")
        assertEquals(
            CacheInvalidationReason.MODEL_CHANGED.name,
            cache.stats.value.lastInvalidationReason
        )
    }

    @Test
    fun `provider change invalidates conversation entries`() {
        planner.plan(request(), providerId = "p1", conversationId = "c")
        planner.plan(request(), providerId = "p2", conversationId = "c")
        assertEquals(
            CacheInvalidationReason.PROVIDER_CHANGED.name,
            cache.stats.value.lastInvalidationReason
        )
    }

    @Test
    fun `conversation reset clears its prefix memory`() {
        planner.plan(request(), providerId = "p1", conversationId = "conv-reset")
        planner.noteConversationReset("conv-reset")
        // After a reset the same content is a miss again (fresh context).
        val again = planner.plan(request(), providerId = "p1", conversationId = "conv-reset")
        assertFalse(again.cacheLookup!!.hit)
    }

    @Test
    fun `request without stable content skips cache lookup`() {
        val noSystem = CloudChatRequest(
            model = "openai/gpt-4o",
            messages = listOf(CloudChatMessage("user", "hi"))
        )
        val planned = planner.plan(noSystem, providerId = "p1")
        assertNull(planned.cacheLookup)
    }

    @Test
    fun `invalid request still plans with validation errors`() {
        val invalid = CloudChatRequest(model = "", messages = emptyList())
        val planned = planner.plan(invalid, providerId = "p1")
        assertFalse(planned.validation.valid)
        assertTrue(planned.validation.errors.isNotEmpty())
    }

    @Test
    fun `anthropic requests get cache_control decoration`() {
        val planned = planner.plan(
            request(model = "anthropic/claude-3-5-sonnet"),
            providerId = "p1"
        )
        val system = planned.request.messages.first { it.role == "system" }
        assertNotNull(system.cacheControl)
    }

    @Test
    fun `tool count is reported for the pipeline`() {
        val planned = planner.plan(
            request(tools = listOf(CloudTool(function = CloudToolFunction(name = "get_weather")))),
            providerId = "p1"
        )
        assertEquals(1, planned.toolCount)
    }
}
