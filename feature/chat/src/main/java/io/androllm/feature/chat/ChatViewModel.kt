package io.androllm.feature.chat

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.androllm.core.common.BaseViewModel
import io.androllm.core.common.getOrNull
import io.androllm.core.database.repository.ConversationRepository
import io.androllm.core.database.repository.MessageRepository
import io.androllm.core.datastore.PreferencesDataStore
import io.androllm.core.datastore.UserPreferences
import io.androllm.core.models.Conversation
import io.androllm.core.models.Message
import io.androllm.core.models.MessageRole
import io.androllm.engine.api.EngineRepository
import io.androllm.engine.api.EngineState
import io.androllm.engine.api.GenerationState
import io.androllm.engine.models.ChatPromptMessage
import io.androllm.engine.models.EngineDebugInfo
import io.androllm.engine.models.EngineStats
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
    private val preferencesDataStore: PreferencesDataStore
) : BaseViewModel() {

    private val _currentConversationId = MutableStateFlow<String>("")
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    private val _searchQuery = MutableStateFlow<String>("")
    private val _isSearchOpen = MutableStateFlow<Boolean>(false)
    private val _debugInfo = MutableStateFlow<EngineDebugInfo?>(null)

    val debugInfo: StateFlow<EngineDebugInfo?> = _debugInfo

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
            _isSearchOpen
        ) { userPrefs, searchQuery, isSearchOpen ->
            Triple(userPrefs, searchQuery, isSearchOpen)
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
        val (userPrefs, searchQuery, isSearchOpen) = searchData

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
            isGenerating = genState is GenerationState.Generating,
            streamingText = (genState as? GenerationState.Generating)?.streamingText,
            userPreferences = userPrefs,
            searchQuery = searchQuery,
            isSearchOpen = isSearchOpen
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = ChatUiState.Loading
    )

    init {
        observeEngine()
        observeActiveMessages()
    }

    private fun observeEngine() {
        viewModelScope.launch {
            engineRepository.initialize()
        }

        viewModelScope.launch {
            engineRepository.generationState.collect { genState ->
                when (genState) {
                    is GenerationState.Completed -> appendAssistantMessage(genState.text)
                    is GenerationState.Failed -> appendErrorMessage(genState.message)
                    else -> Unit
                }
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
        val messages = history.map {
            ChatPromptMessage(
                role = it.role.toTemplateRole(),
                content = it.content.trim()
            )
        }
        viewModelScope.launch {
            val prompt = engineRepository.buildChatPrompt(messages)
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: fallbackRawPrompt(messages)
            engineRepository.generate(prompt = prompt)
        }
    }

    fun sendMessage(content: String) {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) return

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
        viewModelScope.launch {
            engineRepository.cancelGeneration()
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

    private fun fallbackRawPrompt(messages: List<ChatPromptMessage>): String = buildString {
        for (message in messages) {
            val name = when (message.role) {
                "user" -> "User"
                "assistant" -> "Assistant"
                "system" -> "System"
                else -> message.role.replaceFirstChar { it.uppercase() }
            }
            append(name).append(": ").append(message.content).append("\n\n")
        }
        append("Assistant: ")
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
        val isSearchOpen: Boolean = false
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
