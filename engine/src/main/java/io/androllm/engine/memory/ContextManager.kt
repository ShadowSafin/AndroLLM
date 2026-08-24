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
}