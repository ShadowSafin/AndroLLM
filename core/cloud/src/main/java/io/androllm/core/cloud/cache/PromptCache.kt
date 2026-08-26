package io.androllm.core.cloud.cache

import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import timber.log.Timber

/**
 * Prompt cache layer for cloud requests.
 *
 * Caches *stable, reusable* prompt content only — system prompts, tool
 * schemas, conversation headers and static templates — keyed by
 * provider + model + content fingerprint. User-private dynamic content is
 * never cached.
 *
 * The cache does not store completions: it remembers which stable prefixes
 * were already sent so the pipeline can (a) keep the prefix byte-stable for
 * providers with automatic prefix caching, (b) attach explicit
 * `cache_control` markers for providers that honor them (Anthropic via
 * LiteLLM), and (c) report realistic hit/miss/savings diagnostics.
 *
 * Reliability: every disk operation is defensive — a corrupted cache file is
 * quarantined and counted as a [CacheInvalidationReason.CORRUPTED]
 * invalidation, never thrown into the request pipeline.
 */
class PromptCache(
    private val diskFile: File? = null,
    private val maxEntries: Int = 128,
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {

    companion object {
        /** Entries unused for a week expire (system prompts tend to be stable). */
        val DEFAULT_TTL_MS = 7L * 24 * 3600 * 1000

        /** Rough chars-per-token ratio for saved-token estimates. */
        const val CHARS_PER_TOKEN = 4

        /** Key prefixes keep namespaces separate and debuggable. */
        const val KEY_SYSTEM = "system"
        const val KEY_TOOLS = "tools"
        const val KEY_PREFIX = "prefix"
        const val KEY_TEMPLATE = "template"

        /** Builds a namespaced cache key: `kind:providerId:modelId:hash`. */
        fun key(kind: String, providerId: String, modelId: String, hash: String): String =
            "$kind:$providerId:$modelId:$hash"
    }

    private val lock = Any()
    private var entries = LinkedHashMap<String, PromptCacheEntry>()
    private var currentStats = PromptCacheStats()
    private var loaded = diskFile == null
    private val diskMutex = Mutex()

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val _stats = MutableStateFlow(PromptCacheStats())
    /** Live cache diagnostics for the dashboard. */
    val stats: StateFlow<PromptCacheStats> = _stats.asStateFlow()

    // ── Loading / persistence ─────────────────────────────────────────────

    /** Loads the disk cache (if configured). Idempotent and never throws. */
    suspend fun init() {
        if (loaded) return
        val file = diskFile ?: run { loaded = true; return }
        withContext(Dispatchers.IO) {
            diskMutex.withLock {
                val state = runCatching {
                    if (file.exists() && file.length() > 0) {
                        json.decodeFromString(PromptCacheDiskState.serializer(), file.readText(Charsets.UTF_8))
                    } else null
                }.getOrElse { e ->
                    Timber.w(e, "PromptCache: disk cache unreadable — quarantining and starting fresh")
                    runCatching { file.renameTo(File(file.parentFile, file.name + ".corrupt")) }
                    null
                }
                synchronized(lock) {
                    if (state != null) {
                        entries = LinkedHashMap(state.entries.associateBy { it.key })
                        currentStats = state.stats
                        expireLocked(now = clock(), countCorruption = false)
                    } else if (file.exists()) {
                        // File existed but could not be parsed → corruption invalidation.
                        currentStats = currentStats.copy(
                            invalidations = currentStats.invalidations + 1,
                            lastInvalidationReason = CacheInvalidationReason.CORRUPTED.name
                        )
                    }
                    loaded = true
                    publishStatsLocked()
                }
            }
        }
        Timber.d("PromptCache: initialized with ${entries.size} entries")
    }

    /** Persists the cache to disk (best effort). Call on backgrounding. */
    suspend fun flush() {
        val file = diskFile ?: return
        val state = synchronized(lock) {
            PromptCacheDiskState(entries = entries.values.toList(), stats = currentStats)
        }
        withContext(Dispatchers.IO) {
            diskMutex.withLock {
                runCatching {
                    val parent = file.parentFile
                    if (parent != null && !parent.exists()) parent.mkdirs()
                    val tmp = File(file.parentFile, file.name + ".tmp")
                    tmp.writeText(json.encodeToString(PromptCacheDiskState.serializer(), state), Charsets.UTF_8)
                    if (!tmp.renameTo(file)) {
                        file.writeText(tmp.readText(Charsets.UTF_8), Charsets.UTF_8)
                        tmp.delete()
                    }
                }.onFailure { e -> Timber.w(e, "PromptCache: flush failed") }
            }
        }
    }

    // ── Hashing / estimation ──────────────────────────────────────────────

    /** SHA-256 fingerprint of stable content parts (null parts are ignored). */
    fun fingerprint(vararg parts: String?): String {
        val digest = MessageDigest.getInstance("SHA-256")
        for (part in parts) {
            if (part == null) continue
            digest.update(part.toByteArray(Charsets.UTF_8))
            digest.update(0x1F) // unit separator — distinct part boundaries
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Cheap token estimate for savings diagnostics (~4 chars/token). */
    fun estimateTokens(text: String): Int =
        if (text.isEmpty()) 0 else (text.length + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN

    // ── Probe / record ────────────────────────────────────────────────────

    /**
     * Looks up a cached stable prefix. Returns the entry on a hit (and
     * refreshes its last-used timestamp) or null on a miss. Callers follow up
     * with [noteHit] (with savings estimates) or [noteMiss] exactly once.
     */
    fun probe(key: String): PromptCacheEntry? {
        ensureLoaded()
        synchronized(lock) {
            val entry = entries[key] ?: return null
            if (clock() - entry.lastUsedAtMs > ttlMs) {
                entries.remove(key)
                publishStatsLocked()
                return null
            }
            val refreshed = entry.copy(lastUsedAtMs = clock())
            entries[key] = refreshed
            return refreshed
        }
    }

    /** Records a cache hit plus the estimated savings it produced. */
    fun noteHit(key: String, savedTokens: Int, latencySavedMs: Long, costSavedMicros: Long) {
        synchronized(lock) {
            entries[key]?.let { entries[key] = it.copy(hits = it.hits + 1) }
            currentStats = currentStats.copy(
                hits = currentStats.hits + 1,
                savedTokens = currentStats.savedTokens + savedTokens,
                estimatedLatencySavedMs = currentStats.estimatedLatencySavedMs + latencySavedMs,
                estimatedCostSavedMicros = currentStats.estimatedCostSavedMicros + costSavedMicros,
                updatedAtMs = clock()
            )
            publishStatsLocked()
        }
        Timber.d("PromptCache: HIT %s (saved ~%d tokens, ~%dms)", key, savedTokens, latencySavedMs)
    }

    /** Records a cache miss. */
    fun noteMiss(key: String) {
        synchronized(lock) {
            currentStats = currentStats.copy(
                misses = currentStats.misses + 1,
                updatedAtMs = clock()
            )
            publishStatsLocked()
        }
        Timber.d("PromptCache: MISS %s", key)
    }

    /** Stores or refreshes an entry (LRU-evicts beyond [maxEntries]). */
    fun put(entry: PromptCacheEntry) {
        ensureLoaded()
        synchronized(lock) {
            entries.remove(entry.key) // re-insert at the end (LRU order)
            entries[entry.key] = entry
            var evicted = 0
            while (entries.size > maxEntries) {
                val oldest = entries.keys.firstOrNull() ?: break
                entries.remove(oldest)
                evicted++
            }
            if (evicted > 0) {
                currentStats = currentStats.copy(evictions = currentStats.evictions + evicted)
            }
            publishStatsLocked()
        }
    }

    // ── Invalidation ──────────────────────────────────────────────────────

    /**
     * Invalidates entries matching [predicate] and records the reason.
     * Returns the number of entries removed.
     */
    fun invalidateWhere(reason: CacheInvalidationReason, predicate: (PromptCacheEntry) -> Boolean): Int {
        ensureLoaded()
        synchronized(lock) {
            val doomed = entries.values.filter(predicate).map { it.key }
            if (doomed.isEmpty()) return 0
            doomed.forEach { entries.remove(it) }
            currentStats = currentStats.copy(
                invalidations = currentStats.invalidations + doomed.size,
                lastInvalidationReason = reason.name,
                updatedAtMs = clock()
            )
            publishStatsLocked()
            Timber.i("PromptCache: invalidated ${doomed.size} entries (${reason.displayName})")
            return doomed.size
        }
    }

    /** Invalidates every entry (model/provider switch, manual clear...). */
    fun invalidateAll(reason: CacheInvalidationReason): Int =
        invalidateWhere(reason) { true }

    /** Drops entries AND resets diagnostics (dashboard "clear" action). */
    fun clear() {
        synchronized(lock) {
            entries.clear()
            currentStats = PromptCacheStats(updatedAtMs = clock())
            publishStatsLocked()
        }
        Timber.i("PromptCache: cleared")
    }

    /** Current entries (for diagnostics screens), LRU order. */
    fun entries(): List<PromptCacheEntry> = synchronized(lock) { entries.values.toList() }

    /** Number of cached entries. */
    fun size(): Int = synchronized(lock) { entries.size }

    // ── Internals ─────────────────────────────────────────────────────────

    private fun ensureLoaded() {
        // In-memory use without init() must still work (tests, sync callers).
        if (!loaded) {
            loaded = true
        }
    }

    private fun expireLocked(now: Long, countCorruption: Boolean) {
        val doomed = entries.values.filter { now - it.lastUsedAtMs > ttlMs }.map { it.key }
        if (doomed.isNotEmpty()) {
            doomed.forEach { entries.remove(it) }
            currentStats = currentStats.copy(
                invalidations = currentStats.invalidations + doomed.size,
                lastInvalidationReason = CacheInvalidationReason.EXPIRED.name
            )
        }
    }

    private fun publishStatsLocked() {
        _stats.value = currentStats.copy(entries = entries.size)
    }
}
