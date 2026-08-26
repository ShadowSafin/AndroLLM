package io.androllm.core.cloud.cache

import kotlinx.serialization.Serializable

/** What kind of stable content a cache entry represents. */
enum class PromptCacheContentKind {
    SYSTEM_PROMPT,
    TOOL_SCHEMA,
    CHAT_PREFIX,
    TEMPLATE
}

/** Why a cache entry (or the whole cache) was invalidated. */
enum class CacheInvalidationReason(val displayName: String) {
    SYSTEM_PROMPT_CHANGED("System prompt changed"),
    TOOL_SCHEMA_CHANGED("Tool schema changed"),
    MODEL_CHANGED("Model changed"),
    PROVIDER_CHANGED("Provider changed"),
    CONVERSATION_RESET("Conversation reset"),
    PROMPT_STRUCTURE_CHANGED("Prompt structure changed"),
    EXPIRED("Entry expired"),
    CORRUPTED("Cache data corrupted"),
    MANUAL("Cleared manually")
}

/**
 * One cached stable-prompt entry. Entries never contain user-private dynamic
 * content — only system prompts, tool schemas, conversation headers and
 * static template fragments that repeat across requests.
 */
@Serializable
data class PromptCacheEntry(
    val key: String,
    /** Content fingerprint (SHA-256 of the stable content). */
    val fingerprint: String,
    val providerId: String,
    val modelId: String,
    val kind: PromptCacheContentKind,
    /** Estimated token size of the cached content (~4 chars/token). */
    val estimatedTokens: Int,
    /** Byte length of the cached content. */
    val contentChars: Int,
    val createdAtMs: Long,
    val lastUsedAtMs: Long,
    val hits: Int = 0,
    /** Optional conversation correlation (prefix entries only). */
    val conversationId: String = ""
)

/**
 * Prompt cache diagnostics — the numbers behind the dashboard's Cache
 * Performance section.
 */
@Serializable
data class PromptCacheStats(
    val hits: Int = 0,
    val misses: Int = 0,
    val invalidations: Int = 0,
    val evictions: Int = 0,
    /** Prompt tokens estimated saved by cache reuse. */
    val savedTokens: Long = 0,
    /** Estimated cost saved, in millionths of USD. */
    val estimatedCostSavedMicros: Long = 0,
    /** Estimated first-token latency saved (sum across hits). */
    val estimatedLatencySavedMs: Long = 0,
    val entries: Int = 0,
    val lastInvalidationReason: String = "",
    val updatedAtMs: Long = 0
) {
    val lookups: Int get() = hits + misses
    val hitRate: Float get() = if (lookups > 0) hits.toFloat() / lookups else 0f
    val avgLatencySavedPerHitMs: Long get() = if (hits > 0) estimatedLatencySavedMs / hits else 0
}

/** Persisted envelope for the disk cache. */
@Serializable
data class PromptCacheDiskState(
    val entries: List<PromptCacheEntry> = emptyList(),
    val stats: PromptCacheStats = PromptCacheStats()
)
