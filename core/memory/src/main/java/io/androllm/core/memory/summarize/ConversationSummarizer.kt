package io.androllm.core.memory.summarize

import io.androllm.core.common.Result
import io.androllm.core.common.getOrNull
import io.androllm.core.memory.util.MemoryLogger
import io.androllm.engine.api.EngineRepository
import io.androllm.engine.models.ChatPromptMessage
import io.androllm.engine.models.GenerationConfig
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Incremental conversation summarizer (LOCAL llama.cpp backing). Every
 * `summarizationInterval` messages the latest summary + new messages are
 * condensed into an updated rolling summary, stored separately so the context
 * builder can prefer summaries over raw histories.
 *
 * Provider-agnostic callers use [io.androllm.core.memory.intelligence.
 * RoutingMemoryIntelligence], which delegates here when cloud mode is off.
 */
@Singleton
class ConversationSummarizer @Inject constructor(
    private val engineRepository: EngineRepository,
    private val logger: MemoryLogger
) {

    /**
     * Summarizes [recentMessages] together with [previousSummary] (may be null).
     * Returns the new summary text, or an error when the LLM is unavailable.
     */
    suspend fun summarize(
        conversationId: String,
        previousSummary: String?,
        recentMessages: List<Pair<String, String>>
    ): Result<String> {
        val userContent = SummaryPrompts.buildUserContent(previousSummary, recentMessages)

        val messages = listOf(
            ChatPromptMessage(role = "system", content = SummaryPrompts.SYSTEM_INSTRUCTION),
            ChatPromptMessage(role = "user", content = userContent)
        )
        val prompt = engineRepository.buildChatPrompt(messages, addAssistant = true).getOrNull()
        if (prompt.isNullOrBlank()) {
            logger.warn("Summarization skipped: chat template unavailable")
            return Result.error("Chat template unavailable")
        }

        val config = GenerationConfig(
            maxTokens = 220,
            temperature = 0.2f,
            topP = 1.0f,
            minP = 0.0f,
            repetitionPenalty = 1.05f,
            reuseKvCache = false
        )

        val output = engineRepository.generateQuiet(prompt, config).getOrNull()
        val summary = output?.trim().orEmpty()
        if (summary.isEmpty()) {
            logger.debug("Summarization returned empty output (conversation $conversationId)")
            return Result.error("Empty summary")
        }
        logger.info("Summarized conversation $conversationId (${recentMessages.size} messages)")
        return Result.Success(summary)
    }
}
