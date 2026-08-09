package io.androllm.feature.voice.chat

import io.androllm.core.cloud.CloudGateway
import io.androllm.core.cloud.model.CloudChatMessage
import io.androllm.core.cloud.model.CloudGenerationConfig
import io.androllm.core.cloud.model.CloudStreamEvent
import io.androllm.core.common.Result
import io.androllm.core.database.repository.ConversationRepository
import io.androllm.core.database.repository.MessageRepository
import io.androllm.core.database.repository.SettingsRepository
import io.androllm.core.memory.MemoryManager
import io.androllm.core.memory.model.MemoryExchange
import io.androllm.core.models.Conversation
import io.androllm.core.models.Message
import io.androllm.core.models.MessageOrigin
import io.androllm.core.models.MessageRole
import io.androllm.engine.api.EngineRepository
import io.androllm.engine.api.EngineState
import io.androllm.engine.api.InferenceEngine
import io.androllm.engine.models.ChatPromptMessage
import io.androllm.engine.models.GenerationConfig
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/**
 * Centralized, provider-agnostic Chat Pipeline.
 *
 * The Voice Assistant calls [sendMessageStream] to execute a turn.
 * [ChatManager] resolves whether to route the request through the local
 * llama.cpp engine ([EngineRepository]) or the active Cloud Provider ([CloudGateway]).
 *
 * The voice assistant NEVER handles provider-specific reasoning logic.
 *
 * Shared state:
 * - Room SQLite database ([ConversationRepository], [MessageRepository])
 * - Memory system ([MemoryManager])
 * - Context Builder & System Prompts
 */
