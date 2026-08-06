package io.androllm.core.memory.intelligence

import io.androllm.core.common.Result
import io.androllm.core.memory.model.ExtractedMemory
import io.androllm.core.memory.model.MemoryExchange
import io.androllm.core.memory.model.MemorySettings

/**
 * Provider-independent "thinking" used by the memory pipeline: extraction and
 * summarization. The implementation is chosen by
 * [RoutingMemoryIntelligence] based on the ACTIVE inference provider — cloud
 * (any LiteLLM-connected provider) when cloud mode is on, the local llama.cpp
 * engine otherwise. Memory therefore never depends on a specific model.
 *
 * Embeddings are irrelevant here: this interface only turns an exchange into
 * memories and messages into a rolling summary.
 */
interface MemoryIntelligence {

    /**
     * Extracts durable memories from a finished exchange. Returns an empty
     * list when nothing worth storing was found. A failure must surface as
     * [Result.Error] so callers can fall back to another backing.
     */
    suspend fun extract(
        exchange: MemoryExchange,
        settings: MemorySettings
    ): Result<List<ExtractedMemory>>

    /**
     * Rolls [previousSummary] + [recentMessages] into an updated summary.
     * Returns the new summary text or [Result.Error] when unavailable.
     */
    suspend fun summarize(
        conversationId: String,
        previousSummary: String?,
        recentMessages: List<Pair<String, String>>
    ): Result<String>
}