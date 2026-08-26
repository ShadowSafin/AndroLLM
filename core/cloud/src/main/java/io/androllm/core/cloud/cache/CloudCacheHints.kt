package io.androllm.core.cloud.cache

import io.androllm.core.cloud.model.CloudChatMessage
import io.androllm.core.cloud.model.CloudChatRequest
import io.androllm.core.cloud.usage.CloudPricing

/**
 * Provider-aware prompt-caching behavior.
 *
 * Two caching worlds, normalized behind one API:
 *
 * 1. **Explicit cache control** (Anthropic family via LiteLLM): the request
 *    must carry `cache_control: {"type":"ephemeral"}` markers on the stable
 *    prefix (last system message). [decorate] attaches them.
 *
 * 2. **Automatic prefix caching** (OpenAI, Gemini, DeepSeek, Groq, most
 *    LiteLLM-routed providers): no marker exists — the provider caches any
 *    byte-stable prompt prefix server-side. The win comes from keeping the
 *    prefix (system prompt + tool schemas + conversation header) identical
 *    across turns, which [stabilizePrefix] enforces.
 *
 * Everything here is pure JVM logic so it is unit-testable in isolation.
 */
object CloudCacheHints {

    /** Estimated milliseconds of first-token latency saved per cached token. */
    const val LATENCY_SAVED_MS_PER_TOKEN = 0.4

    /** Providers whose models honor explicit `cache_control` markers. */
    private val EXPLICIT_CACHE_FAMILIES = listOf("anthropic/", "claude")

    /** Providers with automatic server-side prefix caching (marker-free). */
    private val PREFIX_CACHE_FAMILIES = listOf(
        "openai/", "gpt-", "o1", "o3", "o4",
        "gemini", "deepseek", "groq/", "llama",
        "mistral/", "openrouter/", "ollama/", "qwen"
    )

    /** True when the model honors explicit `cache_control` markers. */
    fun supportsExplicitCacheControl(modelId: String): Boolean {
        val id = modelId.lowercase()
        return EXPLICIT_CACHE_FAMILIES.any { id.contains(it) }
    }

    /** True when the model's provider does automatic prefix caching. */
    fun supportsPrefixCaching(modelId: String): Boolean {
        val id = modelId.lowercase()
        if (supportsExplicitCacheControl(modelId)) return true
        return PREFIX_CACHE_FAMILIES.any { id.contains(it) }
    }

    /**
     * Decorates [request] with provider-appropriate cache hints:
     * - explicit-cache families: marks the LAST system message with
     *   `cache_control` (Anthropic caches everything up to the marker);
     * - prefix-cache families: returns the request with a stabilized prefix.
     *
     * Never mutates message content — only attaches metadata, so providers
     * that understand neither style simply ignore the result.
     */
    fun decorate(request: CloudChatRequest, modelId: String): CloudChatRequest {
        val stabilized = stabilizePrefix(request.messages)
        if (!supportsExplicitCacheControl(modelId)) {
            return request.copy(messages = stabilized)
        }
        // Mark the last system message — Anthropic caches the prefix up to it.
        val lastSystemIndex = stabilized.indexOfLast { it.role == "system" }
        val messages = if (lastSystemIndex >= 0) {
            stabilized.mapIndexed { index, message ->
                if (index == lastSystemIndex && message.cacheControl == null) {
                    message.copy(cacheControl = io.androllm.core.cloud.model.CloudCacheControl())
                } else message
            }
        } else stabilized
        return request.copy(messages = messages)
    }

    /**
     * Normalizes the stable prefix of a conversation so it is byte-identical
     * across turns: trims trailing whitespace on system messages and drops
     * duplicate leading system messages. Dynamic user content is untouched —
     * only the prefix the provider can actually cache is stabilized.
     */
    fun stabilizePrefix(messages: List<CloudChatMessage>): List<CloudChatMessage> {
        if (messages.isEmpty()) return messages
        val result = ArrayList<CloudChatMessage>(messages.size)
        var previousSystemContent: String? = null
        for (message in messages) {
            if (message.role == "system") {
                val trimmed = message.content?.trimEnd()
                // Drop CONSECUTIVE duplicate system messages (a common
                // artifact of re-injected tool instructions) — duplicates
                // break prefix stability AND waste tokens. A user/tool
                // message in between resets the comparison.
                if (trimmed != null && trimmed == previousSystemContent) continue
                previousSystemContent = trimmed
                result += if (message.content != trimmed) message.copy(content = trimmed) else message
            } else {
                previousSystemContent = null
                result += message
            }
        }
        return result
    }

    /**
     * Estimated first-token latency saved by serving [tokens] from cache.
     * Prefill typically runs at 1–5k tokens/s server-side; 0.4ms/token is a
     * conservative middle estimate used only for diagnostics.
     */
    fun estimateLatencySavedMs(tokens: Int): Long =
        (tokens * LATENCY_SAVED_MS_PER_TOKEN).toLong()

    /**
     * Full savings estimate for one cache hit: tokens saved, latency saved,
     * cost saved (cached tokens bill at a discount of the input price).
     */
    fun estimateSavings(
        modelId: String,
        savedTokens: Int
    ): CacheSavingsEstimate = CacheSavingsEstimate(
        savedTokens = savedTokens,
        latencySavedMs = estimateLatencySavedMs(savedTokens),
        costSavedMicros = CloudPricing.estimateCacheSavingsMicros(modelId, savedTokens.toLong())
    )

    /** Serialized savings triple used by the planner and the usage meter. */
    data class CacheSavingsEstimate(
        val savedTokens: Int,
        val latencySavedMs: Long,
        val costSavedMicros: Long
    )
}
