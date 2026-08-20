package io.androllm.feature.chat

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.androllm.core.cloud.CloudGateway
import io.androllm.core.cloud.model.CloudChatMessage
import io.androllm.core.cloud.model.CloudContentPart
import io.androllm.core.cloud.model.CloudGenerationConfig
import io.androllm.core.cloud.model.CloudStreamEvent
import io.androllm.core.cloud.model.CloudTool
import io.androllm.core.cloud.model.CloudToolCall
import io.androllm.core.cloud.model.CloudToolCallFunction
import io.androllm.core.common.AppConstants
import io.androllm.core.common.BaseViewModel
import io.androllm.core.common.getOrNull
import io.androllm.core.database.repository.ConversationRepository
import io.androllm.core.database.repository.MessageRepository
import io.androllm.core.attachments.AttachmentCache
import io.androllm.core.attachments.AttachmentProcessor
import io.androllm.core.attachments.AttachmentSettingsStore
import io.androllm.core.attachments.ProviderCapabilities
import io.androllm.core.attachments.model.AttachmentStatus
import io.androllm.core.attachments.model.ChatAttachment
import io.androllm.core.datastore.PreferencesDataStore
import io.androllm.core.datastore.UserPreferences
import io.androllm.core.memory.MemoryManager
import io.androllm.core.memory.model.MemoryContext
import io.androllm.core.memory.model.MemoryExchange
import io.androllm.core.models.Conversation
import io.androllm.core.tools.agent.AgentVariableStore
import io.androllm.core.tools.api.ToolCall
import io.androllm.core.tools.api.ToolEvent
import io.androllm.core.tools.coordinator.ToolExecutionRecord
import io.androllm.core.tools.coordinator.ToolRunCoordinator
import io.androllm.core.tools.confirmation.PendingToolConfirmation
import io.androllm.core.tools.confirmation.ToolConfirmationManager
import io.androllm.core.tools.prompt.ToolPromptBuilder
import io.androllm.core.tools.settings.AutomationSettingsStore
import io.androllm.core.tools.trace.ToolExecutionTraceStore
import io.androllm.core.models.Message
import io.androllm.core.models.MessageOrigin
import io.androllm.core.models.MessageRole
import io.androllm.engine.api.EngineRepository
import io.androllm.engine.api.EngineState
import android.content.Context
import android.net.Uri
import java.io.File
import java.util.Base64
import io.androllm.engine.api.GenerationState
import io.androllm.engine.core.NativeToolCall
import io.androllm.engine.core.OutputSanitizer
import io.androllm.engine.models.ChatPromptMessage
import io.androllm.engine.models.EngineDebugInfo
import io.androllm.engine.models.EngineStats
import io.androllm.engine.models.GenerationConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
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
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: Context,
    private val engineRepository: EngineRepository,
    private val conversationRepository: ConversationRepository,
    private val messageRepository: MessageRepository,
    private val preferencesDataStore: PreferencesDataStore,
    private val memoryManager: MemoryManager,
    private val attachmentProcessor: AttachmentProcessor,
    private val attachmentSettingsStore: AttachmentSettingsStore,
    private val cloudGateway: CloudGateway,
    private val toolCoordinator: ToolRunCoordinator,
    private val confirmationManager: ToolConfirmationManager,
    private val automationSettingsStore: AutomationSettingsStore,
    private val traceStore: ToolExecutionTraceStore,
    private val variableStore: AgentVariableStore,
    private val toolPromptBuilder: ToolPromptBuilder
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

    /**
     * Files attached to the CURRENT message being composed. Conversation-
     * scoped by construction: cleared on conversation switch and after send,
     * never indexed or shared across chats. The processed chips render above
     * the composer; their content is packaged into the next prompt.
     */
    private val _pendingAttachments = MutableStateFlow<List<ChatAttachment>>(emptyList())
    private val _attachmentsProcessing = MutableStateFlow(false)

    /**
     * True while a cloud→local switch with pending attachments is awaiting
     * user confirmation ("switching removes the current attachments").
     */
    private val _cloudToLocalConfirm = MutableStateFlow(false)

    /** Attachments of the CURRENT turn that will be packaged into the prompt. */
    private var turnAttachments: List<ChatAttachment> = emptyList()

    /**
     * Live capability check (cloud mode + a resolved cloud model). Local
     * models pass no cloud model id → false. Used by every backend gate so
     * attachments can never reach a local inference engine.
     */
    private fun attachmentsSupportedNow(): Boolean =
        _cloudMode.value &&
            io.androllm.core.attachments.ProviderCapabilities.supportsAttachments(_cloudDefaultModel.value)

    /** Tool action currently awaiting user approval (chat confirmation card). */
    private val _pendingToolConfirmation = MutableStateFlow<PendingToolConfirmation?>(null)
    /** One-line activity chip while tools are planning/executing. */
    private val _toolActivity = MutableStateFlow<String?>(null)
    /**
     * Structured, per-tool lifecycle for the chat tool cards (live streaming
     * status + expandable arguments/results). Cleared at the start of every
     * turn and appended to by [onToolEvent] as the coordinator executes calls.
     */
    private val _toolEvents = MutableStateFlow<List<ToolInvocationUi>>(emptyList())
    /**
     * True from the moment a turn is sent until the engine publishes a
     * terminal state (or the turn is cancelled). Covers the phases
     * [generationState] cannot see: conversation setup, DB write and the
     * tool-planning inference that runs BEFORE [GenerationState.Generating].
     */
    private val _preparing = MutableStateFlow(false)

    val debugInfo: StateFlow<EngineDebugInfo?> = _debugInfo
    val genConfig: StateFlow<GenerationConfig> = _genConfig

    private var cloudJob: Job? = null

    /**
     * The coroutine driving the current LOCAL turn (send / regenerate / edit).
     * [cancelGeneration] and conversation switches cancel it so a Stop press
     * or navigation kills the whole turn — including the invisible
     * tool-planning phase that [generationState] does not cover.
     */
    private var turnJob: Job? = null

    /**
     * True when the current turn executed at least one tool call. Used by the
     * never-blank guard: if the model then produces no text, the reply is
     * grounded in the actual tool result instead of silence.
     */
    private var toolsExecutedThisTurn = false

    /**
     * True while [runLocalToolLoop] drives the current local turn. While it
     * is set, the generationState collector suppresses its own commit — the
     * loop commits the final answer itself (intermediate rounds emit native
     * tool-call markers, not the answer). Cleared in the loop's finally.
     */
    private var nativeToolLoopActive = false

    /**
     * True once the current turn's final answer was committed to the UI
     * (either by the generationState collector or by [commitLocalAnswer]).
     * BOTH commit paths check + set it: the loop's commit and the collector's
     * resume race on the same `Completed` emission, and without the guard the
     * collector could run after `nativeToolLoopActive` was cleared and commit
     * a second copy of the identical message (two duplicate chat bubbles).
     * Reset at the start of every turn in [generateFromHistory].
     */
    private var localTurnCommitted = false

    /**
     * The exact prompt-message list of the current local turn (set in
     * [generateFromHistory] right before generation). Used by the one-shot
     * plain-text regeneration: when the sanitized answer is empty, the same
     * history plus a plain-text-only system instruction is re-run.
     */
    private var lastLocalMessages: List<ChatPromptMessage> = emptyList()

    /**
     * True once the plain-text regeneration was used for the current turn.
     * The regeneration is exactly ONE retry per turn — a model that produces
     * only control tokens twice is not worth a third attempt.
     */
    private var plainTextRetryUsed = false

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
            _toolActivity,
            _toolEvents,
            _preparing,
            _pendingAttachments,
            _attachmentsProcessing,
            _cloudToLocalConfirm
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
                toolActivity = values[8] as String?,
                toolEvents = values[9] as List<ToolInvocationUi>,
                isPreparing = values[10] as Boolean,
                pendingAttachments = values[11] as List<ChatAttachment>,
                attachmentsProcessing = values[12] as Boolean,
                confirmCloudToLocalSwitch = values[13] as Boolean
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
            // The turn is "generating" from the moment it is sent: the
            // PREPARING phase (conversation setup, DB write, tool planning)
            // must keep the composer disabled and the thinking indicator up,
            // otherwise a slow planner looks like a dead app.
            isGenerating = bits.cloudGenerating || bits.isPreparing ||
                genState is GenerationState.Generating,
            isPreparing = bits.isPreparing,
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
            // Capability-driven: attachments exist only when a cloud model is
            // active. Local LiteRT/llama.cpp models pass no cloud model id, so
            // this resolves to false without any provider-name checks.
            attachmentsSupported = bits.cloudMode &&
                io.androllm.core.attachments.ProviderCapabilities.supportsAttachments(bits.cloudDefaultModel),
            pendingAttachments = bits.pendingAttachments,
            attachmentsProcessing = bits.attachmentsProcessing,
            confirmCloudToLocalSwitch = bits.confirmCloudToLocalSwitch,
            pendingToolConfirmation = bits.pendingToolConfirmation,
            toolActivity = bits.toolActivity,
            toolEvents = bits.toolEvents
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
                    is GenerationState.Generating -> _preparing.value = false
                    is GenerationState.Completed -> {
                        _preparing.value = false
                        // While the native tool loop is driving the turn, the
                        // loop commits the answer itself (intermediate rounds
                        // contain tool-call markers, not the reply). Also skip
                        // when the loop already committed (its finally clears
                        // nativeToolLoopActive, so this collector may resume
                        // after the commit and must not append a duplicate).
                        if (nativeToolLoopActive) return@collect
                        if (localTurnCommitted) return@collect
                        localTurnCommitted = true
                        // The sanitizer is the LAST line of defense for the
                        // final assistant message: no control token,
                        // tool-call marker, reasoning artifact or malformed
                        // tag may reach the committed/persisted message.
                        var text = OutputSanitizer.sanitize(genState.text)
                        if (text.isBlank() && toolsExecutedThisTurn) {
                            // STEP 8/12 — never-blank: tools ran but the model
                            // produced no text; ground the reply in the real
                            // tool result instead of staying silent.
                            text = traceStore.lastTurnSummary()
                            android.util.Log.w("ChatViewModel", "Local turn empty after tool calls — injected tool-summary reply")
                        } else if (text.isBlank()) {
                            // Sanitization stripped everything (a
                            // control-token-only turn): regenerate ONCE with a
                            // plain-text-only instruction.
                            text = regeneratePlainText()
                        }
                        toolsExecutedThisTurn = false
                        appendAssistantMessage(text)
                        runMemoryPipeline(text)
                    }
                    is GenerationState.Failed -> {
                        _preparing.value = false
                        appendErrorMessage(genState.message)
                    }
                    GenerationState.Cancelled -> _preparing.value = false
                    GenerationState.Idle -> Unit
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

    /** Pending attachments for the composer chips. */
    val pendingAttachments: StateFlow<List<ChatAttachment>> = _pendingAttachments

    /**
     * Processes picked files ([uris]) into ready attachments for the current
     * conversation. Each chip updates as its file is copied/parsed; failures
     * surface per-chip instead of aborting the batch. Attachments are capped
     * by the settings' max-per-message limit.
     */
    fun attachFiles(uris: List<Uri>) {
        if (uris.isEmpty() || _attachmentsProcessing.value) return
        // Capability gate: attachments are cloud-only. Local models never
        // parse/OCR/process files — the UI hides the button, this is the
        // backend enforcement (spec: no parsing for local providers).
        if (!attachmentsSupportedNow()) {
            android.util.Log.w(TAG, "attachFiles rejected: attachments are not supported for local models")
            appendErrorMessage("Attachments are not supported for local models.")
            return
        }
        val convId = _currentConversationId.value
        if (convId.isBlank()) {
            // No conversation yet — create one so the attachments have a home.
            createNewConversation()
            return
        }
        viewModelScope.launch {
            val settings = attachmentSettingsStore.current()
            val room = (settings.maxAttachmentsPerMessage - _pendingAttachments.value.size)
                .coerceAtLeast(0)
            val toProcess = uris.take(room)
            if (toProcess.isEmpty()) return@launch
            _attachmentsProcessing.value = true
            try {
                val results = attachmentProcessor.processBatch(
                    conversationId = convId,
                    uris = toProcess,
                    onProgress = { _, _, _ -> }
                )
                val ready = results.map { it.attachment }
                _pendingAttachments.value = (_pendingAttachments.value + ready).takeLast(
                    settings.maxAttachmentsPerMessage
                )
            } finally {
                _attachmentsProcessing.value = false
            }
        }
    }

    /** Removes one pending attachment before sending. */
    fun removeAttachment(attachmentId: String) {
        val removed = _pendingAttachments.value.find { it.id == attachmentId }
        _pendingAttachments.value = _pendingAttachments.value.filterNot { it.id == attachmentId }
        // Drop the private copy from the conversation cache (best-effort).
        removed?.let { att ->
            viewModelScope.launch {
                runCatching { java.io.File(att.filePath).delete() }
            }
        }
    }

    /** Clears all pending attachments (e.g. on conversation switch). */
    fun clearPendingAttachments() {
        _pendingAttachments.value = emptyList()
    }

    /**
     * Packages the current turn's ready attachments into a numbered context
     * block injected before the user message. Text documents contribute their
     * extracted text; images contribute OCR text (native image parts are
     * handled separately in [runCloudGeneration] for vision providers).
     * Returns "" when there is nothing to inject.
     */
    private fun buildAttachmentContext(): String {
        // Backend enforcement: never inject attachment content into a local
        // inference prompt. The send guard rejects attachment messages while
        // local, so this is belt-and-braces for mode switches mid-turn.
        if (!attachmentsSupportedNow()) return ""
        val attachments = turnAttachments.filter { it.isReady && it.text.isNotBlank() }
        if (attachments.isEmpty()) return ""
        val block = buildString {
            append("The user attached the following files to this message:\n")
            attachments.forEachIndexed { index, att ->
                append("\n[File ${index + 1}: ").append(att.name)
                if (att.fromOcr) append(" (text extracted by OCR)")
                append("]\n").append(att.text.take(MAX_ATTACHMENT_CHARS_PER_FILE)).append("\n")
            }
        }
        return block
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
                //
                // Ownership-based clear, NOT a blind wipe: only messages of a
                // DIFFERENT conversation are dropped. The send path commits
                // the user message to [_messages] right after switching to a
                // brand-new conversation id, and the observer's id-change
                // resumption races that commit on the Main dispatcher. When
                // the engine's resetChat does not suspend (e.g. no model
                // loaded), the clear used to land AFTER the commit and wiped
                // the freshly-rendered user message — "the message briefly
                // appears, then disappears".
                .onEach { newId ->
                    val keep = _messages.value.filter { it.conversationId == newId }
                    _messages.value = keep
                    val keepIds = keep.mapTo(mutableSetOf()) { it.id }
                    pendingLocalMessageIds.retainAll(keepIds)
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
        // A conversation boundary also drops any unanswered confirmation card,
        // aborts the in-flight turn (its planning phase is invisible to
        // generationState, so the turn job must be cancelled explicitly) and
        // clears the preparing flag so the next conversation starts clean.
        confirmationManager.cancelPending()
        turnJob?.cancel()
        _preparing.value = false
        viewModelScope.launch {
            engineRepository.cancelGeneration()
            engineRepository.resetChat()
        }
    }

    /**
     * Builds the prompt and runs the generation for [history]. Suspend (not
     * fire-and-forget) so the caller's turn job covers the ENTIRE run — a
     * Stop press or conversation switch can therefore kill the invisible
     * tool-planning phase as well as the visible decode. Every failure is
     * caught and surfaced as a visible error message; the user's own message
     * was already committed to [_messages] by the caller.
     */
    private suspend fun generateFromHistory(history: List<ChatMessage>) {
        _preparing.value = true
        // A new turn starts a clean tool-card list; the coordinator re-populates
        // it as it plans and executes (recomposition must never append twice).
        _toolEvents.value = emptyList()
        // Fresh commit guard for this turn (see [localTurnCommitted]).
        localTurnCommitted = false
        // Fresh plain-text retry budget for this turn (see [regeneratePlainText]).
        plainTextRetryUsed = false
        try {
            // Memory retrieval happens before the prompt is built: relevant
            // memories + conversation summaries are injected as a system
            // message. Retrieval is fast (<20ms with the embedding model
            // loaded); the very first use after enabling memory may be slower
            // while the model warms up.
            val memoryContext = buildMemoryContext(history)
            if (memoryContext.systemText.isNotBlank()) {
                android.util.Log.i(TAG, "MEMORY CONTEXT (${memoryContext.memories.size} memories, ${memoryContext.summaries.size} summaries)")
            }
            // Attachment context: the current turn's attached files (text
            // documents + OCR'd images) are injected as a numbered block so
            // the model can answer about them. Native image parts for vision
            // providers are handled in the cloud generation path.
            val attachmentContext = buildAttachmentContext()
            if (attachmentContext.isNotBlank()) {
                android.util.Log.i(TAG, "ATTACHMENT CONTEXT (${turnAttachments.size} files, ${attachmentContext.length} chars)")
            }
            // Context-window guard: a conversation longer than nCtx makes the
            // native engine freeze (encode of an oversized prompt) or decode
            // garbage at the clamped tail — both reported as "the model never
            // starts / gibberish on later prompts". Slide the window over the
            // tail (oldest dropped first, current prompt always kept) so the
            // rendered prompt + reserved output always fit nCtx.
            val contextLength =
                (engineRepository.engineState.value as? EngineState.Ready)
                    ?.model?.contextLength
                    ?: AppConstants.Model.DEFAULT_CONTEXT_LENGTH

            // Prompt Builder: advertise the available tools so the model
            // NEVER claims it lacks access ("I don't have a web search
            // tool") — the tool list is part of its instructions. Built here
            // (before trimming) so its real token cost is reserved below.
            //
            // The advertisement is BUDGETED to the container's REAL context
            // (detected at load): the engine now reports the true limit
            // (some Qwen3 repacks are 2048, not the guessed 4096/8192), and
            // an unbounded tool list alone can exceed it — making EVERY
            // prompt fail with "token ids are too long" before any history
            // is even considered. Budget = context − reserved output − a
            // small history floor, in chars (~4 chars/token). The trimmer
            // below then reserves exactly what was rendered.
            val reservedOutputTokens = _genConfig.value.maxTokens.coerceIn(
                MIN_OUTPUT_RESERVE_TOKENS,
                MAX_OUTPUT_RESERVE_TOKENS
            )
                // Never reserve more than a third of the real context: a
                // default 65536 maxTokens clamps to MAX_OUTPUT_RESERVE_TOKENS
                // (4096), which is the ENTIRE 4096-token container — leaving a
                // zero system budget whose "0 = unlimited" semantics then
                // renders the FULL tool list, overrunning both the context
                // (Qwen3-0.6B's real 2048) and the model's sanity (the
                // measured ~5.7K-char degradation breakpoint).
                .coerceAtMost((contextLength / 3).coerceAtLeast(MIN_OUTPUT_RESERVE_TOKENS))
            val systemCharsBudget = (
                (contextLength - reservedOutputTokens - MIN_HISTORY_TOKENS)
                    .coerceAtLeast(0) * CHARS_PER_TOKEN_ESTIMATE
                )
            val familyAdCapChars =
                (engineRepository.engineState.value as? EngineState.Ready)
                    ?.model?.toolAdvertisementCapChars
                    ?: Int.MAX_VALUE
            // Route the advertisement to THIS request (spec: never expose
            // every tool simultaneously). The latest user message + attachment
            // presence drive the router: attachment questions advertise no
            // tools (content is already injected), math advertises only the
            // calculator, device queries only device tools, etc. — so the
            // model cannot pick the wrong tool "just in case".
            val routeQuery = history.lastOrNull { it.role == MessageRole.USER }?.content.orEmpty()
            val routeHasAttachments = turnAttachments.isNotEmpty()
            val toolAdvertisement = toolPromptBuilder.advertisement(
                maxChars = minOf(systemCharsBudget, familyAdCapChars),
                query = routeQuery,
                hasAttachments = routeHasAttachments
            )

            // Reserve the ACTUAL system-turn cost (memory context + attachment
            // block + tool advertisement + template framing) rather than a
            // fixed guess, so a large injected attachment can never silently
            // push the rendered prompt over nCtx after trimming.
            val systemTokenOverhead = SYSTEM_TEMPLATE_FRAMING_TOKENS +
                ChatHistoryTrimmer.estimateTokens(memoryContext.systemText) +
                (if (attachmentContext.isNotBlank()) ChatHistoryTrimmer.estimateTokens(attachmentContext) else 0) +
                (toolAdvertisement?.let { ChatHistoryTrimmer.estimateTokens(it) } ?: 0)
            val trimmedHistory = ChatHistoryTrimmer.trim(
                history = history,
                contextLength = contextLength,
                reservedOutputTokens = reservedOutputTokens,
                systemTokenOverhead = systemTokenOverhead
            )
            if (trimmedHistory.size != history.size) {
                android.util.Log.w(
                    TAG,
                    "History trimmed to fit context: ${history.size} → ${trimmedHistory.size} messages (nCtx=$contextLength)"
                )
            }

            val messages = buildList {
                if (memoryContext.systemText.isNotBlank()) {
                    add(ChatPromptMessage(role = "system", content = memoryContext.systemText))
                }
                if (attachmentContext.isNotBlank()) {
                    add(ChatPromptMessage(role = "system", content = attachmentContext))
                }
                toolAdvertisement?.let {
                    add(ChatPromptMessage(role = "system", content = it))
                }
                trimmedHistory.mapTo(this) {
                    ChatPromptMessage(
                        role = it.role.toTemplateRole(),
                        content = it.content.trim()
                    )
                }
            }
            android.util.Log.i(TAG, "PROMPT BUILT nMessages=${messages.size} nCtx=$contextLength: ${
                messages.joinToString(" | ") { "${it.role}:${it.content.take(30)}" }
            }")

            // Explicit addAssistant=true: the rendered prompt ends with the
            // assistant turn header so generation can start immediately.
            // Cloud mode: stream through the LiteLLM proxy instead of the
            // local engine. Falls back to local inference when not configured.
            if (_cloudMode.value) {
                if (cloudGateway.resolveChatTarget() == null) {
                    appendErrorMessage("No cloud provider/model configured — add one in Settings → Cloud Providers, or switch back to local mode")
                    return
                }
                runCloudGeneration(messages)
                return
            }

            if (messages.isEmpty()) {
                android.util.Log.e(TAG, "generateFromHistory: empty message list")
                appendErrorMessage("No messages to send")
                return
            }

            // Local tool calling (cloud-style): the model emits native
            // `<|tool_call|>` markers during the answer generation; the loop
            // executes them, feeds the results back and continues — exactly
            // like a cloud provider's function calling. Models without native
            // markers get ONE deduped pre-planner pass as a compatibility
            // fallback. BOUNDED: the loop caps at maxToolRounds and each
            // round is a bounded generation; this outer budget guarantees the
            // turn always finishes.
            // The prompt list is remembered for the one-shot plain-text
            // regeneration when the sanitized answer comes back empty.
            lastLocalMessages = messages
            val planBudgetMs = planBudgetMs(messages)
            android.util.Log.i(TAG, "TOOL LOOP START (budget=${planBudgetMs}ms)")
            if (toolCoordinator.isToolUseEnabled()) {
                val looped = withTimeoutOrNull(planBudgetMs) {
                    runLocalToolLoop(messages)
                } ?: run {
                    android.util.Log.e(
                        TAG,
                        "Tool loop exceeded ${planBudgetMs}ms — committing a direct answer without tool results"
                    )
                    confirmationManager.cancelPending()
                    false
                }
                if (!looped) {
                    android.util.Log.i(TAG, "CHAT GENERATION START messages=${messages.size} addAssistant=true (no tools)")
                    engineRepository.generateChat(
                        messages = messages,
                        addAssistant = true,
                        config = _genConfig.value
                    )
                    android.util.Log.i(TAG, "CHAT GENERATION RETURNED")
                }
            } else {
                android.util.Log.i(TAG, "CHAT GENERATION START messages=${messages.size} addAssistant=true (tools disabled)")
                engineRepository.generateChat(
                    messages = messages,
                    addAssistant = true,
                    config = _genConfig.value
                )
                android.util.Log.i(TAG, "CHAT GENERATION RETURNED")
            }
            android.util.Log.i(TAG, "TOOL LOOP DONE")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Phase 16 — no silent exceptions: the failure reaches the UI and
            // the already-committed user message stays visible.
            android.util.Log.e(TAG, "GENERATION FAILED — ${e.message}", e)
            appendErrorMessage("Local generation failed: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            _preparing.value = false
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
        _preparing.value || _cloudGenerating.value ||
            engineRepository.generationState.value is GenerationState.Generating

    fun sendMessage(content: String) {
        val trimmed = content.trim()
        // A message with attachments may have an empty prompt ("ask about these
        // files"), so only reject a completely empty message with no files.
        if (trimmed.isEmpty() && _pendingAttachments.value.isEmpty()) return

        // Backend enforcement (spec): a request carrying attachments must never
        // reach a local inference engine. Reject immediately — never silently
        // ignore the attachments.
        if (_pendingAttachments.value.isNotEmpty() && !attachmentsSupportedNow()) {
            android.util.Log.w(TAG, "sendMessage rejected: ${_pendingAttachments.value.size} attachment(s) on a local model")
            appendErrorMessage("Attachments are not supported for local models.")
            return
        }

        // Snapshot the composer's attachments for this turn, then clear the
        // chips so the next message starts fresh. The snapshot rides along in
        // the user message metadata (history cards) and its content is
        // packaged into the prompt by [generateFromHistory].
        turnAttachments = _pendingAttachments.value
        _pendingAttachments.value = emptyList()

        // Runtime stabilization: never start a new turn while one is running.
        // A queued/dropped prompt beats a corrupt context.
        if (isGenerationInFlight()) {
            android.util.Log.w(TAG, "sendMessage ignored: generation already in flight")
            return
        }

        // A new user turn supersedes any pending background memory work,
        // any in-flight cloud generation, and any leftover local turn that
        // is still stuck in the invisible planning phase.
        memoryPipelineJob?.cancel()
        cloudJob?.cancel()
        turnJob?.cancel()

        android.util.Log.i(TAG, "SEND CLICK prompt=\"$trimmed\" (${trimmed.length} chars)")

        turnJob = viewModelScope.launch {
            _preparing.value = true
            try {
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
                    timestamp = System.currentTimeMillis(),
                    attachmentsJson = serializeAttachments(turnAttachments)
                )
                android.util.Log.i(TAG, "MESSAGE CREATED id=${userMessage.id} conv=$id")

                // PHASE 2 — commit the user message to UI state IMMEDIATELY.
                // The async Room echo must NEVER be the only path into
                // _messages: a slow/failed upsert, a cancelled Room stream, or
                // a process death mid-write would make the user's own message
                // vanish. Same local-first + pending-tracking pattern as
                // appendAssistantMessage(); the DB observer dedupes by id once
                // Room confirms the row, so the message is rendered and kept
                // regardless of what the persistence layer does afterwards.
                _messages.value = (_messages.value.filterNot { it.id == userMessage.id } + userMessage)
                    .sortedBy { it.timestamp }
                pendingLocalMessageIds += userMessage.id
                android.util.Log.i(TAG, "MESSAGE COMMITTED TO STATE messages=${_messages.value.size}")

                messageRepository.upsert(userMessage.toCoreMessage())
                conversationRepository.updateTitle(id, generateTitleFromMessage(trimmed))
                android.util.Log.i(TAG, "MESSAGE PERSISTED id=${userMessage.id}")

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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Phase 16 — no silent exceptions: the user message stays
                // visible (committed above) and the failure reaches the UI.
                android.util.Log.e(TAG, "SEND FAILED — ${e.message}", e)
                appendErrorMessage("Local generation failed: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                _preparing.value = false
            }
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
        // Tracked as the turn job (like sendMessage) so Stop cancels the whole
        // regenerate — including its invisible planning phase.
        turnJob = viewModelScope.launch {
            try {
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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e(TAG, "REGENERATE FAILED — ${e.message}", e)
                appendErrorMessage("Local generation failed: ${e.message ?: e.javaClass.simpleName}")
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

        turnJob = viewModelScope.launch {
            try {
                messageRepository.truncateAfterTimestamp(targetMsg.conversationId, targetMsg.timestamp)
                pendingLocalMessageIds.removeAll(msgs.map { it.id })
                val updatedMsg = targetMsg.copy(content = newContent.trim(), timestamp = System.currentTimeMillis())
                messageRepository.upsert(updatedMsg.toCoreMessage())

                val remainingMsgs = msgs.takeWhile { it.id != messageId } + updatedMsg
                traceStore.beginTurn(newContent.trim())
                variableStore.beginTurn(_currentConversationId.value)
                toolsExecutedThisTurn = false
                generateFromHistory(remainingMsgs)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e(TAG, "EDIT PROMPT FAILED — ${e.message}", e)
                appendErrorMessage("Local generation failed: ${e.message ?: e.javaClass.simpleName}")
            }
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
            // Conversation-scoped attachment cache goes with the conversation.
            runCatching { AttachmentCache.clearConversation(appContext, conversationId) }
            if (_currentConversationId.value == conversationId) {
                _currentConversationId.value = ""
                clearPendingAttachments()
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
        // Kill the whole local turn, including the invisible tool-planning
        // phase, so a Stop press always takes effect immediately.
        turnJob?.cancel()
        _preparing.value = false
        viewModelScope.launch {
            engineRepository.cancelGeneration()
        }
    }

    /**
     * Flips the Local GGUF / Cloud (LiteLLM) chat mode. Purely a UI toggle:
     * cloud failures never touch the local engine, so switching back is
     * instant.
     *
     * Cloud → local with pending attachments asks first: attachments are
     * cloud-only, so switching would discard them. The dialog flows through
     * [confirmSwitchToLocal].
     */
    fun toggleCloudMode() {
        val enable = !_cloudMode.value
        // Cloud → local with attachments staged in the composer: confirm before
        // discarding them (spec: "switching to a local model will remove the
        // current attachments").
        if (!enable && _pendingAttachments.value.isNotEmpty()) {
            _cloudToLocalConfirm.value = true
            return
        }
        // Switching back to local stops any in-flight cloud stream immediately.
        if (!enable) cloudJob?.cancel()
        viewModelScope.launch {
            cloudGateway.setCloudModeEnabled(enable)
        }
    }

    /**
     * Resolves a pending cloud→local switch confirmation. On confirm: clear
     * pending attachments + the conversation's temporary cache, then switch.
     * On cancel: keep everything as-is.
     */
    fun confirmSwitchToLocal(confirmed: Boolean) {
        if (!_cloudToLocalConfirm.value) return
        _cloudToLocalConfirm.value = false
        if (!confirmed) return
        // Remove pending attachments and the conversation's cached copies.
        _pendingAttachments.value = emptyList()
        turnAttachments = emptyList()
        val convId = _currentConversationId.value
        if (convId.isNotBlank()) {
            viewModelScope.launch {
                io.androllm.core.attachments.AttachmentCache.clearConversation(appContext, convId)
            }
        }
        cloudJob?.cancel()
        viewModelScope.launch {
            cloudGateway.setCloudModeEnabled(false)
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
            // Native image attachment for vision providers: rebuild the LAST
            // user message as a multimodal content array (text + image data
            // URIs). Non-vision providers already received the OCR text via
            // the attachment context block injected by generateFromHistory —
            // we never fake vision by sending image parts to a text model.
            val resolvedTarget = runCatching { cloudGateway.resolveChatTarget() }.getOrNull()
            val visionSupported = ProviderCapabilities.supportsVision(resolvedTarget?.second)
            if (visionSupported) {
                val imageAttachments = turnAttachments.filter {
                    it.isReady && it.type == io.androllm.core.attachments.model.AttachmentType.IMAGE
                }
                if (imageAttachments.isNotEmpty() && history.isNotEmpty()) {
                    val lastIndex = history.size - 1
                    val last = history[lastIndex]
                    if (last.role == "user") {
                        val dataUris = imageAttachments.mapNotNull { imageDataUri(it) }
                        if (dataUris.isNotEmpty()) {
                            val lastContent = last.content.orEmpty()
                            val parts = buildList {
                                if (lastContent.isNotBlank()) add(CloudContentPart.Text(lastContent))
                                dataUris.forEach { add(CloudContentPart.Image(it)) }
                            }
                            history[lastIndex] = CloudChatMessage(
                                role = "user",
                                content = null,
                                contentParts = parts
                            )
                        }
                    }
                }
            }
            // ROUTED tools: only the tools relevant to THIS request (math →
            // calculator only, device → device tools, attachments → none).
            // The model can never call a tool "just in case" because it never
            // sees the others. Blank fallback = full set (defensive).
            val routeQuery = messages.lastOrNull { it.role == "user" }?.content.orEmpty()
            val routeHasAttachments = turnAttachments.isNotEmpty()
            val tools = runCatching {
                toolCoordinator.cloudTools(routeQuery, routeHasAttachments)
            }.getOrDefault(emptyList())
            // Agent context (device facts + workflow variables) injected in
            // front of the tool-calling rounds so the model plans with the
            // real device state instead of asking the user.
            if (tools.isNotEmpty()) {
                toolCoordinator.agentContextMessage()?.let {
                    history.add(0, CloudChatMessage(role = "system", content = it.content))
                }
                // NOTE: the tool advertisement is already part of [messages]
                // (injected by the Prompt Builder in generateFromHistory) —
                // adding it again here would double the token cost.
            }
            // Tool rounds and continuation rounds share one loop but have
            // separate budgets: the loop runs maxRounds + continuation cap so
            // a long truncated answer never eats a tool round (the guard caps
            // actual tool calls at 5 regardless).
            val answerBuffer = StringBuilder()
            val streamStartedAt = System.currentTimeMillis()
            // Provider-aware max output tokens (resolved ONCE — the provider
            // cannot change mid-turn): when the user left maxTokens at the
            // "unlimited" sentinel, use the provider's own maximum from
            // /v1/model/info when known, else omit the field entirely so the
            // provider uses its default — never an artificial 8k ceiling that
            // truncates long answers mid-tool-result.
            val providerMax = runCatching { cloudGateway.maxOutputTokensFor() }.getOrNull()
            var callsExecuted = false
            try {
                callsExecuted = runCloudRounds(
                    history = history,
                    tools = tools,
                    providerMaxTokens = providerMax,
                    answerBuffer = answerBuffer
                )
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
            android.util.Log.i(
                TAG,
                "CLOUD LOOP done: chars=${answerBuffer.length} streamingMs=${System.currentTimeMillis() - streamStartedAt}"
            )
            _cloudGenerating.value = false
            _cloudStreamingText.value = null
            var text = OutputSanitizer.sanitize(answerBuffer.toString())
            if (text.isBlank() && !plainTextRetryUsed) {
                // Sanitization emptied the answer (a control-token-only turn):
                // regenerate ONCE with a plain-text-only instruction and no
                // tools advertised.
                plainTextRetryUsed = true
                android.util.Log.w(TAG, "Cloud output sanitized to empty — regenerating once with a plain-text-only instruction")
                history += CloudChatMessage(role = "system", content = OutputSanitizer.PLAIN_TEXT_RETRY_INSTRUCTION)
                answerBuffer.clear()
                runCloudRounds(
                    history = history,
                    tools = emptyList(),
                    providerMaxTokens = providerMax,
                    answerBuffer = answerBuffer
                )
                text = OutputSanitizer.sanitize(answerBuffer.toString())
            }
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
     * One cloud tool round-trip loop: streams chat rounds through
     * [CloudGateway.streamChat], executes tool calls through the gated
     * executor, and feeds results back — until the model answers without
     * tools. Streamed text is sanitized before it is surfaced
     * ([OutputSanitizer.streamingReady] holds back partial control tags so
     * an unfinished `<tool_call` can never appear in the UI). When the model
     * emits tool calls but [tools] is empty (NO tool executor exists), the
     * calls are DISCARDED and the model is told to answer in plain text.
     * Returns true when at least one tool call was executed.
     */
    private suspend fun runCloudRounds(
        history: MutableList<CloudChatMessage>,
        tools: List<CloudTool>,
        providerMaxTokens: Long?,
        answerBuffer: StringBuilder
    ): Boolean {
        // Per-turn loop protection (spec: max 5 calls, max 2 consecutive
        // same tool, identical calls never re-run, failing tools disabled).
        val loopGuard = io.androllm.core.tools.coordinator.ToolLoopGuard()
        val maxRounds = runCatching { automationSettingsStore.current().maxToolRounds }.getOrDefault(3)
        // Tool rounds and continuation rounds share one loop but have
        // separate budgets: the loop runs maxRounds + continuation cap so
        // a long truncated answer never eats a tool round (the guard caps
        // actual tool calls at 5 regardless).
        val maxLoopRounds = maxRounds + MAX_CLOUD_CONTINUATIONS
        // Throttle UI emissions to ~60fps (see the throttle rationale in
        // [runCloudGeneration]).
        var lastEmitTime = 0L
        var callsExecuted = false
        var lastFinishReason: String? = null
        var continuationCount = 0
        round@ for (round in 0 until maxLoopRounds) {
            val roundBuffer = StringBuilder()
            val calls = LinkedHashMap<Int, AccumulatedToolCall>()
            cloudGateway.streamChat(
                messages = history,
                config = cloudGenerationConfig(tools = tools, providerMaxTokens = providerMaxTokens)
            ).collect { event ->
                when (event) {
                    is CloudStreamEvent.Delta -> {
                        roundBuffer.append(event.text)
                        val now = System.currentTimeMillis()
                        if (now - lastEmitTime >= 16L) {
                            lastEmitTime = now
                            _cloudStreamingText.value = OutputSanitizer.streamingReady(roundBuffer.toString())
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
                    // The provider's terminal signal. `length` means
                    // the output hit the token ceiling mid-answer — we
                    // MUST continue instead of accepting a truncated
                    // reply. `stop`/`end_turn`/`tool_calls` end the
                    // round normally.
                    is CloudStreamEvent.Finish -> lastFinishReason = event.reason
                    CloudStreamEvent.Done -> Unit
                }
            }
            if (calls.isNotEmpty()) {
                if (tools.isEmpty()) {
                    // The model attempted a tool call but NO tool executor
                    // exists (tools were never advertised): discard the call
                    // entirely and continue the conversation in plain text —
                    // the model is told to answer without tools.
                    android.util.Log.w(
                        TAG,
                        "CLOUD: model emitted ${calls.size} tool call(s) with no executor — discarding and continuing in plain text"
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
                val toolMessages = toolCoordinator.executeCloudToolCalls(
                    cloudCalls,
                    assistantContent = interimText.takeIf { it.isNotBlank() },
                    onEvent = ::onToolEvent,
                    guard = loopGuard
                )
                _toolActivity.value = null
                if (toolMessages.isEmpty() && loopGuard.blockedThisTurn) {
                    // Every call this round was blocked by the loop
                    // guard (identical re-call, consecutive cap, total
                    // cap, or a disabled tool): abort tool execution and
                    // tell the model to answer WITHOUT further calls.
                    android.util.Log.w(TAG, "CLOUD LOOP: all calls blocked by guard — stopping tool rounds")
                    history += CloudChatMessage(
                        role = "system",
                        content = loopGuard.stopReason()
                            ?: "The requested tools have already been used and produced no additional useful information. Continue reasoning without further tool calls."
                    )
                    break@round
                }
                history += toolMessages
                callsExecuted = true
                continue@round
            }
            // No tool calls: this is the final answer round. Append it
            // and keep streaming if the provider cut the output short.
            val finalText = roundBuffer.toString()
            answerBuffer.append(finalText)
            val truncated = lastFinishReason == "length" || lastFinishReason == "max_tokens"
            if (truncated && finalText.isNotBlank() && continuationCount < MAX_CLOUD_CONTINUATIONS) {
                continuationCount++
                android.util.Log.i(
                    TAG,
                    "CLOUD LOOP: finish=$lastFinishReason — requesting continuation $continuationCount/$MAX_CLOUD_CONTINUATIONS"
                )
                // Feed the partial answer back so the model continues
                // from where it stopped; the merged text stays one
                // continuous response in answerBuffer.
                history += CloudChatMessage(role = "assistant", content = finalText)
                history += CloudChatMessage(role = "user", content = "Continue.")
                continue@round
            }
            if (truncated) {
                android.util.Log.w(
                    TAG,
                    "CLOUD LOOP: finish=$lastFinishReason with ${answerBuffer.length} chars buffered and no continuation budget"
                )
            }
            break@round
        }
        return callsExecuted
    }

    /**
     * Cloud-style local tool loop: the model emits native `<|tool_call|>`
     * markers during the answer generation; each round executes them through
     * the gated executor, feeds the results back as a system message and
     * continues the SAME conversation — until the model answers without
     * tools. Returns true when the loop committed the answer itself.
     *
     * Families WITHOUT native markers (the engine's `nativeToolMarkers` flag)
     * get ONE legacy pre-planner pass BEFORE the first generation
     * (compatibility for models without native function calling); planned
     * calls are deduped against already-executed ones so a confused model can
     * never re-run the same tool every round. Running it before round 1 keeps
     * the streamed answer final: it is committed the moment generation ends,
     * never hidden while a slow planning pass runs afterwards.
     */
    private suspend fun runLocalToolLoop(messages: List<ChatPromptMessage>): Boolean {
        nativeToolLoopActive = true
        try {
            var history = messages
            val maxRounds = runCatching { automationSettingsStore.current().maxToolRounds }.getOrDefault(3)
            // Per-turn loop protection shared by the pre-planner and every
            // native round: total cap 5, consecutive-same-tool cap 2, dedupe
            // of identical (name, arguments) calls, disable-on-repeated-failure.
            val loopGuard = io.androllm.core.tools.coordinator.ToolLoopGuard()

            // Compatibility pre-planner: only for families WITHOUT native
            // `<|tool_call|>` markers (Qwen3/2.5/2 and the function-calling
            // Gemma repacks emit them natively — an answer without markers is
            // authoritative there, so the slow JSON planner must never run).
            // It runs BEFORE the first generation so the round-1 answer is
            // always final: once text streams it is committed immediately and
            // can never "vanish" behind a ~20s planning pass that commits the
            // same text much later.
            val nativeMarkers = (engineRepository.engineState.value as? EngineState.Ready)
                ?.model?.nativeToolMarkers
            if (nativeMarkers != true) {
                android.util.Log.i(TAG, "NATIVE LOOP: compat pre-planner (nativeMarkers=$nativeMarkers)")
                val planned = toolCoordinator.planLocal(
                    history,
                    hasAttachments = turnAttachments.isNotEmpty()
                )
                val fresh = planned.filter { loopGuard.canExecute(it.name, it.arguments) }
                if (fresh.isNotEmpty()) {
                    android.util.Log.i(TAG, "NATIVE LOOP: pre-planner returned ${fresh.size} call(s)")
                    val records = executeToolCallsWithStatus(fresh, loopGuard)
                    if (records.isNotEmpty()) {
                        toolsExecutedThisTurn = true
                        history = history + toolCoordinator.buildLocalToolFeedback(records)
                    }
                }
            }

            for (round in 0 until maxRounds) {
                android.util.Log.i(TAG, "NATIVE LOOP round=${round + 1}/$maxRounds messages=${history.size}")
                engineRepository.generateChat(
                    messages = history,
                    addAssistant = true,
                    config = _genConfig.value
                )
                val state = engineRepository.generationState.value
                if (state is GenerationState.Failed) {
                    // The collector already surfaced the error.
                    return true
                }
                if (state !is GenerationState.Completed) {
                    android.util.Log.w(TAG, "NATIVE LOOP: no Completed state after round ${round + 1}")
                    return true
                }
                val text = state.text
                val nativeCalls = engineRepository.takeLastNativeToolCalls()
                android.util.Log.i(
                    TAG,
                    "NATIVE LOOP round=${round + 1} textLen=${text.length} nativeCalls=${nativeCalls.size}"
                )

                if (nativeCalls.isEmpty()) {
                    // No native markers: the round-1 answer is the final one.
                    // (The compat planner, when needed, already ran before the
                    // loop — its calls were executed and their results fed
                    // back, so nothing is re-planned or re-executed here.)
                    commitLocalAnswer(text)
                    return true
                }

                // Native markers found: execute, feed the results back and
                // continue the same conversation (cloud-style round trip).
                val calls = nativeToToolCalls(nativeCalls)
                val records = executeToolCallsWithStatus(calls, loopGuard)
                if (records.isEmpty()) {
                    if (loopGuard.blockedThisTurn) {
                        // Every native call this round was blocked (identical
                        // re-call, consecutive cap, total cap, disabled tool):
                        // tell the model to continue reasoning WITHOUT tools
                        // and let it produce the final answer.
                        android.util.Log.w(TAG, "LOCAL LOOP: all calls blocked by guard — asking model to answer without tools")
                        val guardMessage = loopGuard.stopReason()
                            ?: "The requested tools have already been used and produced no additional useful information. Continue reasoning without further tool calls."
                        history = history + listOf(
                            ChatPromptMessage(role = "assistant", content = text),
                            ChatPromptMessage(role = "system", content = guardMessage)
                        )
                        engineRepository.generateChat(
                            messages = history,
                            addAssistant = true,
                            config = _genConfig.value
                        )
                        val finalState = engineRepository.generationState.value
                        if (finalState is GenerationState.Completed) {
                            commitLocalAnswer(finalState.text)
                        } else {
                            commitLocalAnswer(text)
                        }
                        return true
                    }
                    commitLocalAnswer(text)
                    return true
                }
                toolsExecutedThisTurn = true
                val assistantTurn = if (text.isNotBlank()) {
                    listOf(ChatPromptMessage(role = "assistant", content = text))
                } else {
                    emptyList()
                }
                history = history + assistantTurn + toolCoordinator.buildLocalToolFeedback(records)
            }
            // Round cap reached: commit whatever the last round produced.
            val last = engineRepository.generationState.value
            if (last is GenerationState.Completed) commitLocalAnswer(last.text)
            return true
        } finally {
            nativeToolLoopActive = false
        }
    }

    /** Executes calls with a status chip + tool cards; returns the records. */
    private suspend fun executeToolCallsWithStatus(
        calls: List<ToolCall>,
        guard: io.androllm.core.tools.coordinator.ToolLoopGuard? = null
    ): List<ToolExecutionRecord> {
        if (calls.isEmpty()) return emptyList()
        _toolActivity.value = "Running ${calls.size} tool call${if (calls.size == 1) "" else "s"}…"
        val records = toolCoordinator.executeCalls(calls, onEvent = ::onToolEvent, guard = guard)
        _toolActivity.value = null
        return records
    }

    /**
     * Commits the final local answer exactly like the generationState
     * collector would (sanitization + never-blank guard + memory pipeline),
     * used by the native tool loop for the terminal round.
     */
    private suspend fun commitLocalAnswer(text: String) {
        // The generationState collector may resume on the same Completed
        // emission after the loop finished; both paths share this guard so
        // the answer is never appended twice.
        if (localTurnCommitted) return
        localTurnCommitted = true
        var answer = OutputSanitizer.sanitize(text)
        if (answer.isBlank() && toolsExecutedThisTurn) {
            answer = traceStore.lastTurnSummary()
            android.util.Log.w(TAG, "Local turn empty after tool calls — injected tool-summary reply")
        } else if (answer.isBlank()) {
            // Control-token-only round: one plain-text regeneration.
            answer = regeneratePlainText()
        }
        toolsExecutedThisTurn = false
        appendAssistantMessage(answer)
        runMemoryPipeline(answer)
    }

    /**
     * One-shot plain-text regeneration: re-runs the current local turn's
     * history with a system instruction demanding plain text only, exactly
     * once per turn. Called when sanitization emptied the answer (the model
     * emitted only control tokens / tags / tool-call markers). Returns the
     * sanitized retry text ("" when the retry also produced nothing usable).
     */
    private suspend fun regeneratePlainText(): String {
        if (plainTextRetryUsed || lastLocalMessages.isEmpty()) return ""
        plainTextRetryUsed = true
        android.util.Log.w(TAG, "Sanitized output was empty — regenerating once with a plain-text-only instruction")
        val retryMessages = lastLocalMessages +
            ChatPromptMessage(role = "system", content = OutputSanitizer.PLAIN_TEXT_RETRY_INSTRUCTION)
        engineRepository.generateChat(
            messages = retryMessages,
            addAssistant = true,
            config = _genConfig.value
        )
        val retryState = engineRepository.generationState.value
        return if (retryState is GenerationState.Completed) {
            OutputSanitizer.sanitize(retryState.text)
        } else {
            ""
        }
    }

    /** Converts engine-native markers into executor-ready [ToolCall]s. */
    private fun nativeToToolCalls(calls: List<NativeToolCall>): List<ToolCall> =
        calls.map { native ->
            ToolCall(
                id = "native_${native.name.hashCode().toUInt().toString(16)}",
                name = native.name,
                arguments = runCatching {
                    Json.parseToJsonElement(native.argumentsJson).jsonObject
                }.getOrElse { JsonObject(emptyMap()) }
            )
        }

    /**
     * Applies one per-tool lifecycle event to the chat tool-card list: a
     * [ToolEvent.Started] appends a RUNNING card, the terminal events update
     * the matching RUNNING card in place (so cards never duplicate). The list
     * is capped to the last [MAX_TOOL_CARDS] entries of the current turn.
     */
    private fun onToolEvent(event: ToolEvent) {
        val list = _toolEvents.value.toMutableList()
        when (event) {
            is ToolEvent.Started -> list.add(
                ToolInvocationUi(name = event.name, arguments = event.arguments)
            )

            is ToolEvent.Succeeded -> updateToolCard(
                list, event.name, ToolInvocationStatus.SUCCESS, summary = event.summary
            )

            is ToolEvent.Failed -> updateToolCard(
                list, event.name, ToolInvocationStatus.FAILED, error = event.error
            )

            is ToolEvent.Declined -> updateToolCard(
                list, event.name, ToolInvocationStatus.DECLINED
            )
        }
        _toolEvents.value = list.takeLast(MAX_TOOL_CARDS)
    }

    /** Updates the most recent RUNNING card for [name] to its terminal state. */
    private fun updateToolCard(
        list: MutableList<ToolInvocationUi>,
        name: String,
        status: ToolInvocationStatus,
        summary: String = "",
        error: String = ""
    ) {
        val idx = list.indexOfLast { it.name == name && it.status == ToolInvocationStatus.RUNNING }
        if (idx >= 0) {
            list[idx] = list[idx].copy(status = status, summary = summary, error = error)
        }
    }

    /**
     * Maps the chat sampler settings onto the OpenAI-compatible request.
     * [providerMaxTokens] is the resolved provider maximum output (null when
     * unknown) — the request then omits `max_tokens` so the provider uses its
     * own default instead of an artificial ceiling.
     */
    private fun cloudGenerationConfig(
        tools: List<CloudTool> = emptyList(),
        providerMaxTokens: Long? = null
    ): CloudGenerationConfig {
        val gen = _genConfig.value
        val maxTokens = when {
            // User explicitly set a bounded value → honor it.
            gen.maxTokens < UNLIMITED_MAX_TOKENS_SENTINEL -> gen.maxTokens.coerceAtLeast(1)
            providerMaxTokens != null && providerMaxTokens > 0 -> providerMaxTokens.toInt()
            else -> null // omit → provider default (unlimited semantics)
        }
        return CloudGenerationConfig(
            temperature = gen.temperature.toDouble(),
            topP = gen.topP.toDouble(),
            topK = gen.topK.takeIf { it > 0 },
            maxTokens = maxTokens,
            seed = gen.seed.takeIf { it >= 0 },
            stop = gen.stopSequences,
            tools = tools
        )
    }

    private var memoryPipelineJob: Job? = null
    private var lastProcessedExchangeKey: String? = null

    /**
     * Outer budget for the whole local tool-planning phase (planner rounds +
     * tool execution), scaled to the conversation length. Floor
     * [LOCAL_PLAN_BUDGET_MS], cap 240s. Estimated tokens = chars / 4 (same
     * heuristic as the engine's first-token watchdog), +50ms each; a 10K-char
     * planner prompt needs ~135s of CPU prefill headroom, which a fixed 45s
     * budget never allowed. ToolPlanner additionally bounds each individual
     * inference pass; this cap guarantees the answer generation always starts
     * within a bounded time even when a planner or a tool hangs.
     */
    private fun planBudgetMs(messages: List<ChatPromptMessage>): Long {
        val promptChars = messages.sumOf { (it.content ?: "").length }
        return (LOCAL_PLAN_BUDGET_MS + promptChars / 4 * 50L)
            .coerceIn(LOCAL_PLAN_BUDGET_MS, 240_000L)
    }

    companion object {
        private const val TAG = "ChatViewModel"

        /**
         * Floor for the outer local tool-planning budget (see [planBudgetMs]).
         * ToolPlanner bounds each inference pass; this cap guarantees the
         * answer generation always starts within a bounded time even when a
         * planner or a tool hangs.
         */
        private const val LOCAL_PLAN_BUDGET_MS = 45_000L

        /**
         * Hard ceiling for continuation rounds after a `finish_reason=length`
         * truncation — the provider keeps producing until it stops naturally,
         * but a broken provider must never loop forever.
         */
        private const val MAX_CLOUD_CONTINUATIONS = 4

        /**
         * Sentinel for "unlimited" output tokens — the local engine's
         * GenerationConfig default. Values below this are explicit user
         * bounds; at/above it we defer to the provider's maximum.
         */
        private const val UNLIMITED_MAX_TOKENS_SENTINEL = 65536

        /** Budget for memory retrieval on the send path (retrieval is <20ms when warm). */
        private const val MEMORY_RETRIEVAL_TIMEOUT_MS = 400L

        /**
         * Per-file cap for attachment text injected into the prompt. A huge
         * document could otherwise blow the context window; the tail is what
         * matters for a chat answer, and the model can ask for the rest.
         */
        private const val MAX_ATTACHMENT_CHARS_PER_FILE = 12_000

        /** Max tool cards kept in the chat for one turn (oldest dropped). */
        private const val MAX_TOOL_CARDS = 8

        /**
         * Reserved output budget for [ChatHistoryTrimmer]: generation must
         * never be starved by the history window, but reserving the full
         * maxTokens (default 65536 = "unlimited") would leave nothing for
         * conversation history. Clamped to a chat-reasonable span.
         */
        private const val MIN_OUTPUT_RESERVE_TOKENS = 256
        private const val MAX_OUTPUT_RESERVE_TOKENS = 4096

        /**
         * Fixed chat-template framing overhead (system-role markers, BOS/EOS
         * framing) on top of the measured memory + tool-advertisement tokens.
         */
        private const val SYSTEM_TEMPLATE_FRAMING_TOKENS = 64

        /**
         * Minimum history floor (tokens) kept aside from the system-prompt
         * budget so the current user message + a short reply always fit the
         * container's real context even when it is small (2048).
         */
        private const val MIN_HISTORY_TOKENS = 128

        /** Rough chars-per-token used to translate the token budget into a char budget. */
        private const val CHARS_PER_TOKEN_ESTIMATE = 4

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
        // Sanitization funnel: every final assistant message (local AND cloud,
        // committed by the collector, the tool loop or the cloud rounds) must
        // pass the sanitizer before it is rendered or persisted — no control
        // token, tool-call marker or reasoning artifact can ever reach the UI.
        val trimmed = OutputSanitizer.sanitize(text).trim()
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

    /**
     * Serializes the attachments of the current turn into the JSON stored on
     * the message ("" when none). Best-effort: a serialization failure
     * degrades to no attachment cards rather than failing the send.
     */
    private fun serializeAttachments(attachments: List<ChatAttachment>): String =
        if (attachments.isEmpty()) ""
        else runCatching { ChatAttachmentJson.encodeToString(attachments) }.getOrDefault("")

    /**
     * Builds a base64 data URI for an image attachment so vision providers
     * receive it through their native image API. Returns null when the file
     * is missing or unreadable (falls back to the OCR text already injected).
     */
    private fun imageDataUri(attachment: ChatAttachment): String? = runCatching {
        val file = File(attachment.filePath)
        if (!file.exists()) return@runCatching null
        val mime = attachment.mimeType.ifBlank { "image/jpeg" }
        val bytes = file.readBytes()
        "data:$mime;base64," + java.util.Base64.getEncoder().encodeToString(bytes)
    }.getOrNull()
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
        val isPreparing: Boolean = false,
        val streamingText: String? = null,
        val userPreferences: UserPreferences = UserPreferences(),
        val searchQuery: String = "",
        val isSearchOpen: Boolean = false,
        val cloudMode: Boolean = false,
        val cloudDefaultModel: String = "",
        /** Files attached to the message being composed (chips above composer). */
        val pendingAttachments: List<ChatAttachment> = emptyList(),
        /** True while picked files are being copied/parsed. */
        val attachmentsProcessing: Boolean = false,
        /**
         * True when the active model supports the attachment pipeline (cloud
         * only). Drives the paperclip visibility, message-card interaction and
         * settings visibility — never a provider-name check.
         */
        val attachmentsSupported: Boolean = false,
        /** True while a cloud→local switch with pending attachments awaits confirmation. */
        val confirmCloudToLocalSwitch: Boolean = false,
        val pendingToolConfirmation: PendingToolConfirmation? = null,
        val toolActivity: String? = null,
        val toolEvents: List<ToolInvocationUi> = emptyList()
    ) : ChatUiState

    data class Error(val throwable: Throwable) : ChatUiState
}

/** Live status of one tool card in the chat. */
enum class ToolInvocationStatus { RUNNING, SUCCESS, FAILED, DECLINED }

/**
 * One tool call rendered as an expandable chat card: name, rendered
 * arguments, live status and the terminal summary/error from the executor.
 */
data class ToolInvocationUi(
    val name: String,
    val arguments: String = "",
    val status: ToolInvocationStatus = ToolInvocationStatus.RUNNING,
    val summary: String = "",
    val error: String = ""
)

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
    val origin: MessageOrigin = MessageOrigin.TYPED,
    /**
     * Files attached to this message, serialized as a JSON array of
     * [io.androllm.core.attachments.model.ChatAttachment] ("" = none).
     * Rendered as attachment cards under the bubble; tapping one opens the
     * original file from the conversation cache.
     */
    val attachmentsJson: String = ""
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
    val toolActivity: String? = null,
    val toolEvents: List<ToolInvocationUi> = emptyList(),
    val isPreparing: Boolean = false,
    val pendingAttachments: List<ChatAttachment> = emptyList(),
    val attachmentsProcessing: Boolean = false,
    val confirmCloudToLocalSwitch: Boolean = false
)

private fun Message.toChatMessage(): ChatMessage = ChatMessage(
    id = id,
    conversationId = conversationId,
    role = role,
    content = content,
    timestamp = timestamp,
    isBookmarked = isBookmarked,
    origin = origin,
    attachmentsJson = attachmentsJson
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
    origin = origin,
    attachmentsJson = attachmentsJson
)
