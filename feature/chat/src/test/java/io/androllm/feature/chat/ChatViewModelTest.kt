package io.androllm.feature.chat

import android.content.Context
import io.androllm.core.attachments.AttachmentProcessor
import io.androllm.core.attachments.AttachmentSettingsStore
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
import io.androllm.core.database.repository.SettingsRepository
import io.androllm.core.models.AppSettings
import io.androllm.core.tools.agent.AgentVariableStore
import io.androllm.core.tools.coordinator.ToolRunCoordinator
import io.androllm.core.tools.confirmation.ToolConfirmationManager
import io.androllm.core.tools.prompt.ToolPromptBuilder
import io.androllm.core.tools.settings.AutomationSettingsStore
import io.androllm.core.tools.trace.ToolExecutionTraceStore
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
    private val appContext = mockk<Context>(relaxed = true)
    private val memoryManager = mockk<MemoryManager>(relaxed = true)
    private val attachmentProcessor = mockk<AttachmentProcessor>(relaxed = true)
    private val attachmentSettingsStore = mockk<AttachmentSettingsStore>(relaxed = true)
    private val cloudGateway = mockk<CloudGateway>(relaxed = true)
    private val toolCoordinator = mockk<ToolRunCoordinator>(relaxed = true)
    private val confirmationManager = ToolConfirmationManager()
    private val automationSettingsStore = mockk<AutomationSettingsStore>(relaxed = true)
    private val traceStore = ToolExecutionTraceStore()
    private val variableStore = mockk<AgentVariableStore>(relaxed = true)
    private val toolPromptBuilder = mockk<ToolPromptBuilder>(relaxed = true)
    private val settingsRepository = mockk<SettingsRepository>(relaxed = true)

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
        every { settingsRepository.observeSettings() } returns flowOf(AppSettings())
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
            appContext,
            engineRepository,
            conversationRepository,
            messageRepository,
            preferencesDataStore,
            memoryManager,
            attachmentProcessor,
            attachmentSettingsStore,
            cloudGateway,
            toolCoordinator,
            confirmationManager,
            automationSettingsStore,
            traceStore,
            variableStore,
            toolPromptBuilder,
            settingsRepository
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

    @Test
    fun `user message is committed to UI state immediately - independent of the Room echo`() = runTest(dispatcher) {
        val viewModel = createViewModel()
        // messageRepository.observeByConversationId is mocked as
        // flowOf(emptyList()) — Room NEVER echoes the upsert back. The user's
        // message must still be visible: the DB write is only a persistence
        // detail, never the render path (regression: the user message vanished
        // or never appeared when the echo was late or missing).
        //
        // This test ALSO locks in the observer ordering race: nothing in this
        // test suspends between setting the new conversation id and committing
        // the user message, so the observer's id-change clear runs AFTER the
        // commit — exactly the "message briefly appears, then disappears"
        // bug. It passes only because the observer clear is ownership-based
        // (it keeps messages of the new conversation) instead of a blind wipe.
        viewModel.sendMessage("hello")
        advanceUntilIdle()

        val messages = (viewModel.uiState.value as ChatUiState.Success).messages
        assertEquals(listOf("hello"), messages.map { it.content })
        assertEquals(listOf(MessageRole.USER), messages.map { it.role })
    }

    @Test
    fun `failed generation keeps the user message visible and appends a visible error`() = runTest(dispatcher) {
        val viewModel = createViewModel()
        viewModel.sendMessage("hello")
        advanceUntilIdle()

        // The engine publishes a failure after the send: the user message must
        // survive AND an error message must appear — never silent nothing.
        generationState.value = GenerationState.Failed(message = "Model not loaded")
        advanceUntilIdle()

        val messages = (viewModel.uiState.value as ChatUiState.Success).messages
        assertEquals(
            listOf("hello", "Error: Model not loaded"),
            messages.map { it.content }
        )
    }
}


