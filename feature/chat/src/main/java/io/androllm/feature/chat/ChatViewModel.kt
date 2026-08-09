package io.androllm.feature.chat

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.androllm.core.cloud.CloudGateway
import io.androllm.core.cloud.model.CloudChatMessage
import io.androllm.core.cloud.model.CloudGenerationConfig
import io.androllm.core.cloud.model.CloudStreamEvent
import io.androllm.core.cloud.model.CloudTool
import io.androllm.core.cloud.model.CloudToolCall
import io.androllm.core.cloud.model.CloudToolCallFunction
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
import io.androllm.core.tools.agent.AgentVariableStore
import io.androllm.core.tools.coordinator.ToolRunCoordinator
import io.androllm.core.tools.confirmation.PendingToolConfirmation
import io.androllm.core.tools.confirmation.ToolConfirmationManager
import io.androllm.core.tools.settings.AutomationSettingsStore
import io.androllm.core.tools.trace.ToolExecutionTraceStore
import io.androllm.core.models.Message
import io.androllm.core.models.MessageOrigin
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
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ChatViewModel @Inject constructor(
    private val engineRepository: EngineRepository,
    private val conversationRepository: ConversationRepository,
    private val messageRepository: MessageRepository,
    private val preferencesDataStore: PreferencesDataStore,
    private val memoryManager: MemoryManager,
    private val cloudGateway: CloudGateway,
    private val toolCoordinator: ToolRunCoordinator,
    private val confirmationManager: ToolConfirmationManager,
    private val automationSettingsStore: AutomationSettingsStore,
    private val traceStore: ToolExecutionTraceStore,
    private val variableStore: AgentVariableStore
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

    /** Tool action currently awaiting user approval (chat confirmation card). */
    private val _pendingToolConfirmation = MutableStateFlow<PendingToolConfirmation?>(null)
    /** One-line activity chip while tools are planning/executing. */
    private val _toolActivity = MutableStateFlow<String?>(null)

    val debugInfo: StateFlow<EngineDebugInfo?> = _debugInfo
    val genConfig: StateFlow<GenerationConfig> = _genConfig

    private var cloudJob: Job? = null

    /**
     * True when the current turn executed at least one tool call. Used by the
     * never-blank guard: if the model then produces no text, the reply is
     * grounded in the actual tool result instead of silence.
     */
    private var toolsExecutedThisTurn = false

    /**
     * Message ids appended to [_messages] locally while their Room upsert is
     * still in flight. The DB observer merges these back in so a stale Room
     * emission can never drop the assistant response before the next prompt
     * is built (two consecutive user messages → template corruption).
     */
    private val pendingLocalMessageIds = mutableSetOf<String>()

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
            _cloudDefaultModel,
            _pendingToolConfirmation,
            _toolActivity
        ) { values: Array<Any?> ->
            @Suppress("UNCHECKED_CAST")
            CloudUiBits(
                userPrefs = values[0] as UserPreferences,
                searchQuery = values[1] as String,
                isSearchOpen = values[2] as Boolean,
                cloudMode = values[3] as Boolean,
                cloudGenerating = values[4] as Boolean,
                cloudStreamingText = values[5] as String?,
                cloudDefaultModel = values[6] as String,
                pendingToolConfirmation = values[7] as PendingToolConfirmation?,
                toolActivity = values[8] as String?
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
            cloudDefaultModel = bits.cloudDefaultModel,
            pendingToolConfirmation = bits.pendingToolConfirmation,
            toolActivity = bits.toolActivity
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
        observeToolConfirmations()
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
                        var text = genState.text
                        if (text.isBlank() && toolsExecutedThisTurn) {
                            // STEP 8/12 — never-blank: tools ran but the model
                            // produced no text; ground the reply in the real
                            // tool result instead of staying silent.
                            text = traceStore.lastTurnSummary()
                            android.util.Log.w("ChatViewModel", "Local turn empty after tool calls — injected tool-summary reply")
                        }
                        toolsExecutedThisTurn = false
                        appendAssistantMessage(text)
                        runMemoryPipeline(text)
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

    /** Mirrors the shared confirmation hub into the chat UI state. */
    private fun observeToolConfirmations() {
        viewModelScope.launch {
            confirmationManager.pending.collect { _pendingToolConfirmation.value = it }
        }
    }

    /** Approves/denies the pending high-risk tool action from the chat card. */
    fun confirmToolAction(id: String, approved: Boolean) {
        if (approved) confirmationManager.confirm(id) else confirmationManager.deny(id)
    }

    private fun observeActiveMessages() {
        viewModelScope.launch {
            _currentConversationId
                // Switching conversations must never leak the previous
                // conversation's locally-pending messages (or its rows from an
                // in-flight Room stream) into the new one — that would bake
                // foreign assistant text into the next prompt.
                .onEach {
                    pendingLocalMessageIds.clear()
                    _messages.value = emptyList()
                }
                // flatMapLatest cancels the previous conversation's Room
                // stream when the id changes, so stale emissions from the old
                // conversation can never interleave with the new one's.
                .flatMapLatest { id ->
                    if (id.isBlank()) flowOf(emptyList())
                    else messageRepository.observeByConversationId(id)
                }
                .collect { msgs ->
                    val dbMessages = msgs.map { it.toChatMessage() }
                    val dbIds = dbMessages.mapTo(mutableSetOf()) { it.id }
                    // Locally appended messages (async upsert still in flight)
                    // survive a stale DB emission from the same conversation.
                    // Once the DB confirms them they leave the pending set. The
                    // merged list is re-sorted by timestamp so a pending
                    // assistant response never lands after a newer user message
                    // (that would still produce two consecutive user messages
                    // in the next prompt).
                    pendingLocalMessageIds.removeAll(dbIds)
                    val pending = _messages.value.filter { it.id in pendingLocalMessageIds }
                    val merged = (dbMessages + pending).sortedBy { it.timestamp }
                    // PERFORMANCE: skip the write when nothing actually changed.
                    // appendAssistantMessage() already wrote the identical list
                    // locally (the pending-message pattern); the DB echo that
                    // confirms it is data-class-equal, so writing again would
                    // trigger a SECOND full recomposition + markdown re-parse
                    // of the long assistant response for no change.
                    if (merged != _messages.value) {
                        _messages.value = merged
                    }
                }
        }
    }

    fun loadConversation(conversationId: String?) {
        val id = conversationId ?: ""
        _currentConversationId.value = id
        // Loading a specific conversation (e.g. deep-linked from Home/Models)
        // is a conversation boundary: the engine keeps ONE resident context,
        // so entering a different conversation must never decode against the
        // previous one's cached prefix. Same reset as selectConversation.
        resetEngineChatState()
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
            // New conversation = fresh native chat state (messages + KV cache).
            resetEngineChatState()
        }
    }

    fun selectConversation(id: String) {
        _currentConversationId.value = id
        // Switching conversations must reset the native engine's chat state
        // (messages + KV cache): the engine keeps one resident context and
        // continues from its KV across turns, so a new conversation must never
        // decode against the previous one's cached prefix. Matches upstream
        // ai_chat.cpp reset_long_term_states() on new session.
        resetEngineChatState()
    }

    /**
     * Cancels any in-flight generation and resets the native engine's chat
     * state (messages + KV cache) at a conversation boundary. Cancel-first
     * ordering matters: a conversation switch can happen while a decode is
     * running (the composer is disabled but the drawer stays interactive), and
     * clearing the KV cache mid-decode would race the active generation on the
     * shared resident context. The native loop checks the cancel flag between
     * decodes, so awaiting [EngineRepository.cancelGeneration] before
     * [EngineRepository.resetChat] closes that window.
     */
    private fun resetEngineChatState() {
        // A conversation boundary also drops any unanswered confirmation card.
        confirmationManager.cancelPending()
        viewModelScope.launch {
            engineRepository.cancelGeneration()
            engineRepository.resetChat()
        }
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

            if (messages.isEmpty()) {
                android.util.Log.e("ChatViewModel", "generateFromHistory: empty message list")
                appendErrorMessage("No messages to send")
                return@launch
            }
            android.util.Log.i("ChatViewModel", "generateFromHistory: ${messages.size} messages: ${
                messages.joinToString(" | ") { "${it.role}:${it.content.take(30)}" }
            }")
            // Tool planning (local): the model first emits tool calls (JSON,
            // grammar-constrained); when calls exist they are executed and
            // their results are injected as a system message before the
            // answer generation so the model can summarize what happened.
            val finalMessages = planAndExecuteTools(messages)
            // Stateful multi-turn chat: the native engine diffs [messages]
            // against its accumulated conversation and decodes only the new
            // messages' template diff at the continuing KV position (official
            // llama.cpp pattern). Template errors surface as Failed state.
            engineRepository.generateChat(
                messages = finalMessages,
                addAssistant = true,
                config = _genConfig.value
            )
        }
    }

    /**
     * True while a chat turn (local or cloud) is actively generating. The UI
     * already disables input during generation; this guard closes the
     * remaining programmatic paths (prompt library, suggestions, deep links)
     * that would otherwise build a prompt with two consecutive user messages
     * and no assistant separator — a corruption source.
     */
    private fun isGenerationInFlight(): Boolean =
        _cloudGenerating.value ||
            engineRepository.generationState.value is GenerationState.Generating

    fun sendMessage(content: String) {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) return

        // Runtime stabilization: never start a new turn while one is running.
        // A queued/dropped prompt beats a corrupt context.
        if (isGenerationInFlight()) {
            android.util.Log.w("ChatViewModel", "sendMessage ignored: generation already in flight")
            return
        }

        // A new user turn supersedes any pending background memory work.
        memoryPipelineJob?.cancel()
        // ...and any in-flight cloud generation.
        cloudJob?.cancel()

        viewModelScope.launch {
            // Pipeline tracing: stamp this turn so every executed tool call
            // carries its user prompt, and reset the never-blank flag.
            traceStore.beginTurn(trimmed)
            toolsExecutedThisTurn = false

            var id = _currentConversationId.value
            val isNewConversation = id.isBlank()
            if (isNewConversation) {
                val now = System.currentTimeMillis()
                id = UUID.randomUUID().toString()
                val title = if (trimmed.length > 30) trimmed.take(30) + "..." else trimmed
                val newConv = Conversation(id = id, title = title, createdAt = now, updatedAt = now)
                conversationRepository.upsert(newConv)
                _currentConversationId.value = id
                // A brand-new conversation (created from a blank active id —
                // e.g. after deleting the previous one) must never decode
                // against the previous conversation's resident KV cache or
                // chat state. Reset natively BEFORE the first turn starts:
                // awaited inline so generateFromHistory below can never race
                // the reset (the send guard already rejected in-flight work,
                // so the cancel here is a harmless no-op).
                engineRepository.cancelGeneration()
                engineRepository.resetChat()
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

            // A brand-new conversation never inherits stale rows from a previous
            // conversation (_messages is cleared by the observer, but only
            // asynchronously). For existing conversations, the DB observer may
            // already have echoed this user message back into _messages (Room
            // emission after the upsert above) — dedupe so the prompt never
            // contains the same user message twice.
            val base = if (isNewConversation) emptyList() else _messages.value
            val currentHistory =
                base.filterNot { it.id == userMessage.id } + userMessage
            // Fresh workflow variables for this turn (device facts are
            // re-collected; tool outputs chain within the turn).
            variableStore.beginTurn(id)
            generateFromHistory(currentHistory)
        }
    }

    fun regenerateLastResponse() {
        if (isGenerationInFlight()) {
            android.util.Log.w("ChatViewModel", "regenerateLastResponse ignored: generation already in flight")
            return
        }
        val currentMsgs = _messages.value
        if (currentMsgs.isEmpty()) return

        val lastAssistantMsg = currentMsgs.lastOrNull { it.role == MessageRole.ASSISTANT }
        viewModelScope.launch {
            if (lastAssistantMsg != null) {
                pendingLocalMessageIds.remove(lastAssistantMsg.id)
                messageRepository.deleteById(lastAssistantMsg.id)
            }
            val remainingHistory = _messages.value.filter { it.id != lastAssistantMsg?.id }
            traceStore.beginTurn(remainingHistory.lastOrNull { it.role == MessageRole.USER }?.content)
            variableStore.beginTurn(_currentConversationId.value)
            toolsExecutedThisTurn = false
            if (remainingHistory.isNotEmpty()) {
                generateFromHistory(remainingHistory)
            }
        }
    }

    fun editUserPrompt(messageId: String, newContent: String) {
        if (isGenerationInFlight()) {
            android.util.Log.w("ChatViewModel", "editUserPrompt ignored: generation already in flight")
            return
        }
        val msgs = _messages.value
        val targetMsg = msgs.find { it.id == messageId } ?: return

        viewModelScope.launch {
            messageRepository.truncateAfterTimestamp(targetMsg.conversationId, targetMsg.timestamp)
            pendingLocalMessageIds.removeAll(msgs.map { it.id })
            val updatedMsg = targetMsg.copy(content = newContent.trim(), timestamp = System.currentTimeMillis())
            messageRepository.upsert(updatedMsg.toCoreMessage())

            val remainingMsgs = msgs.takeWhile { it.id != messageId } + updatedMsg
            traceStore.beginTurn(newContent.trim())
            variableStore.beginTurn(_currentConversationId.value)
            toolsExecutedThisTurn = false
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
            result.getOrNull()?.let {
                _currentConversationId.value = it.id
                // Duplicate starts a new conversation — reset native chat state
                // so it never continues from the source conversation's KV.
                resetEngineChatState()
            }
        }
    }

    fun deleteConversation(conversationId: String) {
        viewModelScope.launch {
            conversationRepository.deleteById(conversationId)
            if (_currentConversationId.value == conversationId) {
                _currentConversationId.value = ""
                // The active conversation is gone — clear the resident native
                // chat state (messages + KV cache) so the next sendMessage()
                // that opens a fresh conversation starts from a clean slate.
                resetEngineChatState()
            }
        }
    }

    fun toggleBookmarkMessage(messageId: String, currentBookmarked: Boolean) {
        viewModelScope.launch {
            messageRepository.setBookmarked(messageId, !currentBookmarked)
        }
    }

    fun deleteMessage(messageId: String) {
        pendingLocalMessageIds.remove(messageId)
        viewModelScope.launch {
            messageRepository.deleteById(messageId)
        }
    }

    fun cancelGeneration() {
        cloudJob?.cancel()
        confirmationManager.cancelPending()
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
     * Streams a chat completion through the LiteLLM proxy with tool calling.
     * When the model emits `tool_calls` the calls are executed through
     * [ToolRunCoordinator] and their results are appended to the OpenAI
     * history before the next round, up to [AutomationSettingsStore.maxToolRounds].
     * The buffered text is surfaced as [ChatUiState.Success.streamingText]; on
     * completion the assistant message is persisted and the memory pipeline
     * runs exactly as it does for local generation.
     */
    private fun runCloudGeneration(messages: List<ChatPromptMessage>) {
        cloudJob?.cancel()
        cloudJob = viewModelScope.launch {
            _cloudGenerating.value = true
            _cloudStreamingText.value = ""
            val history = mutableListOf<CloudChatMessage>()
            history += messages.map { CloudChatMessage(role = it.role, content = it.content) }
            val tools = runCatching { toolCoordinator.cloudTools() }.getOrDefault(emptyList())
            // Agent context (device facts + workflow variables) injected in
            // front of the tool-calling rounds so the model plans with the
            // real device state instead of asking the user.
            if (tools.isNotEmpty()) {
                toolCoordinator.agentContextMessage()?.let {
                    history.add(0, CloudChatMessage(role = "system", content = it.content))
                }
            }
            val maxRounds = runCatching { automationSettingsStore.current().maxToolRounds }.getOrDefault(3)
            val answerBuffer = StringBuilder()
            // Throttle UI emissions to ~60fps. Publishing on EVERY delta forces
            // a full recomposition + markdown re-parse of the streaming bubble
            // per token, and buffer.toString() copies the ENTIRE accumulated
            // response each time (O(n²) garbage that spikes GC right after the
            // generation ends). Local streaming already throttles this way.
            var lastEmitTime = 0L
            var callsExecuted = false
            try {
                round@ for (round in 0 until maxRounds) {
                    val roundBuffer = StringBuilder()
                    val calls = LinkedHashMap<Int, AccumulatedToolCall>()
                    cloudGateway.streamChat(
                        messages = history,
                        config = cloudGenerationConfig(tools = tools)
                    ).collect { event ->
                        when (event) {
                            is CloudStreamEvent.Delta -> {
                                roundBuffer.append(event.text)
                                val now = System.currentTimeMillis()
                                if (now - lastEmitTime >= 16L) {
                                    lastEmitTime = now
                                    _cloudStreamingText.value = roundBuffer.toString()
                                }
                            }
                            // The model wants tools: accumulate the streaming
                            // fragments, execute them after the round, and
                            // feed the results back for the next round.
                            is CloudStreamEvent.ToolCallDelta -> {
                                val acc = calls.getOrPut(event.index) {
                                    AccumulatedToolCall(event.id, event.name.orEmpty())
                                }
                                event.id?.let { acc.id = it }
                                event.name?.let { acc.name = it }
                                acc.arguments.append(event.arguments)
                            }
                            is CloudStreamEvent.Reasoning -> Unit
                            is CloudStreamEvent.Usage -> Unit
                            CloudStreamEvent.Done -> Unit
                        }
                    }
                    if (calls.isNotEmpty()) {
                        val cloudCalls = calls.values.map {
                            CloudToolCall(
                                index = 0,
                                id = it.id,
                                type = "function",
                                function = CloudToolCallFunction(it.name, it.arguments.toString())
                            )
                        }
                        // Text the model streamed in the SAME message as the
                        // tool calls is part of the assistant's reply — it must
                        // not be thrown away or the final answer ends up
                        // partial (e.g. a chained "search weather, then message
                        // mom" where the model narrates alongside the calls).
                        // Keep it for the user AND feed it back to the model
                        // in history so the next round knows what was said.
                        val interimText = roundBuffer.toString()
                        if (interimText.isNotBlank()) answerBuffer.append(interimText)
                        _toolActivity.value = "Running ${cloudCalls.size} tool call${if (cloudCalls.size == 1) "" else "s"}…"
                        history += toolCoordinator.executeCloudToolCalls(
                            cloudCalls,
                            assistantContent = interimText.takeIf { it.isNotBlank() }
                        )
                        callsExecuted = true
                        _toolActivity.value = null
                        continue@round
                    }
                    answerBuffer.append(roundBuffer)
                    break@round
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _cloudGenerating.value = false
                _cloudStreamingText.value = null
                if (answerBuffer.isEmpty()) {
                    appendErrorMessage("Cloud error: ${e.message}")
                } else {
                    appendErrorMessage("${e.message} — response may be incomplete")
                }
                return@launch
            }
            _cloudGenerating.value = false
            _cloudStreamingText.value = null
            var text = answerBuffer.toString()
            if (text.isBlank() && callsExecuted) {
                // Never-blank after tool execution (STEP 8/12).
                text = traceStore.lastTurnSummary()
                android.util.Log.w("ChatViewModel", "Cloud turn empty after tool calls — injected tool-summary reply")
            }
            if (text.isNotBlank()) {
                appendAssistantMessage(text)
                runMemoryPipeline(text)
            }
        }
    }

    /**
     * Runs the LOCAL tool planner when enabled and, when the model wants to
     * use tools, executes them (with confirmations) and appends the results
     * as a system message so the answer generation can summarize them.
     */
    private suspend fun planAndExecuteTools(messages: List<ChatPromptMessage>): List<ChatPromptMessage> {
        if (!toolCoordinator.isToolUseEnabled()) return messages
        // Multi-round local workflow: plan → execute (confirmations + retry) →
        // feed results back → re-plan, up to the configured round guard. Each
        // round re-injects the agent context so multi-step tasks can branch on
        // previous tool outputs and chain variables between tools.
        return toolCoordinator.runLocalWorkflow(messages) { status ->
            if (status != null) toolsExecutedThisTurn = true
            _toolActivity.value = status
        }
    }

    /** Maps the chat sampler settings onto the OpenAI-compatible request. */
    private fun cloudGenerationConfig(tools: List<CloudTool> = emptyList()): CloudGenerationConfig {
        val gen = _genConfig.value
        return CloudGenerationConfig(
            temperature = gen.temperature.toDouble(),
            topP = gen.topP.toDouble(),
            topK = gen.topK.takeIf { it > 0 },
            // Local is effectively unlimited (the native engine clamps to the
            // context window), but cloud providers reject absurd max_tokens —
            // cap at a high, provider-safe ceiling.
            maxTokens = gen.maxTokens.coerceIn(1, CLOUD_MAX_OUTPUT_TOKENS),
            seed = gen.seed.takeIf { it >= 0 },
            stop = gen.stopSequences,
            tools = tools
        )
    }

    private var memoryPipelineJob: Job? = null
    private var lastProcessedExchangeKey: String? = null

    companion object {
        /**
         * Ceiling for cloud max_tokens: most providers reject or clamp values
         * above ~8k, and no reasonable chat answer needs more.
         */
        private const val CLOUD_MAX_OUTPUT_TOKENS = 8192

        /** Budget for memory retrieval on the send path (retrieval is <20ms when warm). */
        private const val MEMORY_RETRIEVAL_TIMEOUT_MS = 400L

        /**
         * Settle window before the post-response memory pipeline starts.
         * Local extraction/summarization run a full llama.cpp inference pass on
         * the shared chat model whose native threads saturate every CPU core —
         * starting it too early makes the app feel frozen right after the
         * response. Waiting until the UI + GC burst has settled keeps the
         * completion moment responsive. The job is also cancelled the moment a
         * new chat turn starts, so the user never waits on it.
         */
        private const val MEMORY_PIPELINE_SETTLE_MS = 2_000L
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
            delay(MEMORY_PIPELINE_SETTLE_MS) // let the response UI settle before spending CPU
            // Memory defers to chat: never start extraction while a chat turn
            // is in flight (sendMessage cancels this job, but this guards the
            // window where a generation starts after the delay elapses).
            if (isGenerationInFlight()) {
                android.util.Log.d("ChatViewModel", "Memory extraction deferred: chat generation in flight")
                return@launch
            }
            val history = _messages.value
            val recent = history.takeLast(6).map { it.role.name.lowercase() to it.content }
            val tracer = io.androllm.core.utils.StageTracer("memory pipeline (post-response)")
            val result = memoryManager.processExchange(
                MemoryExchange(
                    conversationId = convId,
                    userMessage = userMessage,
                    assistantResponse = assistantText,
                    recentMessages = recent,
                    messageCount = history.size
                )
            )
            tracer.mark("processExchange")
            result.getOrNull()?.let { s ->
                android.util.Log.i(
                    "AndroLLM.Perf",
                    "[PostGen] memory: +${s.inserted} ~${s.updated} -${s.skipped} extracted=${s.extracted} summarized=${s.summarized}"
                )
            }
            tracer.finish()
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
        // Attach the final assistant text to this turn's tool traces.
        traceStore.endTurn(trimmed)

        val convId = _currentConversationId.value
        if (convId.isBlank()) return

        // Post-generation performance audit: every stage below is timed so the
        // exact cost of completing a response is visible in logcat
        // (tag AndroLLM.Perf). Anything >100ms total is suspicious.
        val tracer = io.androllm.core.utils.StageTracer("generation finished")

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
        // Track the id so the DB observer merges it back in until the async
        // upsert is confirmed by a Room emission.
        pendingLocalMessageIds += message.id
        tracer.mark("local emit")

        viewModelScope.launch {
            messageRepository.upsert(message.toCoreMessage())
            // Spans the full schedule→commit window, i.e. the SQLite write cost.
            tracer.mark("room upsert")
            tracer.finish()
        }
    }

    private fun appendErrorMessage(message: String) {
        val convId = _currentConversationId.value
        if (convId.isBlank()) return
        traceStore.endTurn("Error: $message")

        val errorMessage = ChatMessage(
            id = UUID.randomUUID().toString(),
            conversationId = convId,
            role = MessageRole.ASSISTANT,
            content = "Error: $message",
            timestamp = System.currentTimeMillis()
        )

        // Same local-first + pending-tracking pattern as appendAssistantMessage:
        // an error message must never be dropped by a stale Room emission.
        _messages.value = _messages.value + errorMessage
        pendingLocalMessageIds += errorMessage.id

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
        val cloudDefaultModel: String = "",
        val pendingToolConfirmation: PendingToolConfirmation? = null,
        val toolActivity: String? = null
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
    val isBookmarked: Boolean = false,
    val origin: MessageOrigin = MessageOrigin.TYPED
)

/** Accumulates a streaming cloud `tool_calls` fragment by index. */
private class AccumulatedToolCall(
    initialId: String?,
    initialName: String
) {
    var id: String? = initialId
    var name: String = initialName
    val arguments = StringBuilder()
}

/** Internal bundle for the extra chat state flows feeding [ChatUiState]. */
private data class CloudUiBits(
    val userPrefs: UserPreferences = UserPreferences(),
    val searchQuery: String = "",
    val isSearchOpen: Boolean = false,
    val cloudMode: Boolean = false,
    val cloudGenerating: Boolean = false,
    val cloudStreamingText: String? = null,
    val cloudDefaultModel: String = "",
    val pendingToolConfirmation: PendingToolConfirmation? = null,
    val toolActivity: String? = null
)

private fun Message.toChatMessage(): ChatMessage = ChatMessage(
    id = id,
    conversationId = conversationId,
    role = role,
    content = content,
    timestamp = timestamp,
    isBookmarked = isBookmarked,
    origin = origin
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
    isBookmarked = isBookmarked,
    origin = origin
)