@Singleton
class ChatManager @Inject constructor(
    private val engineRepository: EngineRepository,
    private val inferenceEngine: InferenceEngine,
    private val cloudGateway: CloudGateway,
    private val conversationRepository: ConversationRepository,
    private val messageRepository: MessageRepository,
    private val memoryManager: MemoryManager,
    private val settingsRepository: SettingsRepository
) {
    private val scope = CoroutineScope(Dispatchers.Default)

    /**
     * Resolves a human-readable label for the currently active model, no matter
     * which provider it comes from (local GGUF or any cloud provider). The
     * voice overlay uses this for its model chip — it never hardcodes a
     * provider.
     *
     * Returns `(providerLabel, modelName)`; both empty when nothing is loaded
     * and no cloud provider is configured.
     */
    suspend fun activeModelLabel(): Pair<String, String> {
        val cloud = runCatching { cloudGateway.resolveChatTarget() }.getOrNull()
        if (cloud != null) return cloud.first to cloud.second
        val engineState = runCatching { engineRepository.engineState.value }.getOrNull()
        if (engineState is EngineState.Ready) {
            val model = engineState.model
            val name = model.generalName.ifBlank { model.id }
            return "Local GGUF" to name
        }
        return "" to ""
    }

    /**
     * Sends a message into the current or new conversation and returns a [Flow] of response text deltas.
     *
     * Handles:
     * 1. Resolving or creating an active conversation ID.
     * 2. Persisting the user message to SQLite DB (`MessageRepository`).
     * 3. Building memory & system prompt context.
     * 4. Routing generation to local llama.cpp or selected cloud model (Gemini, Claude, GPT, Grok, DeepSeek, OpenRouter, LiteLLM Custom).
     * 5. Emitting streaming deltas.
     * 6. Persisting the assistant reply to SQLite DB and launching post-turn memory processing.
     */
    fun sendMessageStream(
        content: String,
        origin: MessageOrigin = MessageOrigin.VOICE,
        lowLatencyMode: Boolean = false
    ): Flow<String> = channelFlow {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) return@channelFlow
        // Step 9: confirm ChatManager receives the recognizer's exact text —
        // it must only ever strip surrounding whitespace, never edit words.
        if (origin == MessageOrigin.VOICE) {
            Timber.tag("CHAT").i(
                "STEP9: ChatManager received EXACT transcript: '%s' (%d chars) [unchanged from STT? %s]",
                trimmed, trimmed.length,
                if (content.trim() == content) "yes" else "no (whitespace trimmed)"
            )
        }

        val now = System.currentTimeMillis()
        var activeConv = conversationRepository.observeActive().first().firstOrNull()
        var convId = activeConv?.id.orEmpty()

        if (convId.isBlank()) {
            convId = UUID.randomUUID().toString()
            val title = if (trimmed.length > 30) trimmed.take(30) + "..." else trimmed
            activeConv = Conversation(id = convId, title = title, createdAt = now, updatedAt = now)
            conversationRepository.upsert(activeConv)
        }

        // Save user message in shared DB
        val userMsgId = UUID.randomUUID().toString()
        val userMessage = Message(
            id = userMsgId,
            conversationId = convId,
            role = MessageRole.USER,
            content = trimmed,
            timestamp = now,
            origin = origin
        )
        messageRepository.upsert(userMessage)

        // Build memory & prompt context
        val memoryContext = if (lowLatencyMode) {
            io.androllm.core.memory.model.MemoryContext()
        } else {
            withTimeoutOrNull(400) {
                memoryManager.buildContext(userQuery = trimmed)
            } ?: io.androllm.core.memory.model.MemoryContext()
        }

        // Build prompt message list from DB history
        val historyMessages = messageRepository.observeByConversationId(convId).first()
        val promptMessages = buildList {
            if (memoryContext.systemText.isNotBlank()) {
                add(ChatPromptMessage(role = "system", content = memoryContext.systemText))
            }
            historyMessages.forEach { msg ->
                add(ChatPromptMessage(role = msg.role.name.lowercase(), content = msg.content))
            }
        }

        val answerBuffer = StringBuilder()

        // Provider Routing:
        // Check if cloud mode is enabled and cloud provider target exists.
        // Both checks are defensive: a missing/empty cloud or engine
        // configuration must NEVER throw here — it should fall through to
        // the friendly no-provider fallback below so the voice assistant
        // always answers instead of silently failing.
        val isCloud = runCatching { cloudGateway.isConfigured() }.getOrDefault(false)
        val isLocalLoaded = runCatching { inferenceEngine.isLoaded() }.getOrDefault(false)

        try {
            if (isCloud) {
                // Route to Cloud Gateway (supports Gemini, Claude, GPT, Grok, DeepSeek, OpenRouter, LiteLLM Custom)
                cloudGateway.streamChat(
                    messages = promptMessages.map { CloudChatMessage(role = it.role, content = it.content) },
                    config = CloudGenerationConfig(temperature = 0.7, topP = 0.95, maxTokens = 512)
                ).collect { event ->
                    if (event is CloudStreamEvent.Delta) {
                        answerBuffer.append(event.text)
                        send(event.text)
                    }
                }
            } else if (isLocalLoaded) {
                // Route to Local llama.cpp Engine
                inferenceEngine.generateChatStream(
                    messages = promptMessages,
                    addAssistant = true,
                    config = GenerationConfig()
                ).collect { result ->
                    when (result) {
                        is Result.Success -> {
                            answerBuffer.append(result.data.delta)
                            send(result.data.delta)
                        }
                        is Result.Error -> {
                            Timber.e(result.exception, "ChatManager: local generation error")
                        }
                    }
                }
            } else {
                // No local model loaded AND no cloud provider configured.
                // Speak a helpful, actionable fallback instead of failing
                // silently (the voice overlay must still respond).
                val fallback = "I don't have a model loaded yet. Say open models to download one, or add a cloud provider in settings."
                send(fallback)
                answerBuffer.append(fallback)
            }
        } catch (e: Exception) {
            Timber.e(e, "ChatManager: generation failed")
            if (answerBuffer.isEmpty()) {
                send("Error: ${e.message}")
            }
        }

        val finalAnswer = answerBuffer.toString()
        if (finalAnswer.isNotBlank()) {
            // Persist Assistant response in shared DB
            val assistantMsg = Message(
                id = UUID.randomUUID().toString(),
                conversationId = convId,
                role = MessageRole.ASSISTANT,
                content = finalAnswer,
                timestamp = System.currentTimeMillis()
            )
            messageRepository.upsert(assistantMsg)

            // Trigger Memory processing pipeline
            if (memoryManager.currentSettings().enabled) {
                scope.launch {
                    runCatching {
                        memoryManager.processExchange(
                            MemoryExchange(
                                conversationId = convId,
                                userMessage = trimmed,
                                assistantResponse = finalAnswer,
                                recentMessages = listOf("user" to trimmed, "assistant" to finalAnswer),
                                messageCount = 2
                            )
                        )
                    }
                }
            }
        }
    }
}
