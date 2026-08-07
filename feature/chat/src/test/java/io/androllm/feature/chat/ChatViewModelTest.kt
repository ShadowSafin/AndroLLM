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
import io.androllm.core.models.Conversation
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {

    // Unconfined: nested viewModelScope launches (e.g. sendMessage ->
    // generateFromHistory) execute eagerly, so verification is deterministic.
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
        every { messageRepository.observeByConversationId(any()) } returns flowOf(emptyList())
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
    fun `initial state succeeds with loading state then success`() = runTest(dispatcher) {
        val viewModel = createViewModel()
        advanceUntilIdle()
        val state = viewModel.uiState.value
        assertTrue(state is ChatUiState.Success)
    }

    @Test
    fun `createNewConversation creates a conversation entry`() = runTest(dispatcher) {
        val viewModel = createViewModel()
        viewModel.createNewConversation("Test Title")
        advanceUntilIdle()

        coVerify { conversationRepository.upsert(any()) }
    }

    @Test
    fun `sendMessage creates new conversation if none selected and triggers generation`() = runTest(dispatcher) {
        val viewModel = createViewModel()
        viewModel.sendMessage("Hello World")
        advanceUntilIdle()

        coVerify { conversationRepository.upsert(any()) }
        coVerify { messageRepository.upsert(any()) }
        coVerify { engineRepository.generateChat(any(), any(), any()) }
    }

    @Test
    fun `togglePinConversation invokes repository`() = runTest(dispatcher) {
        val viewModel = createViewModel()
        val conv = Conversation(id = "123", title = "Test", createdAt = 0L, updatedAt = 0L, isPinned = false)
        viewModel.togglePinConversation(conv)
        advanceUntilIdle()

        coVerify { conversationRepository.setPinned("123", true) }
    }

    @Test
    fun `cancelGeneration propagates to engine repository`() = runTest(dispatcher) {
        val viewModel = createViewModel()
        viewModel.cancelGeneration()
        advanceUntilIdle()

        coVerify { engineRepository.cancelGeneration() }
    }
}


