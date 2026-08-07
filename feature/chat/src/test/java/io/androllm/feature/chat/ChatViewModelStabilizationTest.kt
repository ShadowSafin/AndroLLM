package io.androllm.feature.chat

import io.androllm.core.cloud.CloudGateway
import io.androllm.core.cloud.model.CloudSettings
import io.androllm.core.database.repository.ConversationRepository
import io.androllm.core.database.repository.MessageRepository
import io.androllm.core.datastore.PreferencesDataStore
import io.androllm.core.datastore.UserPreferences
import io.androllm.core.memory.MemoryManager
import io.androllm.core.memory.model.MemoryContext
import io.androllm.core.memory.model.MemorySettings
import io.androllm.core.models.Message
import io.androllm.core.models.MessageRole
import io.androllm.engine.api.EngineRepository
import io.androllm.engine.api.EngineState
import io.androllm.engine.api.GenerationState
import io.androllm.engine.models.BackendType
import io.androllm.engine.models.EngineCapabilities
import io.androllm.engine.models.EngineStats
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Runtime Stabilization tests for [ChatViewModel]: the send-during-generation
 * guard (prevents two consecutive user messages → template corruption) and the
 * pending-message merge (a stale Room emission can never drop the assistant
 * response before the next prompt is built).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelStabilizationTest {

    private val dispatcher = UnconfinedTestDispatcher()

    private val engineRepository = mockk<EngineRepository>()
    private val conversationRepository = mockk<ConversationRepository>()
    private val messageRepository = mockk<MessageRepository>()
    private val preferencesDataStore = mockk<PreferencesDataStore>()
    private val memoryManager = mockk<MemoryManager>(relaxed = true)
    private val cloudGateway = mockk<CloudGateway>(relaxed = true)

    private val engineState = MutableStateFlow<EngineState>(EngineState.Unloaded)
    private val generationState = MutableStateFlow<GenerationState>(GenerationState.Idle)
    private val performanceStats = MutableStateFlow<EngineStats?>(null)

    /** Emulated Room stream for the active conversation. */
    private val dbMessages = MutableStateFlow<List<Message>>(emptyList())

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        coEvery { engineRepository.initialize() } returns io.androllm.core.common.Result.Success(Unit)
        coEvery { engineRepository.buildChatPrompt(any(), any()) } returns io.androllm.core.common.Result.Success("<|im_start|>user\nhi<|im_end|>\n<|im_start|>assistant\n")
        coEvery { engineRepository.generate(any(), any()) } returns io.androllm.core.common.Result.Success(Unit)
        coEvery { engineRepository.generateChat(any(), any(), any()) } returns io.androllm.core.common.Result.Success(Unit)
        coEvery { engineRepository.cancelGeneration() } returns io.androllm.core.common.Result.Success(Unit)
        coEvery { engineRepository.unloadModel() } returns io.androllm.core.common.Result.Success(Unit)

        every { conversationRepository.observeActive() } returns flowOf(emptyList())
        every { conversationRepository.observePinned() } returns flowOf(emptyList())
        every { messageRepository.observeByConversationId(any()) } returns dbMessages
        every { preferencesDataStore.userPreferences } returns flowOf(UserPreferences())

        coEvery { conversationRepository.upsert(any()) } returns io.androllm.core.common.Result.Success("id")
        coEvery { conversationRepository.setPinned(any(), any()) } returns io.androllm.core.common.Result.Success(Unit)
        coEvery { conversationRepository.setArchived(any(), any()) } returns io.androllm.core.common.Result.Success(Unit)
        coEvery { conversationRepository.updateTitle(any(), any()) } returns io.androllm.core.common.Result.Success(Unit)
        coEvery { conversationRepository.deleteById(any()) } returns io.androllm.core.common.Result.Success(Unit)
        coEvery { messageRepository.upsert(any()) } returns io.androllm.core.common.Result.Success(Unit)
        coEvery { messageRepository.deleteById(any()) } returns io.androllm.core.common.Result.Success(Unit)
        coEvery { messageRepository.setBookmarked(any(), any()) } returns io.androllm.core.common.Result.Success(Unit)
        coEvery { messageRepository.truncateAfterTimestamp(any(), any()) } returns io.androllm.core.common.Result.Success(Unit)
        coEvery { memoryManager.currentSettings() } returns MemorySettings()
        coEvery { memoryManager.buildContext(any(), any(), any(), any()) } returns MemoryContext()
        every { cloudGateway.settings } returns flowOf(CloudSettings())
        coEvery { cloudGateway.resolveChatTarget() } returns null
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(): ChatViewModel {
        every { engineRepository.engineState } returns engineState
        every { engineRepository.generationState } returns generationState
        every { engineRepository.performanceStats } returns performanceStats
        every { engineRepository.capabilities } returns EngineCapabilities(
            name = "test",
            version = "1",
            backend = BackendType.CPU
        )
        coEvery { engineRepository.resetChat() } returns io.androllm.core.common.Result.Success(Unit)
        return ChatViewModel(
            engineRepository,
            conversationRepository,
            messageRepository,
            preferencesDataStore,
            memoryManager,
            cloudGateway
        )
    }

    @Test
    fun `sendMessage is ignored while generation is in flight`() = runTest(dispatcher) {
        val viewModel = createViewModel()
        generationState.value = GenerationState.Generating(prompt = "in flight", streamingText = "")
        advanceUntilIdle()

        viewModel.sendMessage("second prompt")
        advanceUntilIdle()

        // No DB write, no conversation, no generation for the second prompt.
        coVerify(exactly = 0) { conversationRepository.upsert(any()) }
        coVerify(exactly = 0) { engineRepository.generateChat(any(), any(), any()) }
    }

    @Test
    fun `sendMessage proceeds normally when idle`() = runTest(dispatcher) {
        val viewModel = createViewModel()
        generationState.value = GenerationState.Idle
        advanceUntilIdle()

        viewModel.sendMessage("first prompt")
        advanceUntilIdle()

        coVerify(exactly = 1) { engineRepository.generateChat(any(), any(), any()) }
    }

    @Test
    fun `regenerateLastResponse is ignored while generation is in flight`() = runTest(dispatcher) {
        val viewModel = createViewModel()
        // Seed one assistant message so regeneration would normally trigger.
        val convId = "conv-1"
        dbMessages.value = listOf(
            Message(id = "u1", conversationId = convId, role = MessageRole.USER, content = "hi", timestamp = 1L),
            Message(id = "a1", conversationId = convId, role = MessageRole.ASSISTANT, content = "hello", timestamp = 2L)
        )
        viewModel.loadConversation(convId)
        generationState.value = GenerationState.Generating(prompt = "x", streamingText = "")
        advanceUntilIdle()

        viewModel.regenerateLastResponse()
        advanceUntilIdle()

        coVerify(exactly = 0) { messageRepository.deleteById(any()) }
        coVerify(exactly = 0) { engineRepository.generateChat(any(), any(), any()) }
    }

    @Test
    fun `assistant message appended locally survives a stale DB emission`() = runTest(dispatcher) {
        val viewModel = createViewModel()
        val convId = "conv-1"
        // Realistic timestamps around now: appendAssistantMessage stamps with
        // System.currentTimeMillis(), so the assistant response lands between
        // the first user message (older) and the next one (newer).
        val now = System.currentTimeMillis()
        val userMsg = Message(id = "u1", conversationId = convId, role = MessageRole.USER, content = "hi", timestamp = now - 5_000L)

        // Emulate Room state that has only the user message persisted.
        dbMessages.value = listOf(userMsg)
        viewModel.loadConversation(convId)
        advanceUntilIdle()
        assertEquals(1, viewModel.uiState.value.messages().size)

        // Simulate the completion path: the repository publishes Completed and
        // the ViewModel appends the assistant message locally while the async
        // upsert is still in flight (not yet visible in Room).
        generationState.value = GenerationState.Completed(text = "assistant reply")
        advanceUntilIdle()

        // Capture the id + timestamp the ViewModel assigned locally (same id is
        // upserted to Room by appendAssistantMessage).
        val localAssistant = viewModel.uiState.value.messages().last()
        assertEquals("assistant reply", localAssistant.content)
        val assistantId = localAssistant.id
        val assistantTs = localAssistant.timestamp

        // A stale DB emission arrives (e.g. from the NEXT user-message upsert)
        // WITHOUT the assistant message yet — the assistant upsert is still in
        // flight. This is the exact race that previously produced prompts with
        // two consecutive user messages.
        dbMessages.value = listOf(
            userMsg,
            Message(id = "u2", conversationId = convId, role = MessageRole.USER, content = "next", timestamp = assistantTs + 1_000L)
        )
        advanceUntilIdle()

        val messages = viewModel.uiState.value.messages()
        // The locally-appended assistant response must survive the stale emission
        // AND stay in chronological order (before the newer user message).
        assertEquals(3, messages.size)
        assertEquals(
            listOf("hi", "assistant reply", "next"),
            messages.map { it.content }
        )

        // Once the DB confirms the assistant message (same id), it leaves the
        // pending set and the view converges to the DB truth (no duplicates).
        dbMessages.value = listOf(
            userMsg,
            Message(id = assistantId, conversationId = convId, role = MessageRole.ASSISTANT, content = "assistant reply", timestamp = assistantTs),
            Message(id = "u2", conversationId = convId, role = MessageRole.USER, content = "next", timestamp = assistantTs + 1_000L)
        )
        advanceUntilIdle()
        assertEquals(3, viewModel.uiState.value.messages().size)
        val ids = viewModel.uiState.value.messages().map { it.id }
        assertEquals(ids.size, ids.toSet().size) // no duplicates
    }

    @Test
    fun `switching conversations never leaks a pending message from the previous one`() = runTest(dispatcher) {
        val viewModel = createViewModel()
        val convA = "conv-a"
        val convB = "conv-b"
        val now = System.currentTimeMillis()
        val msgA = Message(id = "a1", conversationId = convA, role = MessageRole.USER, content = "in A", timestamp = now - 5_000L)

        // Conversation A is active with one user message.
        dbMessages.value = listOf(msgA)
        viewModel.loadConversation(convA)
        advanceUntilIdle()
        assertEquals(1, viewModel.uiState.value.messages().size)

        // A completion appends an assistant message locally; its upsert is
        // still in flight (pending), so it is not yet in the Room stream.
        generationState.value = GenerationState.Completed(text = "reply in A")
        advanceUntilIdle()
        assertEquals(2, viewModel.uiState.value.messages().size)

        // User switches to conversation B BEFORE the assistant upsert commits.
        // The Room stream for B must not inherit A's pending assistant message.
        dbMessages.value = listOf(
            Message(id = "b1", conversationId = convB, role = MessageRole.USER, content = "in B", timestamp = now - 1_000L)
        )
        viewModel.loadConversation(convB)
        advanceUntilIdle()

        val messages = viewModel.uiState.value.messages()
        assertEquals(listOf("in B"), messages.map { it.content })
        assertTrue(messages.all { it.conversationId == convB })

        // Switching back to A after the upsert confirms shows A's full history
        // (no duplicates, no loss).
        dbMessages.value = listOf(
            msgA,
            Message(id = "a2", conversationId = convA, role = MessageRole.ASSISTANT, content = "reply in A", timestamp = now)
        )
        viewModel.loadConversation(convA)
        advanceUntilIdle()
        assertEquals(2, viewModel.uiState.value.messages().size)
        assertEquals(listOf("in A", "reply in A"), viewModel.uiState.value.messages().map { it.content })
    }

    @Test
    fun `new conversation never inherits stale messages from the previous one`() = runTest(dispatcher) {
        val viewModel = createViewModel()
        val now = System.currentTimeMillis()
        val oldMsg = Message(id = "old", conversationId = "old-conv", role = MessageRole.USER, content = "old history", timestamp = now - 5_000L)

        dbMessages.value = listOf(oldMsg)
        viewModel.loadConversation("old-conv")
        advanceUntilIdle()

        // A blank id (new chat) must clear history and never reuse stale rows.
        dbMessages.value = listOf(oldMsg)
        viewModel.loadConversation("")
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.messages().isEmpty())
    }

    private fun io.androllm.feature.chat.ChatUiState.messages(): List<ChatMessage> =
        (this as ChatUiState.Success).messages
}
