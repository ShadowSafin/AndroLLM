package io.androllm.core.tools.coordinator

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonObject
import java.util.LinkedHashMap

/**
 * Short-lived in-memory cache for PURE-READ tool results (web search,
 * weather, calculator, battery). The LLM may legitimately re-request the
 * exact same call with the exact same arguments in a turn (e.g. a
 * regenerated answer re-running a web search); instead of re-hitting the
 * network we replay the previous complete output.
 *
 * Safety rules:
 * - Only populated through [io.androllm.core.tools.api.ToolSpec.cacheable]
 *   tools (never side-effecting tools like SMS/calls).
 * - Keyed by tool name + canonical argument JSON, so an identical call is
 *   required to hit.
 * - Entries expire after [ttlMs] so results do not go stale.
 * - Bounded to [maxEntries] (oldest evicted) so memory stays flat.
 */
@Singleton
class ToolResultCache @Inject constructor() {

    /** Result freshness window — older entries are never replayed. */
    private val ttlMs: Long = DEFAULT_TTL_MS

    /** Bound on live entries — oldest evicted first. */
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES

    private data class Entry(
        val summary: String,
        val data: JsonObject?,
        val timestamp: Long
    )

    private val map = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean =
            size > maxEntries
    }

    /** Stable cache key from a tool call (name + canonical args JSON). */
    fun key(name: String, arguments: JsonObject): String {
        val argsJson = arguments.toString()
        return "$name|$argsJson"
    }

    /**
     * Returns the cached summary for [key] when present and unexpired, along
     * with its structured data (may be null). Null on miss.
     */
    @Synchronized
    fun get(key: String): Pair<String, JsonObject?>? {
        val entry = map[key] ?: return null
        if (System.currentTimeMillis() - entry.timestamp > ttlMs) {
            map.remove(key)
            return null
        }
        return entry.summary to entry.data
    }

    /** Stores [summary] (and optional structured [data]) for [key]. */
    @Synchronized
    fun put(key: String, summary: String, data: JsonObject? = null) {
        map[key] = Entry(summary, data, System.currentTimeMillis())
    }

    /** Drops every entry (used when settings change). */
    @Synchronized
    fun clear() = map.clear()

    /** Number of live entries (diagnostics). */
    @Synchronized
    fun size(): Int = map.size

    companion object {
        /** Results older than this are never replayed (stale-data guard). */
        private const val DEFAULT_TTL_MS = 10 * 60_000L

        /** Default bound on live entries. */
        private const val DEFAULT_MAX_ENTRIES = 32
    }
}
