package io.androllm.feature.chat

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.androllm.core.cloud.CloudGateway
import io.androllm.core.cloud.model.CloudChatMessage
import io.androllm.core.cloud.model.CloudGenerationConfig
import io.androllm.core.cloud.model.CloudStreamEvent
import io.androllm.core.common.BaseViewModel
import io.androllm.core.common.getOrNull
import io.androllm.core.database.repository.ConversationRepository
import io.androllm.core.database.repository.MessageRepository
import io.androllm.core.datastore.PreferencesDataStore
import io.androllm.core.datastore.UserPreferences
import io.androllm.core.memory.MemoryManager
import io.androllm.core.memory.model.MemoryContext
import io.androllm.core.memory.model.MemoryExchange
import io.androllm.core.models.Conversation
import io.androllm.core.models.Message
import io.androllm.core.models.MessageRole
import io.androllm.engine.api.EngineRepository
import io.androllm.engine.api.EngineState
import io.androllm.engine.api.GenerationState
import io.androllm.engine.models.ChatPromptMessage
import io.androllm.engine.models.EngineDebugInfo
import io.androllm.engine.models.EngineStats
import io.androllm.engine.models.GenerationConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import javax.inject.Inject

/**
 * ViewModel for the chat feature managing full conversation lifecycle,
 * streaming, settings, search, pinning/archiving, export, and prompt editing.
 */
