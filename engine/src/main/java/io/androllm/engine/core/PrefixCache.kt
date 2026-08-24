package io.androllm.engine.core

import io.androllm.engine.models.BackendType
import java.security.MessageDigest

/**
 * LRU cache for prompt/prefix reuse across generation requests.
 *
 * The system prompt and chat template prefix are the same for every turn in a
 * conversation. This cache avoids re-encoding identical prefixes by storing
 * the raw prefix string keyed by a content hash of the stable prompt components.
 *
 * Key components:
 * - Model ID (different models have different tokenizations)
 * - Chat template hash (template changes change the prefix)
 * - System prompt content hash
 * - Backend type (NPU/GPU/CPU may tokenize differently)
 * - Conversation mode (chat vs plain generation)
 *
 * The cache does NOT store tokenized output (that lives in the LiteRT-LM
 * Conversation object's KV cache). It stores the raw prefix string so the
 * caller can detect "nothing changed" and skip unnecessary work.
 */
object PrefixCache {

    private const val MAX_CACHE_ENTRIES = 8

    /**
     * A cached prefix entry.
     */
    data class PrefixEntry(
        val key: String,
        val prefixText: String,
        val timestampMs: Long = System.currentTimeMillis(),
        /** Number of times this prefix was reused (for diagnostics). */
        var hitCount: Int = 0
    )

    /** Cache storage: key -> PrefixEntry. Access-ordered for LRU. */
    private val cache = LinkedHashMap<String, PrefixEntry>(MAX_CACHE_ENTRIES, 0.75f, true)

    /** Cache statistics for diagnostics. */
    @Volatile
    private var totalHits = 0L

    @Volatile
    private var totalMisses = 0L

    /**
     * Looks up a cached prefix. Returns the cached entry if the key matches,
     * null on miss (caller should build and cache the prefix).
     */
    fun get(
        modelId: String,
        templateHash: String,
        systemPromptHash: String,
        backend: BackendType,
        isChat: Boolean
    ): PrefixEntry? {
        val key = buildKey(modelId, templateHash, systemPromptHash, backend, isChat)
        val entry = synchronized(cache) {
            val e = cache[key]
            // Move to end (most recently used) if found
            if (e != null) {
                cache.remove(key)
                cache[key] = e
            }
            e
        }
        if (entry != null) {
            totalHits++
            entry.hitCount++
            return entry
        }
        totalMisses++
        return null
    }

    /**
     * Stores a prefix in the cache. Evicts the oldest entry when full.
     */
    fun put(
        modelId: String,
        templateHash: String,
        systemPromptHash: String,
        backend: BackendType,
        isChat: Boolean,
        prefixText: String
    ) {
        val key = buildKey(modelId, templateHash, systemPromptHash, backend, isChat)
        synchronized(cache) {
            // Remove existing entry at this key if present
            cache.remove(key)
            // Evict oldest if at capacity
            if (cache.size >= MAX_CACHE_ENTRIES) {
                val eldest = cache.keys.iterator().next()
                cache.remove(eldest)
            }
            cache[key] = PrefixEntry(key = key, prefixText = prefixText)
        }
    }

    /**
     * Invalidates the cache for a specific model (called on model switch).
     */
    fun invalidateModel(modelId: String) {
        synchronized(cache) {
            val keysToRemove = cache.keys.filter { it.startsWith("m:$modelId|") }
            keysToRemove.forEach { cache.remove(it) }
        }
    }

    /**
     * Invalidates the entire cache (called on context reset, backend change).
     */
    fun invalidateAll() {
        synchronized(cache) {
            cache.clear()
        }
        totalHits = 0
        totalMisses = 0
    }

    /**
     * Returns cache hit/miss statistics for diagnostics.
     */
    fun stats(): CacheStats {
        val entries = synchronized(cache) { cache.size }
        val hits = totalHits
        val misses = totalMisses
        return CacheStats(
            entries = entries,
            maxEntries = MAX_CACHE_ENTRIES,
            totalHits = hits,
            totalMisses = misses,
            hitRate = if (hits + misses > 0) hits.toFloat() / (hits + misses) else 0f
        )
    }

    /**
     * Hashes a system prompt for use as a cache key component.
     * Uses SHA-256 truncated to 8 hex chars for speed.
     */
    fun hashPrompt(prompt: String): String {
        if (prompt.isEmpty()) return "empty"
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(prompt.toByteArray(Charsets.UTF_8))
        return hash.take(4).joinToString("") { "%02x".format(it) }
    }

    private fun buildKey(
        modelId: String,
        templateHash: String,
        systemPromptHash: String,
        backend: BackendType,
        isChat: Boolean
    ): String = "m:$modelId|t:$templateHash|s:$systemPromptHash|b:${backend.name}|c:$isChat"

    data class CacheStats(
        val entries: Int,
        val maxEntries: Int,
        val totalHits: Long,
        val totalMisses: Long,
        val hitRate: Float
    ) {
        fun summary(): String = buildString {
            append("prefix_cache: $entries/$maxEntries entries, ")
            append("hit_rate=${ "%.1f".format(hitRate * 100)}% ")
            append("($totalHits hits, $totalMisses misses)")
        }
    }
}
