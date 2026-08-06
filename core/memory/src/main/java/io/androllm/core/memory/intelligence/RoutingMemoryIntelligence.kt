package io.androllm.core.memory.intelligence

import io.androllm.core.cloud.CloudGateway
import io.androllm.core.common.Result
import io.androllm.core.memory.model.ExtractedMemory
import io.androllm.core.memory.model.MemoryExchange
import io.androllm.core.memory.model.MemorySettings
import io.androllm.core.memory.util.MemoryLogger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Routes memory intelligence to the ACTIVE inference provider:
 *
 *  - Cloud mode configured → [CloudMemoryIntelligence] (the selected provider
 *    does the thinking), falling back to [LocalMemoryIntelligence] when the
 *    cloud call fails (offline, quota, provider error).
 *  - Otherwise → [LocalMemoryIntelligence] (llama.cpp GGUF model).
 *
 * The memory pipeline only ever sees this interface — it carries no
 * provider-specific knowledge.
 */
@Singleton
class RoutingMemoryIntelligence @Inject constructor(
    private val cloud: CloudMemoryIntelligence,
    private val local: LocalMemoryIntelligence,
    private val cloudGateway: CloudGateway,
    private val logger: MemoryLogger
) : MemoryIntelligence {

    override suspend fun extract(
        exchange: MemoryExchange,
        settings: MemorySettings
    ): Result<List<ExtractedMemory>> {
        if (!settings.extractionEnabled) return Result.Success(emptyList())
        if (!cloudGateway.isConfigured()) return local.extract(exchange, settings)
        return cloud.extract(exchange, settings).orElse {
            logger.warn("Cloud extraction failed (${it.message}); falling back to local")
            local.extract(exchange, settings)
        }
    }

    override suspend fun summarize(
        conversationId: String,
        previousSummary: String?,
        recentMessages: List<Pair<String, String>>
    ): Result<String> {
        if (!cloudGateway.isConfigured()) {
            return local.summarize(conversationId, previousSummary, recentMessages)
        }
        return cloud.summarize(conversationId, previousSummary, recentMessages).orElse {
            logger.warn("Cloud summarization failed (${it.message}); falling back to local")
            local.summarize(conversationId, previousSummary, recentMessages)
        }
    }

    /** Runs [fallback] when this result is an error. */
    private inline fun <T> Result<T>.orElse(fallback: (Throwable) -> Result<T>): Result<T> =
        if (this is Result.Error) fallback(exception) else this
}