@HiltViewModel
class ChatViewModel @Inject constructor(
    private val engineRepository: EngineRepository,
    private val conversationRepository: ConversationRepository,
    private val messageRepository: MessageRepository,
    private val preferencesDataStore: PreferencesDataStore,
    private val memoryManager: MemoryManager,
    private val cloudGateway: CloudGateway
) : BaseViewModel() {

    private val _currentConversationId = MutableStateFlow<String>("")
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    private val _searchQuery = MutableStateFlow<String>("")
    private val _isSearchOpen = MutableStateFlow<Boolean>(false)
    private val _debugInfo = MutableStateFlow<EngineDebugInfo?>(null)
    private val _genConfig = MutableStateFlow(GenerationConfig())

    /** Cloud chat mode state (Local GGUF vs LiteLLM proxy). */
    private val _cloudMode = MutableStateFlow(false)
    private val _cloudGenerating = MutableStateFlow(false)
    private val _cloudStreamingText = MutableStateFlow<String?>(null)
    private val _cloudDefaultModel = MutableStateFlow("")

    val debugInfo: StateFlow<EngineDebugInfo?> = _debugInfo
    val genConfig: StateFlow<GenerationConfig> = _genConfig

    private var cloudJob: Job? = null

    fun updateGenConfig(config: GenerationConfig) {
        _genConfig.value = config
    }

    val uiState: StateFlow<ChatUiState> = combine(
        combine(
            _currentConversationId,
            _messages,
            conversationRepository.observeActive(),
            conversationRepository.observePinned()
        ) { currentId, messages, activeConvs, pinnedConvs ->
            listOf(currentId, messages, activeConvs, pinnedConvs)
        },
        combine(
            engineRepository.engineState,
            engineRepository.generationState,
            engineRepository.performanceStats
        ) { engineState, genState, stats ->
            Triple(engineState, genState, stats)
        },
        combine(
            preferencesDataStore.userPreferences,
            _searchQuery,
            _isSearchOpen,
            _cloudMode,
            _cloudGenerating,
            _cloudStreamingText,
            _cloudDefaultModel
        ) { values: Array<Any?> ->
            @Suppress("UNCHECKED_CAST")
            CloudUiBits(
                userPrefs = values[0] as UserPreferences,
                searchQuery = values[1] as String,
                isSearchOpen = values[2] as Boolean,
                cloudMode = values[3] as Boolean,
                cloudGenerating = values[4] as Boolean,
                cloudStreamingText = values[5] as String?,
                cloudDefaultModel = values[6] as String
            )
        }
    ) { convData, engineData, searchData ->
        @Suppress("UNCHECKED_CAST")
        val currentId = convData[0] as String
        @Suppress("UNCHECKED_CAST")
        val messages = convData[1] as List<ChatMessage>
        @Suppress("UNCHECKED_CAST")
        val activeConvs = convData[2] as List<Conversation>
        @Suppress("UNCHECKED_CAST")
        val pinnedConvs = convData[3] as List<Conversation>

        val (engineState, genState, stats) = engineData
        val bits = searchData

        val currentConv = (activeConvs + pinnedConvs).find { it.id == currentId }

        ChatUiState.Success(
            conversationId = currentId,
            conversation = currentConv,
            activeConversations = activeConvs,
            pinnedConversations = pinnedConvs,
            messages = messages,
            engineState = engineState,
            generationState = genState,
            performanceStats = stats,
            isGenerating = if (bits.cloudMode) {
                bits.cloudGenerating
            } else {
                genState is GenerationState.Generating
            },
            streamingText = if (bits.cloudMode) {
                bits.cloudStreamingText
            } else {
                (genState as? GenerationState.Generating)?.streamingText
            },
            userPreferences = bits.userPrefs,
            searchQuery = bits.searchQuery,
            isSearchOpen = bits.isSearchOpen,
            cloudMode = bits.cloudMode,
            cloudDefaultModel = bits.cloudDefaultModel
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = ChatUiState.Loading
    )

    init {
        observeEngine()
        observeActiveMessages()
        observeCloudMode()
        // Preload the embedding source in the background so the first prompt
        // after enabling memory never pays a multi-second load on the send
        // path. No-op when memory is disabled or cloud embeddings are active.
        viewModelScope.launch {
            if (memoryManager.currentSettings().enabled) {
                memoryManager.preloadEmbeddingModel()
            }
        }
    }

    private fun observeEngine() {
        viewModelScope.launch {
            engineRepository.initialize()
        }

        viewModelScope.launch {
            engineRepository.generationState.collect { genState ->
                when (genState) {
                    is GenerationState.Completed -> {
                        appendAssistantMessage(genState.text)
                        runMemoryPipeline(genState.text)
                    }
                    is GenerationState.Failed -> appendErrorMessage(genState.message)
                    else -> Unit
                }
            }
        }
    }

    private fun observeCloudMode() {
        viewModelScope.launch {
            cloudGateway.settings.collect { settings ->
                _cloudMode.value = settings.enabled
                _cloudDefaultModel.value = settings.defaultModelId
            }
        }
    }

    private fun observeActiveMessages() {
        viewModelScope.launch {
            _currentConversationId.collect { id ->
                if (id.isNotBlank()) {
                    messageRepository.observeByConversationId(id).collect { msgs ->
                        _messages.value = msgs.map { it.toChatMessage() }
                    }
                } else {
                    _messages.value = emptyList()
                }
            }
        }
    }

    fun loadConversation(conversationId: String?) {
        val id = conversationId ?: ""
        _currentConversationId.value = id
    }

    private var initialPromptConsumed = false

    /**
     * Sends a prompt arriving from the Prompt Library (nav argument).
     * One-shot per chat entry: navigating back does not re-send it.
     */
    fun sendPromptFromLibrary(prompt: String) {
        if (initialPromptConsumed || prompt.isBlank()) return
        initialPromptConsumed = true
        sendMessage(prompt)
    }

    fun createNewConversation(title: String = "New Chat") {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val newId = UUID.randomUUID().toString()
            val newConv = Conversation(
                id = newId,
                title = title,
                createdAt = now,
                updatedAt = now
            )
            conversationRepository.upsert(newConv)
            _currentConversationId.value = newId
        }
    }

    fun selectConversation(id: String) {
        _currentConversationId.value = id
    }

    private fun generateFromHistory(history: List<ChatMessage>) {
        viewModelScope.launch {
            // Memory retrieval happens before the prompt is built: relevant
            // memories + conversation summaries are injected as a system
            // message. Retrieval is fast (<20ms with the embedding model
            // loaded); the very first use after enabling memory may be slower
            // while the model warms up.
            val memoryContext = buildMemoryContext(history)
            if (memoryContext.systemText.isNotBlank()) {
                android.util.Log.i("ChatViewModel", "Injected memory context (${memoryContext.memories.size} memories, ${memoryContext.summaries.size} summaries)")
            }

            val messages = buildList {
                if (memoryContext.systemText.isNotBlank()) {
                    add(ChatPromptMessage(role = "system", content = memoryContext.systemText))
                }
                history.mapTo(this) {
                    ChatPromptMessage(
                        role = it.role.toTemplateRole(),
                        content = it.content.trim()
                    )
                }
            }
            android.util.Log.i("ChatViewModel", "generateFromHistory: ${messages.size} messages: ${
                messages.joinToString(" | ") { "${it.role}:${it.content.take(30)}" }
            }")
            // Explicit addAssistant=true: the rendered prompt ends with the
            // assistant turn header so generation can start immediately.
            // Cloud mode: stream through the LiteLLM proxy instead of the
            // local engine. Falls back to local inference when not configured.
            if (_cloudMode.value) {
                if (cloudGateway.resolveChatTarget() == null) {
                    appendErrorMessage("No cloud provider/model configured — add one in Settings → Cloud Providers, or switch back to local mode")
                    return@launch
                }
                runCloudGeneration(messages)
                return@launch
            }

            val promptResult = engineRepository.buildChatPrompt(messages, addAssistant = true)
            val prompt = promptResult.getOrNull()
            if (prompt.isNullOrBlank()) {
                val err = (promptResult as? io.androllm.core.common.Result.Error)?.exception?.message
                    ?: "Chat template returned empty prompt"
                android.util.Log.e("ChatViewModel", "Chat template failed: $err")
                appendErrorMessage("Model chat template unavailable: $err")
                return@launch
            }
            android.util.Log.i("ChatViewModel", "prompt.length=${prompt.length} head=${prompt.take(80)}")
            engineRepository.generate(prompt = prompt, config = _genConfig.value)
        }
    }

    fun sendMessage(content: String) {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) return

        // A new user turn supersedes any pending background memory work.
        memoryPipelineJob?.cancel()
        // ...and any in-flight cloud generation.
        cloudJob?.cancel()

        viewModelScope.launch {
            var id = _currentConversationId.value
            if (id.isBlank()) {
                val now = System.currentTimeMillis()
                id = UUID.randomUUID().toString()
                val title = if (trimmed.length > 30) trimmed.take(30) + "..." else trimmed
                val newConv = Conversation(id = id, title = title, createdAt = now, updatedAt = now)
                conversationRepository.upsert(newConv)
                _currentConversationId.value = id
            }

            val userMessage = ChatMessage(
                id = UUID.randomUUID().toString(),
                conversationId = id,
                role = MessageRole.USER,
                content = trimmed,
                timestamp = System.currentTimeMillis()
            )

            messageRepository.upsert(userMessage.toCoreMessage())
            conversationRepository.updateTitle(id, generateTitleFromMessage(trimmed))

            val currentHistory = _messages.value + userMessage
            generateFromHistory(currentHistory)
        }
    }

    fun regenerateLastResponse() {
        val currentMsgs = _messages.value
        if (currentMsgs.isEmpty()) return

        val lastAssistantMsg = currentMsgs.lastOrNull { it.role == MessageRole.ASSISTANT }
        viewModelScope.launch {
            if (lastAssistantMsg != null) {
                messageRepository.deleteById(lastAssistantMsg.id)
            }
            val remainingHistory = _messages.value.filter { it.id != lastAssistantMsg?.id }
            if (remainingHistory.isNotEmpty()) {
                generateFromHistory(remainingHistory)
            }
        }
    }

    fun editUserPrompt(messageId: String, newContent: String) {
        val msgs = _messages.value
        val targetMsg = msgs.find { it.id == messageId } ?: return

        viewModelScope.launch {
            messageRepository.truncateAfterTimestamp(targetMsg.conversationId, targetMsg.timestamp)
            val updatedMsg = targetMsg.copy(content = newContent.trim(), timestamp = System.currentTimeMillis())
            messageRepository.upsert(updatedMsg.toCoreMessage())

            val remainingMsgs = msgs.takeWhile { it.id != messageId } + updatedMsg
            generateFromHistory(remainingMsgs)
        }
    }

    fun togglePinConversation(conversation: Conversation) {
        viewModelScope.launch {
            conversationRepository.setPinned(conversation.id, !conversation.isPinned)
        }
    }

    fun toggleArchiveConversation(conversation: Conversation) {
        viewModelScope.launch {
            conversationRepository.setArchived(conversation.id, !conversation.isArchived)
        }
    }

    fun renameConversation(conversationId: String, newTitle: String) {
        viewModelScope.launch {
            conversationRepository.updateTitle(conversationId, newTitle.trim())
        }
    }

    fun duplicateConversation(conversationId: String) {
        viewModelScope.launch {
            val result = conversationRepository.duplicateConversation(conversationId)
            result.getOrNull()?.let { _currentConversationId.value = it.id }
        }
    }

    fun deleteConversation(conversationId: String) {
        viewModelScope.launch {
            conversationRepository.deleteById(conversationId)
            if (_currentConversationId.value == conversationId) {
                _currentConversationId.value = ""
            }
        }
    }

    fun toggleBookmarkMessage(messageId: String, currentBookmarked: Boolean) {
        viewModelScope.launch {
            messageRepository.setBookmarked(messageId, !currentBookmarked)
        }
    }

    fun deleteMessage(messageId: String) {
        viewModelScope.launch {
            messageRepository.deleteById(messageId)
        }
    }

    fun cancelGeneration() {
        cloudJob?.cancel()
        viewModelScope.launch {
            engineRepository.cancelGeneration()
        }
    }

    /**
     * Flips the Local GGUF / Cloud (LiteLLM) chat mode. Purely a UI toggle:
     * cloud failures never touch the local engine, so switching back is
     * instant.
     */
    fun toggleCloudMode() {
        val enable = !_cloudMode.value
        // Switching back to local stops any in-flight cloud stream immediately.
        if (!enable) cloudJob?.cancel()
        viewModelScope.launch {
            cloudGateway.setCloudModeEnabled(enable)
        }
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun toggleSearch(isOpen: Boolean) {
        _isSearchOpen.value = isOpen
    }

    fun refreshDebugInfo() {
        viewModelScope.launch {
            _debugInfo.value = engineRepository.getDebugInfo().getOrNull()
        }
    }

    /**
     * Streams a chat completion through the LiteLLM proxy. The buffered text
     * is surfaced as [ChatUiState.Success.streamingText]; on completion the
     * assistant message is persisted and the memory pipeline runs exactly as
     * it does for local generation.
     */
    private fun runCloudGeneration(messages: List<ChatPromptMessage>) {
        cloudJob?.cancel()
        cloudJob = viewModelScope.launch {
            _cloudGenerating.value = true
            _cloudStreamingText.value = ""
            val buffer = StringBuilder()
            try {
                val requestMessages = messages.map { CloudChatMessage(role = it.role, content = it.content) }
                cloudGateway.streamChat(
                    messages = requestMessages,
                    config = cloudGenerationConfig()
                ).collect { event ->
                    when (event) {
                        is CloudStreamEvent.Delta -> {
                            buffer.append(event.text)
                            _cloudStreamingText.value = buffer.toString()
                        }
                        // Reasoning/tool deltas are not surfaced in the plain
                        // chat UI yet — they are dropped at the gateway edge.
                        is CloudStreamEvent.Reasoning -> Unit
                        is CloudStreamEvent.ToolCallDelta -> Unit
                        is CloudStreamEvent.Usage -> Unit
                        CloudStreamEvent.Done -> Unit
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _cloudGenerating.value = false
                _cloudStreamingText.value = null
                if (buffer.isEmpty()) {
                    appendErrorMessage("Cloud error: ${e.message}")
                } else {
                    appendErrorMessage("${e.message} — response may be incomplete")
                }
                return@launch
            }
            _cloudGenerating.value = false
            _cloudStreamingText.value = null
            val text = buffer.toString()
            if (text.isNotBlank()) {
                appendAssistantMessage(text)
                runMemoryPipeline(text)
            }
        }
    }

    /** Maps the chat sampler settings onto the OpenAI-compatible request. */
    private fun cloudGenerationConfig(): CloudGenerationConfig {
        val gen = _genConfig.value
        return CloudGenerationConfig(
            temperature = gen.temperature.toDouble(),
            topP = gen.topP.toDouble(),
            topK = gen.topK.takeIf { it > 0 },
            maxTokens = gen.maxTokens,
            seed = gen.seed.takeIf { it >= 0 },
            stop = gen.stopSequences
        )
    }

    private var memoryPipelineJob: Job? = null
    private var lastProcessedExchangeKey: String? = null

    companion object {
        /** Budget for memory retrieval on the send path (retrieval is <20ms when warm). */
        private const val MEMORY_RETRIEVAL_TIMEOUT_MS = 400L
    }

    /**
     * Post-response memory pipeline: extract long-term memories from the
     * finished exchange and schedule rolling summarization. Best-effort and
     * deferred so the UI stays responsive.
     */
    private fun runMemoryPipeline(assistantText: String) {
        val convId = _currentConversationId.value
        val userMessage = _messages.value.lastOrNull { it.role == MessageRole.USER }?.content ?: return
        if (convId.isBlank()) return

        // Regenerate/replace flows re-emit Completed with the same exchange.
        val key = "$convId|$userMessage|$assistantText"
        if (key == lastProcessedExchangeKey) return
        lastProcessedExchangeKey = key

        memoryPipelineJob?.cancel()
        memoryPipelineJob = viewModelScope.launch(Dispatchers.IO) {
            delay(800L) // let the response UI settle before spending CPU
            val history = _messages.value
            val recent = history.takeLast(6).map { it.role.name.lowercase() to it.content }
            memoryManager.processExchange(
                MemoryExchange(
                    conversationId = convId,
                    userMessage = userMessage,
                    assistantResponse = assistantText,
                    recentMessages = recent,
                    messageCount = history.size
                )
            )
        }
    }

    /**
     * Retrieves memories + summaries for the current conversation and formats
     * the system prompt block to inject. No-op when memory is disabled.
     */
    private suspend fun buildMemoryContext(history: List<ChatMessage>): MemoryContext {
        val settings = memoryManager.currentSettings()
        if (!settings.enabled) return MemoryContext()
        val query = history.lastOrNull { it.role == MessageRole.USER }?.content ?: return MemoryContext()
        // The send path must never stall on memory work: when the embedding
        // source is still warming up (first use), retrieval can take longer.
        // Cap it so the prompt always builds promptly.
        return withTimeoutOrNull(MEMORY_RETRIEVAL_TIMEOUT_MS) {
            memoryManager.buildContext(
                userQuery = query,
                conversationId = _currentConversationId.value.takeIf { it.isNotBlank() }
            )
        } ?: MemoryContext()
    }

    private fun appendAssistantMessage(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return

        val convId = _currentConversationId.value
        if (convId.isBlank()) return

        val message = ChatMessage(
            id = UUID.randomUUID().toString(),
            conversationId = convId,
            role = MessageRole.ASSISTANT,
            content = trimmed,
            timestamp = System.currentTimeMillis()
        )

        // Update _messages IMMEDIATELY so the next sendMessage() sees the assistant
        // response. Without this, the DB write is async and Room Flow may not have
        // emitted by the time the user sends the next message, causing the prompt
        // to be built WITHOUT the assistant response (two consecutive user messages),
        // which produces garbage output.
        _messages.value = _messages.value + message

        viewModelScope.launch {
            messageRepository.upsert(message.toCoreMessage())
        }
    }

    private fun appendErrorMessage(message: String) {
        val convId = _currentConversationId.value
        if (convId.isBlank()) return

        val errorMessage = ChatMessage(
            id = UUID.randomUUID().toString(),
            conversationId = convId,
            role = MessageRole.ASSISTANT,
            content = "Error: $message",
            timestamp = System.currentTimeMillis()
        )

        viewModelScope.launch {
            messageRepository.upsert(errorMessage.toCoreMessage())
        }
    }

    private fun generateTitleFromMessage(firstMessageText: String): String {
        return if (firstMessageText.length > 25) firstMessageText.take(25) + "..." else firstMessageText
    }
}

/**
 * UI State sealed interface for Chat screen.
 */
sealed interface ChatUiState {
    data object Loading : ChatUiState

    data class Success(
        val conversationId: String = "",
        val conversation: Conversation? = null,
        val activeConversations: List<Conversation> = emptyList(),
        val pinnedConversations: List<Conversation> = emptyList(),
        val messages: List<ChatMessage> = emptyList(),
        val engineState: EngineState = EngineState.Unloaded,
        val generationState: GenerationState = GenerationState.Idle,
        val performanceStats: EngineStats? = null,
        val isGenerating: Boolean = false,
        val streamingText: String? = null,
        val userPreferences: UserPreferences = UserPreferences(),
        val searchQuery: String = "",
        val isSearchOpen: Boolean = false,
        val cloudMode: Boolean = false,
        val cloudDefaultModel: String = ""
    ) : ChatUiState

    data class Error(val throwable: Throwable) : ChatUiState
}

/**
 * Presentation chat message representation.
 */
data class ChatMessage(
    val id: String,
    val conversationId: String,
    val role: MessageRole,
    val content: String,
    val timestamp: Long,
    val isBookmarked: Boolean = false
)

/** Internal bundle for the extra chat state flows feeding [ChatUiState]. */
private data class CloudUiBits(
    val userPrefs: UserPreferences = UserPreferences(),
    val searchQuery: String = "",
    val isSearchOpen: Boolean = false,
    val cloudMode: Boolean = false,
    val cloudGenerating: Boolean = false,
    val cloudStreamingText: String? = null,
    val cloudDefaultModel: String = ""
)

private fun Message.toChatMessage(): ChatMessage = ChatMessage(
    id = id,
    conversationId = conversationId,
    role = role,
    content = content,
    timestamp = timestamp,
    isBookmarked = isBookmarked
)

private fun MessageRole.toTemplateRole(): String = when (this) {
    MessageRole.USER -> "user"
    MessageRole.ASSISTANT -> "assistant"
    MessageRole.SYSTEM -> "system"
}

private fun ChatMessage.toCoreMessage(): Message = Message(
    id = id,
    conversationId = conversationId,
    role = role,
    content = content,
    timestamp = timestamp,
    isBookmarked = isBookmarked
)
