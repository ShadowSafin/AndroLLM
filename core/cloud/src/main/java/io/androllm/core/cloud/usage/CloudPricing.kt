package io.androllm.core.cloud.usage

/**
 * Per-model pricing used for the dashboard's *estimated* cost metrics.
 *
 * Prices are USD per 1M tokens, stored as integer micros-per-token-free
 * pairs (the meter converts). The table covers common LiteLLM model
 * prefixes; anything unknown falls back to a conservative family heuristic
 * so the dashboard always shows a plausible estimate instead of $0.00.
 *
 * All figures are list-price approximations — the dashboard labels every
 * cost figure as an estimate.
 */
object CloudPricing {

    /** USD per 1M tokens. */
    data class ModelPricing(
        val inputPerMillionUsd: Double,
        val outputPerMillionUsd: Double
    )

    /** Cost of one request in millionths of USD (1_000_000 = $1.00). */
    fun estimateCostMicros(modelId: String, inputTokens: Long, outputTokens: Long): Long {
        if (inputTokens <= 0 && outputTokens <= 0) return 0
        val pricing = pricingFor(modelId)
        val inputUsd = inputTokens / 1_000_000.0 * pricing.inputPerMillionUsd
        val outputUsd = outputTokens / 1_000_000.0 * pricing.outputPerMillionUsd
        return ((inputUsd + outputUsd) * 1_000_000.0).toLong().coerceAtLeast(0)
    }

    /**
     * Estimated savings from serving [cachedTokens] out of a prompt cache:
     * cached input tokens are typically billed at a fraction of the input
     * price (Anthropic 10%, OpenAI prefix caching 50%). We assume a
     * conservative average 50% discount.
     */
    fun estimateCacheSavingsMicros(modelId: String, cachedTokens: Long): Long {
        if (cachedTokens <= 0) return 0
        val pricing = pricingFor(modelId)
        val savedUsd = cachedTokens / 1_000_000.0 * pricing.inputPerMillionUsd * CACHE_READ_DISCOUNT
        return (savedUsd * 1_000_000.0).toLong().coerceAtLeast(0)
    }

    /** Average discount of a cache-read token vs a fresh input token. */
    const val CACHE_READ_DISCOUNT = 0.5

    /** Resolves pricing for a LiteLLM model id (prefix match, most specific first). */
    fun pricingFor(modelId: String): ModelPricing {
        val normalized = modelId.lowercase().trim()
        // Exact/longest prefix match against the known table.
        var best: Map.Entry<String, ModelPricing>? = null
        for (entry in TABLE) {
            if (normalized.contains(entry.key)) {
                if (best == null || entry.key.length > best.key.length) best = entry
            }
        }
        best?.let { return it.value }
        return heuristic(normalized)
    }

    /** Family heuristic for unknown models — keeps estimates plausible. */
    private fun heuristic(normalized: String): ModelPricing = when {
        // Tiny/fast tiers
        normalized.contains("nano") || normalized.contains("tiny") -> ModelPricing(0.10, 0.40)
        normalized.contains("mini") || normalized.contains("haiku") ||
            normalized.contains("flash") || normalized.contains("small") ||
            normalized.contains("turbo") || normalized.contains("instant") -> ModelPricing(0.25, 1.00)
        // Reasoning / flagship tiers
        normalized.contains("opus") || normalized.contains("ultra") -> ModelPricing(15.0, 75.0)
        normalized.startsWith("o1") || normalized.startsWith("o3") || normalized.startsWith("o4") ||
            normalized.contains("reason") || normalized.contains("pro") -> ModelPricing(3.0, 15.0)
        // Default mid tier
        else -> ModelPricing(1.0, 3.0)
    }

