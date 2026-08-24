package io.androllm.engine.core

import io.androllm.engine.models.BackendType
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class PrefixCacheTest {

    @Before
    fun setUp() {
        PrefixCache.invalidateAll()
    }

    @Test
    fun `cache miss on empty cache`() {
        val result = PrefixCache.get(
            modelId = "model1",
            templateHash = "t1",
            systemPromptHash = "s1",
            backend = BackendType.CPU,
            isChat = true
        )
        assertNull(result)
    }

    @Test
    fun `cache hit after put`() {
        PrefixCache.put(
            modelId = "model1",
            templateHash = "t1",
            systemPromptHash = "s1",
            backend = BackendType.CPU,
            isChat = true,
            prefixText = "system prompt"
        )
        val result = PrefixCache.get(
            modelId = "model1",
            templateHash = "t1",
            systemPromptHash = "s1",
            backend = BackendType.CPU,
            isChat = true
        )
        assertNotNull(result)
        assertEquals("system prompt", result!!.prefixText)
    }

    @Test
    fun `cache miss on different model`() {
        PrefixCache.put(
            modelId = "model1",
            templateHash = "t1",
            systemPromptHash = "s1",
            backend = BackendType.CPU,
            isChat = true,
            prefixText = "prompt"
        )
        val result = PrefixCache.get(
            modelId = "model2",
            templateHash = "t1",
            systemPromptHash = "s1",
            backend = BackendType.CPU,
            isChat = true
        )
        assertNull(result)
    }

    @Test
    fun `cache miss on different backend`() {
        PrefixCache.put(
            modelId = "model1",
            templateHash = "t1",
            systemPromptHash = "s1",
            backend = BackendType.CPU,
            isChat = true,
            prefixText = "prompt"
        )
        val result = PrefixCache.get(
            modelId = "model1",
            templateHash = "t1",
            systemPromptHash = "s1",
            backend = BackendType.GPU,
            isChat = true
        )
        assertNull(result)
    }

    @Test
    fun `invalidateAll clears cache`() {
        PrefixCache.put(
            modelId = "m1", templateHash = "t", systemPromptHash = "s",
            backend = BackendType.CPU, isChat = true, prefixText = "x"
        )
        PrefixCache.invalidateAll()
        val result = PrefixCache.get(
            modelId = "m1", templateHash = "t", systemPromptHash = "s",
            backend = BackendType.CPU, isChat = true
        )
        assertNull(result)
    }

    @Test
    fun `invalidateModel clears only that model`() {
        PrefixCache.put(
            modelId = "m1", templateHash = "t", systemPromptHash = "s",
            backend = BackendType.CPU, isChat = true, prefixText = "x"
        )
        PrefixCache.put(
            modelId = "m2", templateHash = "t", systemPromptHash = "s",
            backend = BackendType.CPU, isChat = true, prefixText = "y"
        )
        PrefixCache.invalidateModel("m1")
        assertNull(PrefixCache.get(
            modelId = "m1", templateHash = "t", systemPromptHash = "s",
            backend = BackendType.CPU, isChat = true
        ))
        assertNotNull(PrefixCache.get(
            modelId = "m2", templateHash = "t", systemPromptHash = "s",
            backend = BackendType.CPU, isChat = true
        ))
    }

    @Test
    fun `stats tracks hits and misses`() {
        PrefixCache.put(
            modelId = "m", templateHash = "t", systemPromptHash = "s",
            backend = BackendType.CPU, isChat = true, prefixText = "x"
        )
        PrefixCache.get(modelId = "m", templateHash = "t", systemPromptHash = "s",
            backend = BackendType.CPU, isChat = true) // hit
        PrefixCache.get(modelId = "m", templateHash = "t", systemPromptHash = "s",
            backend = BackendType.CPU, isChat = true) // hit
        PrefixCache.get(modelId = "m", templateHash = "t", systemPromptHash = "x",
            backend = BackendType.CPU, isChat = true) // miss
        val stats = PrefixCache.stats()
        assertEquals(2L, stats.totalHits)
        assertEquals(1L, stats.totalMisses)
        assertTrue(stats.hitRate > 0.6f)
    }

    @Test
    fun `hashPrompt is deterministic`() {
        val h1 = PrefixCache.hashPrompt("hello world")
        val h2 = PrefixCache.hashPrompt("hello world")
        assertEquals(h1, h2)
    }

    @Test
    fun `hashPrompt differs for different inputs`() {
        val h1 = PrefixCache.hashPrompt("hello")
        val h2 = PrefixCache.hashPrompt("world")
        assertNotEquals(h1, h2)
    }

    @Test
    fun `hashPrompt empty returns sentinel`() {
        assertEquals("empty", PrefixCache.hashPrompt(""))
    }
}
