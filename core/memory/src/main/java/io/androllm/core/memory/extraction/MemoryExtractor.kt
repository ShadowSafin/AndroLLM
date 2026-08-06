package io.androllm.core.memory.extraction

import io.androllm.core.common.Result
import io.androllm.core.common.getOrNull
import io.androllm.core.memory.model.ExtractedMemory
import io.androllm.core.memory.model.MemoryExchange
import io.androllm.core.memory.model.MemorySettings
import io.androllm.core.memory.util.MemoryLogger
import io.androllm.engine.api.EngineRepository
import io.androllm.engine.models.ChatPromptMessage
import io.androllm.engine.models.GenerationConfig
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs the "extract long-term memories" step through the LOCAL llama.cpp
 * engine: a short, low-temperature generation constrained by a JSON schema
 * grammar. Only durable facts survive the prompt filter (greetings / one-off
 * requests are ignored).
 *
 * Provider-agnostic callers use [io.androllm.core.memory.intelligence.
 * RoutingMemoryIntelligence], which delegates here when cloud mode is off.
 * Runs through [EngineRepository.generateQuiet] so the chat UI never sees the
 * call, and the shared generation mutex guarantees it never overlaps an
 * interactive response.
 */
@Singleton
class MemoryExtractor @Inject constructor(
    private val engineRepository: EngineRepository,
    private val logger: MemoryLogger
) {

    /**
     * Extracts memories from an exchange. Returns an empty list when nothing
     * was extracted or when the LLM call is unavailable (model not loaded).
     */
    suspend fun extract(exchange: MemoryExchange, settings: MemorySettings): Result<List<ExtractedMemory>> {
        if (!settings.extractionEnabled) return Result.Success(emptyList())

        val userContent = ExtractionPrompts.buildUserContent(exchange)
        val messages = listOf(
            ChatPromptMessage(role = "system", content = ExtractionPrompts.SYSTEM_INSTRUCTION),
            ChatPromptMessage(role = "user", content = userContent)
        )

        val prompt = engineRepository.buildChatPrompt(messages, addAssistant = true).getOrNull()
        if (prompt.isNullOrBlank()) {
            logger.warn("Extraction skipped: chat template unavailable")
            return Result.error("Chat template unavailable")
        }

        val config = GenerationConfig(
            maxTokens = 160,
            temperature = 0.2f,
            topP = 1.0f,
            minP = 0.0f,
            repetitionPenalty = 1.1f,
            jsonSchema = ExtractionSchema.JSON_SCHEMA,
            reuseKvCache = false
        )

        val output = engineRepository.generateQuiet(prompt, config).getOrNull()
        if (output.isNullOrBlank()) {
            logger.debug("Extraction returned empty output")
            return Result.Success(emptyList())
        }

        val memories = ExtractionJsonParser.parse(output)
        logger.info("Extraction: ${memories.size} memory(s) from exchange")
        return Result.Success(memories)
    }
}
