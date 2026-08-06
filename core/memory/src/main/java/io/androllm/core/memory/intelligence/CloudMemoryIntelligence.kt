package io.androllm.core.memory.intelligence

import io.androllm.core.cloud.CloudGateway
import io.androllm.core.cloud.model.CloudChatMessage
import io.androllm.core.cloud.model.CloudException
import io.androllm.core.cloud.model.CloudGenerationConfig
import io.androllm.core.common.Result
import io.androllm.core.common.runCatching
import io.androllm.core.memory.extraction.ExtractionJsonParser
import io.androllm.core.memory.extraction.ExtractionPrompts
import io.androllm.core.memory.extraction.ExtractionSchema
import io.androllm.core.memory.model.ExtractedMemory
import io.androllm.core.memory.model.MemoryExchange
import io.androllm.core.memory.model.MemorySettings
import io.androllm.core.memory.summarize.SummaryPrompts
import io.androllm.core.memory.util.MemoryLogger
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json

/**
 * [MemoryIntelligence] backed by the ACTIVE cloud provider (any LiteLLM-
 * connected service: OpenAI, Anthropic, Gemini, Groq, Ollama, LM Studio,
 * custom OpenAI-compatible endpoints, ...). Uses the same prompts and the
 * same JSON contract as the local backing, so extraction output is identical
 * regardless of which model generated the answer.
 *
 * Runs as a quiet non-streaming call ([CloudGateway.chatOnce]) so the chat
 * UI never sees it.
 */
@Singleton
class CloudMemoryIntelligence @Inject constructor(
    private val cloudGateway: CloudGateway,
    private val logger: MemoryLogger
) : MemoryIntelligence {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun extract(
        exchange: MemoryExchange,
        settings: MemorySettings
    ): Result<List<ExtractedMemory>> {
        if (!settings.extractionEnabled) return Result.Success(emptyList())
        return io.androllm.core.common.runCatching {
            val messages = listOf(
                CloudChatMessage(role = "system", content = ExtractionPrompts.SYSTEM_INSTRUCTION),
                CloudChatMessage(role = "user", content = ExtractionPrompts.buildUserContent(exchange))
            )
            val output = cloudGateway.chatOnce(
                messages = messages,
                config = CloudGenerationConfig(
                    temperature = 0.2,
                    topP = 1.0,
                    maxTokens = 220,
                    jsonSchema = json.parseToJsonElement(ExtractionSchema.JSON_SCHEMA)
                )
            )
            val memories = ExtractionJsonParser.parse(output)
            logger.info("Cloud extraction: ${memories.size} memory(s) from exchange")
            memories
        }
    }

    override suspend fun summarize(
        conversationId: String,
        previousSummary: String?,
        recentMessages: List<Pair<String, String>>
    ): Result<String> = io.androllm.core.common.runCatching {
        val messages = listOf(
            CloudChatMessage(role = "system", content = SummaryPrompts.SYSTEM_INSTRUCTION),
            CloudChatMessage(role = "user", content = SummaryPrompts.buildUserContent(previousSummary, recentMessages))
        )
        val output = cloudGateway.chatOnce(
            messages = messages,
            config = CloudGenerationConfig(
                temperature = 0.2,
                topP = 1.0,
                maxTokens = 220
            )
        )
        val summary = output?.trim().orEmpty()
        if (summary.isEmpty()) throw CloudException("Empty summary")
        logger.info("Cloud summarized conversation $conversationId (${recentMessages.size} messages)")
        summary
    }
}