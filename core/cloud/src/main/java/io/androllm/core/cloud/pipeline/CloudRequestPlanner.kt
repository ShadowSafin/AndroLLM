package io.androllm.core.cloud.pipeline

import io.androllm.core.cloud.cache.CacheInvalidationReason
import io.androllm.core.cloud.cache.CloudCacheHints
import io.androllm.core.cloud.cache.PromptCache
import io.androllm.core.cloud.cache.PromptCacheContentKind
import io.androllm.core.cloud.cache.PromptCacheEntry
import io.androllm.core.cloud.model.CloudChatRequest
import kotlinx.serialization.json.Json

/** Outcome of the prompt-cache lookup for one planned request. */
data class CacheLookupResult(
    val hit: Boolean,
    val key: String,
    val fingerprint: String,
    val savedTokensEstimate: Int,
    val latencySavedMs: Long,
    val costSavedMicros: Long
) {
    companion object {
        fun miss(key: String, fingerprint: String) =
            CacheLookupResult(false, key, fingerprint, 0, 0, 0)
    }
}

/**
 * A fully prepared cloud request: validated, cache-checked, decorated with
 * provider-appropriate cache hints, and annotated with the diagnostics the
 * usage meter needs after the round completes.
 */
data class PlannedCloudRequest(
    val request: CloudChatRequest,
    val validation: CloudRequestValidation,
    val cacheLookup: CacheLookupResult?,
    val toolCount: Int,
    val providerId: String,
    val modelId: String,
    val conversationId: String?
)

/**
 * The cloud request planner — stage 1 of the pipeline:
 *
 * ```
 * User request → Prompt assembly → [Cache lookup] → Tool planning →
 * Provider selection → Cloud request → ...
 * ```
 *
 * Responsibilities:
 * - validate the assembled request ([CloudRequestValidator])
 * - fingerprint the STABLE prefix (system messages + tool schemas) and look
 *   it up in the [PromptCache]
 * - detect invalidation conditions between turns of the same conversation
 *   (system prompt / tool schema / model / provider changes, resets)
 * - decorate the request with provider-aware cache hints
 *   ([CloudCacheHints]) so providers can reuse the prefix
 *
 * The planner never touches user-private dynamic content: only system
 * prompts, tool schemas and conversation headers are fingerprinted/cached.
 */
