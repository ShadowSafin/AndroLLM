package io.androllm.core.memory.intelligence

import io.androllm.core.common.Result
import io.androllm.core.memory.extraction.MemoryExtractor
import io.androllm.core.memory.model.ExtractedMemory
import io.androllm.core.memory.model.MemoryExchange
import io.androllm.core.memory.model.MemorySettings
import io.androllm.core.memory.summarize.ConversationSummarizer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [MemoryIntelligence] backed by the LOCAL llama.cpp engine (GGUF chat model).
 * Used when cloud mode is off, or as the fallback when the cloud call fails —
 * so memory extraction keeps working even on a fully offline device.
 */
@Singleton
class LocalMemoryIntelligence @Inject constructor(
    private val extractor: MemoryExtractor,
    private val summarizer: ConversationSummarizer
) : MemoryIntelligence {

    override suspend fun extract(
        exchange: MemoryExchange,
        settings: MemorySettings
    ): Result<List<ExtractedMemory>> = extractor.extract(exchange, settings)

    override suspend fun summarize(
        conversationId: String,
        previousSummary: String?,
        recentMessages: List<Pair<String, String>>
    ): Result<String> = summarizer.summarize(conversationId, previousSummary, recentMessages)
}