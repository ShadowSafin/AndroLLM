package io.androllm.core.cloud.cache

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Prompt cache behavior: reuse, hit/miss accounting, invalidation reasons,
 * TTL expiry, LRU eviction, fingerprint stability and savings tracking.
 */
class PromptCacheTest {

    private var now = 1_700_000_000_000L
    private lateinit var cache: PromptCache

    @Before
    fun setUp() {
        cache = PromptCache(diskFile = null, maxEntries = 4, ttlMs = 60_000, clock = { now })
    }

    private fun entry(
        key: String,
        provider: String = "p1",
        model: String = "m1",
        tokens: Int = 500,
        kind: PromptCacheContentKind = PromptCacheContentKind.CHAT_PREFIX,
        conversation: String = ""
    ) = PromptCacheEntry(
        key = key,
        fingerprint = cache.fingerprint(key),
        providerId = provider,
        modelId = model,
        kind = kind,
        estimatedTokens = tokens,
        contentChars = tokens * 4,
        createdAtMs = now,
        lastUsedAtMs = now,
        conversationId = conversation
    )

    @Test
    fun `miss then put then hit`() {
        val key = PromptCache.key(PromptCache.KEY_PREFIX, "p1", "m1", "abc")
        assertNull(cache.probe(key))
        cache.noteMiss(key)
        cache.put(entry(key))

        val hit = cache.probe(key)
        assertNotNull(hit)
        cache.noteHit(key, savedTokens = 500, latencySavedMs = 200, costSavedMicros = 100)

        val stats = cache.stats.value
        assertEquals(1, stats.hits)
        assertEquals(1, stats.misses)
        assertEquals(500L, stats.savedTokens)
        assertEquals(200L, stats.estimatedLatencySavedMs)
        assertEquals(100L, stats.estimatedCostSavedMicros)
        assertEquals(0.5f, stats.hitRate, 0.001f)
    }

    @Test
    fun `hit increments entry hit counter`() {
        val key = PromptCache.key(PromptCache.KEY_PREFIX, "p1", "m1", "abc")
        cache.put(entry(key))
        cache.probe(key)
        cache.noteHit(key, 500, 200, 100)

        assertEquals(1, cache.entries().first { it.key == key }.hits)
    }

    @Test
    fun `invalidation removes matching entries and records reason`() {
        cache.put(entry("k1", provider = "p1", model = "m1", kind = PromptCacheContentKind.SYSTEM_PROMPT))
        cache.put(entry("k2", provider = "p1", model = "m1", kind = PromptCacheContentKind.TOOL_SCHEMA))
        cache.put(entry("k3", provider = "p2", model = "m1", kind = PromptCacheContentKind.SYSTEM_PROMPT))

        val removed = cache.invalidateWhere(CacheInvalidationReason.SYSTEM_PROMPT_CHANGED) {
            it.providerId == "p1" && it.kind == PromptCacheContentKind.SYSTEM_PROMPT
        }
        assertEquals(1, removed)
        assertNull(cache.probe("k1"))
        assertNotNull(cache.probe("k2"))
        assertNotNull(cache.probe("k3"))
        assertEquals(
            CacheInvalidationReason.SYSTEM_PROMPT_CHANGED.name,
            cache.stats.value.lastInvalidationReason
        )
        assertEquals(1, cache.stats.value.invalidations)
    }

    @Test
    fun `invalidateAll clears everything with reason`() {
        cache.put(entry("k1"))
        cache.put(entry("k2"))
        val removed = cache.invalidateAll(CacheInvalidationReason.PROVIDER_CHANGED)
        assertEquals(2, removed)
        assertEquals(0, cache.size())
        assertEquals(CacheInvalidationReason.PROVIDER_CHANGED.name, cache.stats.value.lastInvalidationReason)
    }

    @Test
    fun `entries expire after ttl`() {
        cache.put(entry("k1"))
        assertNotNull(cache.probe("k1"))
        now += 120_000 // beyond the 60s TTL
        assertNull(cache.probe("k1"))
    }

    @Test
    fun `lru eviction keeps the newest entries`() {
        cache.put(entry("k1"))
        cache.put(entry("k2"))
        cache.put(entry("k3"))
        cache.put(entry("k4"))
        cache.put(entry("k5")) // evicts k1 (maxEntries = 4)

        assertNull(cache.probe("k1"))
        assertNotNull(cache.probe("k2"))
        assertNotNull(cache.probe("k5"))
        assertTrue(cache.stats.value.evictions >= 1)
    }

    @Test
    fun `fingerprint is stable for same content and distinct otherwise`() {
        val a = cache.fingerprint("system prompt", "tools schema")
        val b = cache.fingerprint("system prompt", "tools schema")
        val c = cache.fingerprint("system prompt CHANGED", "tools schema")
        val d = cache.fingerprint("system prompt", "tools schema CHANGED")
        assertEquals(a, b)
        assertNotEquals(a, c)
        assertNotEquals(a, d)
    }

    @Test
    fun `fingerprint respects part boundaries`() {
        // "ab" + "c" must differ from "a" + "bc".
        assertNotEquals(cache.fingerprint("ab", "c"), cache.fingerprint("a", "bc"))
    }

    @Test
    fun `token estimation approximates 4 chars per token`() {
        assertEquals(0, cache.estimateTokens(""))
        assertEquals(1, cache.estimateTokens("abc"))
        assertEquals(1, cache.estimateTokens("abcd"))
        assertEquals(2, cache.estimateTokens("abcde"))
        assertEquals(100, cache.estimateTokens("x".repeat(400)))
    }

    @Test
    fun `clear resets entries and diagnostics`() {
        cache.put(entry("k1"))
        cache.noteMiss("k1")
        cache.clear()
        assertEquals(0, cache.size())
        assertEquals(0, cache.stats.value.misses)
        assertEquals(0, cache.stats.value.lookups)
        assertFalse(cache.stats.value.hitRate > 0f)
    }
}