class CloudRequestPlanner(
    private val cache: PromptCache,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {

    private val json = Json { encodeDefaults = false }

    /** Per-conversation memory of the previous turn's stable prefix. */
    private data class ConversationPrefixMemory(
        val systemFingerprint: String,
        val toolsFingerprint: String,
        val providerId: String,
        val modelId: String
    )

    private val conversationMemory = HashMap<String, ConversationPrefixMemory>()

    /**
     * Plans one cloud request. Pure and fast — safe to call on the request
     * path. Never throws: any internal failure degrades to an unplanned
     * pass-through so the request still goes out.
     */
    fun plan(
        request: CloudChatRequest,
        providerId: String,
        conversationId: String? = null
    ): PlannedCloudRequest {
        val validation = runCatching { CloudRequestValidator.validate(request) }
            .getOrElse { CloudRequestValidation.OK }

        val cacheLookup = runCatching {
            planCache(request, providerId, conversationId)
        }.getOrElse { e ->
            CloudPipelineLogger.failure("cache lookup failed — continuing without cache", e)
            null
        }

        val decorated = runCatching {
            CloudCacheHints.decorate(request, request.model)
        }.getOrElse { request }

        CloudPipelineLogger.plan(
            "model=${request.model} provider=$providerId tools=${request.tools.size} " +
                "messages=${request.messages.size} cache=${if (cacheLookup?.hit == true) "HIT" else "MISS"} " +
                "valid=${validation.valid}"
        )

        return PlannedCloudRequest(
            request = decorated,
            validation = validation,
            cacheLookup = cacheLookup,
            toolCount = request.tools.size,
            providerId = providerId,
            modelId = request.model,
            conversationId = conversationId
        )
    }

    /**
     * Tells the cache that a conversation was reset (new chat): its prefix
     * entries no longer represent a reusable context.
     */
    fun noteConversationReset(conversationId: String) {
        runCatching {
            val memory = conversationMemory.remove(conversationId) ?: return
            cache.invalidateWhere(CacheInvalidationReason.CONVERSATION_RESET) {
                it.conversationId == conversationId ||
                    (it.providerId == memory.providerId && it.modelId == memory.modelId &&
                        it.kind == PromptCacheContentKind.CHAT_PREFIX)
            }
            CloudPipelineLogger.cache("conversation reset: $conversationId")
        }
    }

    /** Drops all conversation memory (e.g. provider deleted). */
    fun resetConversationMemory() {
        conversationMemory.clear()
    }

    // ── Internals ─────────────────────────────────────────────────────────

    private fun planCache(
        request: CloudChatRequest,
        providerId: String,
        conversationId: String?
    ): CacheLookupResult? {
        // Stable prefix = system messages + tool schemas. Nothing dynamic.
        val systemContent = request.messages
            .filter { it.role == "system" }
            .joinToString("\n") { it.content.orEmpty() }
        val toolsContent = request.tools.joinToString("\n") { tool ->
            "${tool.function.name}:${tool.function.description}:${tool.function.parameters}"
        }
        // A request with no stable content at all has nothing to cache.
        if (systemContent.isBlank() && toolsContent.isBlank()) return null

        val systemFingerprint = cache.fingerprint(systemContent)
        val toolsFingerprint = cache.fingerprint(toolsContent)
        val prefixFingerprint = cache.fingerprint(systemContent, toolsContent)
        val key = PromptCache.key(PromptCache.KEY_PREFIX, providerId, request.model, prefixFingerprint)

        // Invalidation detection against the previous turn of this conversation.
        if (conversationId != null) {
            val previous = conversationMemory[conversationId]
            if (previous != null) {
                if (previous.providerId != providerId) {
                    cache.invalidateWhere(CacheInvalidationReason.PROVIDER_CHANGED) {
                        it.conversationId == conversationId
                    }
                } else if (previous.modelId != request.model) {
                    cache.invalidateWhere(CacheInvalidationReason.MODEL_CHANGED) {
                        it.conversationId == conversationId
                    }
                } else {
                    if (previous.systemFingerprint != systemFingerprint) {
                        cache.invalidateWhere(CacheInvalidationReason.SYSTEM_PROMPT_CHANGED) {
                            it.providerId == providerId && it.modelId == request.model &&
                                (it.kind == PromptCacheContentKind.SYSTEM_PROMPT ||
                                    it.kind == PromptCacheContentKind.CHAT_PREFIX)
                        }
                    }
                    if (previous.toolsFingerprint != toolsFingerprint) {
                        cache.invalidateWhere(CacheInvalidationReason.TOOL_SCHEMA_CHANGED) {
                            it.providerId == providerId && it.modelId == request.model &&
                                (it.kind == PromptCacheContentKind.TOOL_SCHEMA ||
                                    it.kind == PromptCacheContentKind.CHAT_PREFIX)
                        }
                    }
                }
            }
            conversationMemory[conversationId] = ConversationPrefixMemory(
                systemFingerprint = systemFingerprint,
                toolsFingerprint = toolsFingerprint,
                providerId = providerId,
                modelId = request.model
            )
        }

        // Probe the cache for this exact stable prefix.
        val existing = cache.probe(key)
        if (existing != null) {
            val savings = CloudCacheHints.estimateSavings(request.model, existing.estimatedTokens)
            cache.noteHit(
                key,
                savedTokens = savings.savedTokens,
                latencySavedMs = savings.latencySavedMs,
                costSavedMicros = savings.costSavedMicros
            )
            return CacheLookupResult(
                hit = true,
                key = key,
                fingerprint = prefixFingerprint,
                savedTokensEstimate = savings.savedTokens,
                latencySavedMs = savings.latencySavedMs,
                costSavedMicros = savings.costSavedMicros
            )
        }

        cache.noteMiss(key)
        // Store the new stable prefix so the next turn of this conversation
        // (and any other conversation with the same system prompt + tools)
        // hits the cache.
        val estimatedTokens = cache.estimateTokens(systemContent) + cache.estimateTokens(toolsContent)
        cache.put(
            PromptCacheEntry(
                key = key,
                fingerprint = prefixFingerprint,
                providerId = providerId,
                modelId = request.model,
                kind = PromptCacheContentKind.CHAT_PREFIX,
                estimatedTokens = estimatedTokens,
                contentChars = systemContent.length + toolsContent.length,
                createdAtMs = clock(),
                lastUsedAtMs = clock(),
                conversationId = conversationId.orEmpty()
            )
        )
        return CacheLookupResult.miss(key, prefixFingerprint)
    }
}
