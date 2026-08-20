package io.androllm.feature.voice.chat

import io.androllm.core.cloud.CloudGateway
import io.androllm.core.cloud.model.CloudChatMessage
import io.androllm.core.cloud.model.CloudGenerationConfig
import io.androllm.core.cloud.model.CloudStreamEvent
import io.androllm.core.cloud.model.CloudTool
import io.androllm.core.cloud.model.CloudToolCall
import io.androllm.core.cloud.model.CloudToolCallFunction
import io.androllm.core.common.Result
import io.androllm.core.database.repository.ConversationRepository
import io.androllm.core.database.repository.MessageRepository
import io.androllm.core.database.repository.SettingsRepository
import io.androllm.core.memory.MemoryManager
import io.androllm.core.memory.model.MemoryExchange
import io.androllm.core.models.Conversation
import io.androllm.core.tools.agent.AgentVariableStore
import io.androllm.core.tools.coordinator.ToolRunCoordinator
import io.androllm.core.tools.prompt.ToolPromptBuilder
import io.androllm.core.tools.settings.AutomationSettingsStore
import io.androllm.core.tools.trace.ToolExecutionTraceStore
import io.androllm.core.models.Message
import io.androllm.core.models.MessageOrigin
import io.androllm.core.models.MessageRole
import io.androllm.engine.api.EngineRepository
import io.androllm.engine.api.EngineState
import io.androllm.engine.api.InferenceEngine
import io.androllm.engine.core.OutputSanitizer
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
 * LiteRT-LM engine ([EngineRepository]) or the active Cloud Provider ([CloudGateway]).
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
    private val settingsRepository: SettingsRepository,
    private val toolCoordinator: ToolRunCoordinator,
    private val automationSettingsStore: AutomationSettingsStore,
    private val traceStore: ToolExecutionTraceStore,
    private val variableStore: AgentVariableStore,
    private val toolPromptBuilder: ToolPromptBuilder
) {
    private val scope = CoroutineScope(Dispatchers.Default)

    /**
     * Resolves a human-readable label for the currently active model, no matter
     * which provider it comes from (local LiteRT or any cloud provider). The
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
            return "Local LiteRT" to name
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
     * 4. Routing generation to local LiteRT-LM or selected cloud model (Gemini, Claude, GPT, Grok, DeepSeek, OpenRouter, LiteLLM Custom).
     * 5. Emitting streaming deltas.
     * 6. Persisting the assistant reply to SQLite DB and launching post-turn memory processing.
     */
    fun sendMessageStream(
        content: String,
        origin: MessageOrigin = MessageOrigin.VOICE,
        lowLatencyMode: Boolean = false,
        onToolStatus: suspend (String) -> Unit = {}
    ): Flow<String> = channelFlow {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) return@channelFlow
        // Pipeline tracing: stamp this turn so every executed tool call
        // carries the spoken prompt (Tool Debug screen).
        traceStore.beginTurn(trimmed)
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
        // Fresh workflow variables for this turn (voice mode uses the real
        // conversation scope so multi-step voice tasks can chain tools).
        variableStore.beginTurn(convId)

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
            // Prompt Builder: advertise the available tools so the model
            // NEVER claims it lacks access — the tool list is part of its
            // instructions (mirrors the text-chat pipeline).
            toolPromptBuilder.advertisement()?.let {
                add(ChatPromptMessage(role = "system", content = it))
            }
            historyMessages.forEach { msg ->
                add(ChatPromptMessage(role = msg.role.name.lowercase(), content = msg.content))
            }
        }

        val answerBuffer = StringBuilder()
        // Remembered for the one-shot plain-text regeneration (the turn's
        // history plus a plain-text-only system instruction).
        var lastTurnMessages = promptMessages
        // One plain-text regeneration per turn (bounded).
        var plainTextRetryUsed = false

        // Provider Routing:
        // Check if cloud mode is enabled and cloud provider target exists.
        // Both checks are defensive: a missing/empty cloud or engine
        // configuration must NEVER throw here — it should fall through to
        // the friendly no-provider fallback below so the voice assistant
        // always answers instead of silently failing.
        val isCloud = runCatching { cloudGateway.isConfigured() }.getOrDefault(false)
        val isLocalLoaded = runCatching { inferenceEngine.isLoaded() }.getOrDefault(false)
        var toolsRan = false

        try {
            if (isCloud) {
                // Route to Cloud Gateway (supports Gemini, Claude, GPT, Grok, DeepSeek, OpenRouter, LiteLLM Custom)
                // with native tool calling: when the model emits tool_calls
                // they are executed and their results fed back for the next
                // round (up to the configured loop guard).
                val tools = runCatching { toolCoordinator.cloudTools() }.getOrDefault(emptyList())
                val maxRounds = runCatching { automationSettingsStore.current().maxToolRounds }.getOrDefault(3)
                val history = mutableListOf<CloudChatMessage>()
                history += promptMessages.map { CloudChatMessage(role = it.role, content = it.content) }
                // Agent context (device facts + workflow variables) injected
                // before the tool-calling rounds so the model plans with the
                // real device state instead of asking.
                if (tools.isNotEmpty()) {
                    toolCoordinator.agentContextMessage()?.let {
                        history.add(0, CloudChatMessage(role = "system", content = it.content))
                    }
                    // NOTE: the tool advertisement is already part of
                    // [promptMessages] (injected by the Prompt Builder above) —
                    // adding it again here would double the token cost.
                }
                round@ for (round in 0 until maxRounds) {
                    val roundBuffer = StringBuilder()
                    val calls = LinkedHashMap<Int, AccumulatedCloudCall>()
                    cloudGateway.streamChat(
                        messages = history,
                        config = CloudGenerationConfig(
                            temperature = 0.7,
                            topP = 0.95,
                            // null = omit max_tokens so the provider uses its
                            // own maximum output instead of an 8k ceiling.
                            maxTokens = null,
                            tools = tools
                        )
                    ).collect { event ->
                        when (event) {
                            is CloudStreamEvent.Delta -> roundBuffer.append(event.text)
                            is CloudStreamEvent.ToolCallDelta -> {
                                val acc = calls.getOrPut(event.index) {
                                    AccumulatedCloudCall(event.id, event.name.orEmpty())
                                }
                                event.id?.let { acc.id = it }
                                event.name?.let { acc.name = it }
                                acc.arguments.append(event.arguments)
                            }
                            else -> Unit
                        }
                    }
                    if (calls.isNotEmpty()) {
                        if (tools.isEmpty()) {
                            // NO tool executor exists: discard the calls and
                            // keep the conversation going in natural language.
                            Timber.w(
                                "ChatManager: model emitted %d tool call(s) with no executor — discarding and continuing in plain text",
                                calls.size
                            )
                            history += CloudChatMessage(
                                role = "system",
                                content = OutputSanitizer.NO_TOOL_EXECUTOR_INSTRUCTION
                            )
                            continue@round
                        }
                        val cloudCalls = calls.values.map {
                            CloudToolCall(
                                index = 0,
                                id = it.id,
                                type = "function",
                                function = CloudToolCallFunction(it.name, it.arguments.toString())
                            )
                        }
                        // Text streamed in the same message as the tool calls is
                        // part of the reply — never discard it (a chained
                        // "search the weather, then message mom" often has the
                        // model narrate alongside its calls). Keep it for the
                        // user and feed it back in history for the next round.
                        val interimText = OutputSanitizer.sanitize(roundBuffer.toString())
                        if (interimText.isNotBlank()) {
                            answerBuffer.append(interimText)
                            send(interimText)
                        }
                        onToolStatus("Running ${cloudCalls.size} tool call${if (cloudCalls.size == 1) "" else "s"}…")
                        history += toolCoordinator.executeCloudToolCalls(
                            cloudCalls,
                            assistantContent = interimText.takeIf { it.isNotBlank() }
                        )
                        toolsRan = true
                        continue@round
                    }
                    val text = OutputSanitizer.sanitize(roundBuffer.toString())
                    if (text.isNotBlank()) {
                        answerBuffer.append(text)
                        send(text)
                    }
                    break@round
                }
            } else if (isLocalLoaded) {
                // Route to Local LiteRT-LM Engine — with prompt-based tool
                // planning first when automation is enabled.
                val (finalMessages, localToolsRan) = planAndExecuteTools(promptMessages, onToolStatus)
                if (localToolsRan) toolsRan = true
                lastTurnMessages = finalMessages
                inferenceEngine.generateChatStream(
                    messages = finalMessages,
                    addAssistant = true,
                    config = GenerationConfig()
                ).collect { result ->
                    when (result) {
                        is Result.Success -> {
                            val delta = OutputSanitizer.sanitize(result.data.delta)
                            if (delta.isNotBlank()) {
                                answerBuffer.append(delta)
                                send(delta)
                            }
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

        var finalAnswer = OutputSanitizer.sanitize(answerBuffer.toString())
        if (finalAnswer.isBlank() && !toolsRan && !plainTextRetryUsed && lastTurnMessages.isNotEmpty()) {
            // Sanitization emptied the answer (a control-token-only turn):
            // regenerate ONCE with a plain-text-only instruction.
            plainTextRetryUsed = true
            Timber.i("ChatManager: sanitized output empty — regenerating once with a plain-text-only instruction")
            val retryMessages = lastTurnMessages +
                ChatPromptMessage(role = "system", content = OutputSanitizer.PLAIN_TEXT_RETRY_INSTRUCTION)
            val retryBuffer = StringBuilder()
            if (isCloud) {
                cloudGateway.streamChat(
                    messages = retryMessages.map { CloudChatMessage(role = it.role, content = it.content) },
                    config = CloudGenerationConfig(
                        temperature = 0.7,
                        topP = 0.95,
                        maxTokens = null,
                        tools = emptyList()
                    )
                ).collect { event ->
                    if (event is CloudStreamEvent.Delta) retryBuffer.append(event.text)
                }
            } else if (isLocalLoaded) {
                inferenceEngine.generateChatStream(
                    messages = retryMessages,
                    addAssistant = true,
                    config = GenerationConfig()
                ).collect { result ->
                    if (result is Result.Success) retryBuffer.append(result.data.delta)
                }
            }
            if (retryBuffer.isNotBlank()) {
                val retryText = OutputSanitizer.sanitize(retryBuffer.toString())
                if (retryText.isNotBlank()) {
                    answerBuffer.append(retryText)
                    send(retryText)
                }
            }
            finalAnswer = OutputSanitizer.sanitize(answerBuffer.toString())
        }
        if (finalAnswer.isBlank() && toolsRan) {
            // Never-blank after tool execution: speak the real tool result
            // instead of silence.
            finalAnswer = buildToolFallbackText()
            Timber.i("ChatManager: turn empty after tool calls — injected tool-summary reply")
        }
        traceStore.endTurn(finalAnswer)
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

    /**
     * Local tool planning: run the planner, execute any calls (confirmations
     * go through the voice responder), and append the results as a system
     * message before the answer generation.
     */
    private suspend fun planAndExecuteTools(
        messages: List<ChatPromptMessage>,
        onToolStatus: suspend (String) -> Unit
    ): Pair<List<ChatPromptMessage>, Boolean> {
        if (!toolCoordinator.isToolUseEnabled()) return messages to false
        // Multi-round local workflow: plan → execute (with confirmations and
        // retry) → feed results back → re-plan, up to the configured round
        // guard. The callback flags the never-blank guard and the UI chip.
        var toolsRan = false
        val finalMessages = toolCoordinator.runLocalWorkflow(
            messages,
            onActivity = { status ->
                if (status != null) {
                    toolsRan = true
                    onToolStatus(status)
                }
            }
        )
        return finalMessages to toolsRan
    }

    /**
     * Never-blank reply builder (STEP 8/12): when tools ran but the model
     * produced no text, ground the spoken reply in the last real tool result.
     */
    private fun buildToolFallbackText(): String = traceStore.lastTurnSummary()
}

/** Accumulates a streaming cloud `tool_calls` fragment by index. */
private class AccumulatedCloudCall(
    initialId: String?,
    initialName: String
) {
    var id: String? = initialId
    var name: String = initialName
    val arguments = StringBuilder()
}