    /** Known model-family pricing (USD per 1M tokens, input/output). */
    private val TABLE: Map<String, ModelPricing> = mapOf(
        // OpenAI
        "gpt-4o-mini" to ModelPricing(0.15, 0.60),
        "gpt-4o" to ModelPricing(2.50, 10.00),
        "gpt-4.1-nano" to ModelPricing(0.10, 0.40),
        "gpt-4.1-mini" to ModelPricing(0.40, 1.60),
        "gpt-4.1" to ModelPricing(2.00, 8.00),
        "gpt-4-turbo" to ModelPricing(10.00, 30.00),
        "gpt-4" to ModelPricing(30.00, 60.00),
        "gpt-3.5-turbo" to ModelPricing(0.50, 1.50),
        "o3-mini" to ModelPricing(1.10, 4.40),
        "o1-mini" to ModelPricing(1.10, 4.40),
        "o1-preview" to ModelPricing(15.00, 60.00),
        "o1" to ModelPricing(15.00, 60.00),
        "chatgpt-4o" to ModelPricing(2.50, 10.00),
        // Anthropic
        "claude-3-5-haiku" to ModelPricing(0.80, 4.00),
        "claude-3-haiku" to ModelPricing(0.25, 1.25),
        "claude-3-5-sonnet" to ModelPricing(3.00, 15.00),
        "claude-3-7-sonnet" to ModelPricing(3.00, 15.00),
        "claude-sonnet-4" to ModelPricing(3.00, 15.00),
        "claude-opus-4" to ModelPricing(15.00, 75.00),
        "claude-3-opus" to ModelPricing(15.00, 75.00),
        "claude-2" to ModelPricing(8.00, 24.00),
        // Google Gemini
        "gemini-1.5-flash" to ModelPricing(0.075, 0.30),
        "gemini-1.5-pro" to ModelPricing(1.25, 5.00),
        "gemini-2.0-flash" to ModelPricing(0.10, 0.40),
        "gemini-2.5-flash" to ModelPricing(0.15, 0.60),
        "gemini-2.5-pro" to ModelPricing(1.25, 10.00),
        "gemini-flash" to ModelPricing(0.10, 0.40),
        "gemini-pro" to ModelPricing(1.25, 5.00),
        // DeepSeek
        "deepseek-chat" to ModelPricing(0.27, 1.10),
        "deepseek-v3" to ModelPricing(0.27, 1.10),
        "deepseek-reasoner" to ModelPricing(0.55, 2.19),
        "deepseek-r1" to ModelPricing(0.55, 2.19),
        // Meta Llama (typical hosted pricing)
        "llama-3.1-8b" to ModelPricing(0.05, 0.08),
        "llama-3.1-70b" to ModelPricing(0.59, 0.79),
        "llama-3.3-70b" to ModelPricing(0.59, 0.79),
        "llama-3.1-405b" to ModelPricing(2.50, 2.50),
        "llama3-8b" to ModelPricing(0.05, 0.08),
        "llama3-70b" to ModelPricing(0.59, 0.79),
        // Mistral
        "mistral-small" to ModelPricing(0.10, 0.30),
        "mistral-large" to ModelPricing(2.00, 6.00),
        "mixtral-8x7b" to ModelPricing(0.24, 0.24),
        "mixtral-8x22b" to ModelPricing(0.65, 0.65),
        "codestral" to ModelPricing(0.30, 0.90),
        // Qwen
        "qwen-turbo" to ModelPricing(0.05, 0.20),
        "qwen-plus" to ModelPricing(0.40, 1.20),
        "qwen-max" to ModelPricing(1.60, 6.40),
        "qwen2.5-coder" to ModelPricing(0.80, 0.80),
        // Embeddings (per 1M tokens, no output)
        "text-embedding-3-small" to ModelPricing(0.02, 0.0),
        "text-embedding-3-large" to ModelPricing(0.13, 0.0),
        "text-embedding-ada" to ModelPricing(0.10, 0.0),
        "embedding" to ModelPricing(0.05, 0.0)
    )

    /** Formats micros as a compact USD string for the UI ("$0.0042"). */
    fun formatUsd(micros: Long): String {
        val usd = micros / 1_000_000.0
        return when {
            usd == 0.0 -> "$0.00"
            usd < 0.01 -> "$" + "%.4f".format(usd)
            usd < 1.0 -> "$" + "%.3f".format(usd)
            else -> "$" + "%.2f".format(usd)
        }
    }
}
