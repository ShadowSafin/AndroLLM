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
 */
object ContextManager {

    /**
     * Resolves a requested context length (0 = unspecified) to the explicit
     * context the native engine will be told to use.
     */
    fun resolveContextLength(requested: Int): Int =
        requested.takeIf { it > 0 } ?: io.androllm.core.common.AppConstants.Model.DEFAULT_CONTEXT_LENGTH

    /**
     * Clamps a resolved context to a model's train context for display
     * purposes only — never for the actual allocation.
     */
    fun clampToTrainContext(contextLength: Int, trainContext: Int): Int =
        contextLength.coerceAtMost(trainContext.takeIf { it > 0 } ?: contextLength)
}