package io.androllm.engine.memory

/**
 * Deterministic context-window resolution for the local engine.
 *
 * The old engine passed n_ctx=0 to llama.cpp when the caller did not request a
 * context, which silently used the model's TRAIN context (e.g. 32768 for
 * Qwen2.5) — huge KV allocations for small models and a RAM estimate that did
 * not match the actual allocation. Every load path now resolves the context
 * to an explicit number BEFORE any native allocation, so the RAM guard and
 * the context creation always agree.
 *
 * Optimization: adaptive context sizing based on device class. Low-end
 * devices use shorter contexts to reduce KV-cache memory pressure, while
 * flagship devices get full context for maximum quality.
 */
object ContextManager {

    /**
     * Aggressive-fit context ladder — smallest safe sizes ordered largest→smallest.
     * For large models the smallest practical is 1024 (512 allowed only as last resort).
     */
    private val AGGRESSIVE_LADDER = intArrayOf(8192, 6144, 4096, 3072, 2048, 1536, 1024, 512)
    private val LARGE_MODEL_LADDER = intArrayOf(4096, 3072, 2048, 1536, 1024)
    private val XLARGE_MODEL_LADDER = intArrayOf(3072, 2048, 1536, 1024)

    /**
     * Resolves a requested context length (0 = unspecified) to the explicit
     * context the native engine will be told to use. Uses device-class-
     * adaptive defaults when no explicit request is made.
     */
    fun resolveContextLength(requested: Int): Int {
        if (requested > 0) return requested
        // Use device-adaptive default instead of a fixed constant.
        return io.androllm.engine.utils.ThreadManager.recommendedContextLength()
    }

    /**
     * Clamps a resolved context to a model's train context for display
     * purposes only — never for the actual allocation.
     */
    fun clampToTrainContext(contextLength: Int, trainContext: Int): Int =
        contextLength.coerceAtMost(trainContext.takeIf { it > 0 } ?: contextLength)

    /**
     * Estimates the KV-cache memory needed for a given context length
     * and model file size. Used by the resource guard to decide whether
     * the model fits in available RAM.
     */
    fun estimateKvCacheMemory(contextLength: Int, modelFileSizeBytes: Long): Long {
        val kvBytesPerToken = io.androllm.engine.utils.MemoryEstimator.fallbackKvBytesPerToken(modelFileSizeBytes)
        return contextLength.toLong() * kvBytesPerToken
    }

    /**
     * Returns the maximum context length that fits in the given memory
     * budget, based on the model's KV-cache cost per token.
     */
    fun maxContextForBudget(memoryBudgetBytes: Long, modelFileSizeBytes: Long): Int {
        val kvBytesPerToken = io.androllm.engine.utils.MemoryEstimator.fallbackKvBytesPerToken(modelFileSizeBytes)
        if (kvBytesPerToken <= 0) return io.androllm.core.common.AppConstants.Model.DEFAULT_CONTEXT_LENGTH
        return (memoryBudgetBytes / kvBytesPerToken).toInt().coerceAtLeast(512)
    }

    // ── Aggressive-fit helpers ────────────────────────────────────────

    /**
     * Returns the smallest safe context that still fits the memory budget
     * after aggressive optimizations (reduced scratch, compact buffers).
     * Never returns less than 512 (absolute floor) or 1024 for 7B+ models.
     */
    fun smallestFittingContext(
        requested: Int,
        modelFileSizeBytes: Long,
        availableBytes: Long,
        safetyFraction: Double = 0.85
    ): Int {
        val isXLarge = io.androllm.engine.utils.MemoryEstimator.isXLargeModel(modelFileSizeBytes)
        val isLarge = io.androllm.engine.utils.MemoryEstimator.isLargeModel(modelFileSizeBytes)
        val floor = if (isXLarge || isLarge) 1024 else 512
        val ladder = when {
            isXLarge -> XLARGE_MODEL_LADDER
            isLarge -> LARGE_MODEL_LADDER
            else -> AGGRESSIVE_LADDER
        }
        val budget = (availableBytes * safetyFraction).toLong()
        for (candidate in ladder) {
            if (candidate > requested) continue
            if (candidate < floor) continue
            val footprint = io.androllm.engine.utils.MemoryEstimator.estimateAggressiveFootprint(
                fileSizeBytes = modelFileSizeBytes,
                contextLength = candidate
            )
            if (footprint <= budget) return candidate
        }
        return floor.coerceAtMost(requested)
    }

    /**
     * Returns the reduced-context ladder for a given requested size and
     * model category. Used by the aggressive-fit planner before refusing.
     */
    fun reducedContextOptions(
        requested: Int,
        modelFileSizeBytes: Long
    ): List<Int> {
        val isXLarge = io.androllm.engine.utils.MemoryEstimator.isXLargeModel(modelFileSizeBytes)
        val isLarge = io.androllm.engine.utils.MemoryEstimator.isLargeModel(modelFileSizeBytes)
        val ladder = when {
            isXLarge -> XLARGE_MODEL_LADDER
            isLarge -> LARGE_MODEL_LADDER
            else -> AGGRESSIVE_LADDER
        }
        val filtered = ladder.filter { it <= requested }.distinct().sortedDescending()
        return if (filtered.isEmpty()) listOf(requested.coerceAtLeast(512)) else filtered
    }

    /**
     * Trims a context length to the smallest entry in the aggressive ladder
     * that is <= [requested]. Useful for forcing Qwen3 8B onto a safe short context.
     */
    fun aggressiveContextFor(modelFileSizeBytes: Long, requested: Int): Int {
        val opts = reducedContextOptions(requested, modelFileSizeBytes)
        // Prefer the largest that gives meaningful savings: for 8B/7B models pick 2048 when requested is 4096+
        if (io.androllm.engine.utils.MemoryEstimator.isLargeModel(modelFileSizeBytes) && requested >= 4096) {
            return 2048.coerceAtMost(requested)
        }
        return opts.firstOrNull() ?: requested
    }

    /**
     * KV-cache trimming helper: returns a safe context that keeps only the
     * active conversation state. Reuses cache between prompts; callers should
     * drop old turns when approaching this limit.
     */
    fun trimContextForActiveConversation(
        currentContext: Int,
        activeTokens: Int,
        modelFileSizeBytes: Long
    ): Int {
        // Keep only active conversation + 20% headroom, never below 1024
        val needed = (activeTokens * 1.2).toInt().coerceAtLeast(1024)
        return needed.coerceAtMost(currentContext)
    }
}