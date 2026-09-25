package io.androllm.feature.settings

import android.content.Context
import io.androllm.core.common.UiState
import io.androllm.core.database.repository.SettingsRepository
import io.androllm.core.datastore.PreferencesDataStore
import io.androllm.core.attachments.AttachmentSettingsStore
import io.androllm.core.memory.MemoryManager
import io.androllm.core.memory.model.MemoryInspectorStats
import io.androllm.core.memory.model.MemorySettings
import io.androllm.core.models.AppSettings
import io.androllm.core.models.ThemeMode
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

import io.androllm.core.voice.VoiceSettingsStore
import io.androllm.core.voice.model.VoiceSettings
import io.androllm.feature.voice.VoiceAssistantController
import io.androllm.feature.voice.VoiceUiState

import io.androllm.core.tools.registry.ToolRegistry
import io.androllm.core.tools.settings.AutomationSettings
import io.androllm.core.tools.settings.AutomationSettingsStore
import io.androllm.core.mcp.McpConnectionManager
import io.androllm.core.mcp.McpSettingsStore
import io.androllm.core.accessibility.controller.AccessibilityController
import io.androllm.core.accessibility.settings.AccessibilitySettingsStore
import io.androllm.core.database.repository.ModelRepository
import io.androllm.engine.api.EngineRepository
import io.androllm.engine.api.EngineState
import io.androllm.engine.backend.BackendCapabilities
import io.androllm.core.voice.stt.WhisperModelManager
import io.androllm.core.voice.stt.WhisperSpeechRecognizer
import io.androllm.core.voice.wakeword.WakeWordEngine

/**
 * Tests for the settings screen ViewModel.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val context: Context = mockk(relaxed = true)
    private val settingsRepository: SettingsRepository = mockk(relaxed = true)
    private val preferencesDataStore: PreferencesDataStore = mockk(relaxed = true)
    private val memoryManager: MemoryManager = mockk(relaxed = true)
    private val cloudGateway: io.androllm.core.cloud.CloudGateway = mockk(relaxed = true)
    private val attachmentSettingsStore: AttachmentSettingsStore = mockk(relaxed = true)
    private val voiceSettingsStore: VoiceSettingsStore = mockk(relaxed = true)
    private val voiceController: VoiceAssistantController = mockk(relaxed = true)
    private val wakeWordEngine: WakeWordEngine = mockk(relaxed = true)
    private val whisperModelManager: WhisperModelManager = mockk(relaxed = true)
    private val whisperSpeechRecognizer: WhisperSpeechRecognizer = mockk(relaxed = true)
    private val automationSettingsStore: AutomationSettingsStore = mockk(relaxed = true)
    private val toolRegistry: ToolRegistry = mockk(relaxed = true)
    private val accessibilitySettingsStore: AccessibilitySettingsStore = mockk(relaxed = true)
    private val accessibilityController: AccessibilityController = mockk(relaxed = true)
    private val mcpSettingsStore: McpSettingsStore = mockk(relaxed = true)
    private val mcpConnectionManager: McpConnectionManager = mockk(relaxed = true)
    private val modelRepository: ModelRepository = mockk(relaxed = true)
    private val engineRepository: EngineRepository = mockk(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        every { context.getExternalFilesDir(any()) } returns
            java.io.File(System.getProperty("java.io.tmpdir"), "androllm_test_logs")
        every { context.getExternalFilesDir(null) } returns
            java.io.File(System.getProperty("java.io.tmpdir"), "androllm_test_external")
        every { context.filesDir } returns
            java.io.File(System.getProperty("java.io.tmpdir"), "androllm_test_files")
        every { preferencesDataStore.userPreferences } returns flowOf(io.androllm.core.datastore.UserPreferences())
        every { memoryManager.settings } returns flowOf(MemorySettings())
        coEvery { memoryManager.currentSettings() } returns MemorySettings()
        coEvery { memoryManager.getInspectorStats() } returns MemoryInspectorStats()
        every { voiceSettingsStore.settings } returns flowOf(VoiceSettings())
        coEvery { voiceSettingsStore.current() } returns VoiceSettings()
        every { voiceController.state } returns MutableStateFlow(VoiceUiState())
        every { mcpSettingsStore.servers } returns flowOf(emptyList())
        every { mcpConnectionManager.states } returns MutableStateFlow(emptyMap())
        every { cloudGateway.settings } returns flowOf(io.androllm.core.cloud.model.CloudSettings())
        every { modelRepository.observeDownloaded() } returns flowOf(emptyList())
        every { preferencesDataStore.backendPreference } returns flowOf("AUTO")
        every { engineRepository.engineState } returns MutableStateFlow(EngineState.Unloaded)
        every { engineRepository.backendCapabilities } returns MutableStateFlow(BackendCapabilities())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state reflects stored settings`() = runTest {
        every { settingsRepository.observeSettings() } returns flowOf(
            AppSettings(theme = ThemeMode.DARK, developerMode = true)
        )

        val viewModel = SettingsViewModel(
            context, settingsRepository, preferencesDataStore, memoryManager, cloudGateway, attachmentSettingsStore, voiceSettingsStore, voiceController,
            wakeWordEngine, whisperModelManager, whisperSpeechRecognizer,
            automationSettingsStore, toolRegistry, accessibilitySettingsStore, accessibilityController,
            mcpSettingsStore, mcpConnectionManager,
            modelRepository, engineRepository
        )

        val state = viewModel.uiState.value
        assertTrue(state is UiState.Success)
        val data = (state as UiState.Success).data
        assertEquals(ThemeMode.DARK, data.theme)
        assertTrue(data.developerMode)
    }

    @Test
    fun `defaults are used when no settings exist`() = runTest {
        every { settingsRepository.observeSettings() } returns flowOf(AppSettings())

        val viewModel = SettingsViewModel(
            context, settingsRepository, preferencesDataStore, memoryManager, cloudGateway, attachmentSettingsStore, voiceSettingsStore, voiceController,
            wakeWordEngine, whisperModelManager, whisperSpeechRecognizer,
            automationSettingsStore, toolRegistry, accessibilitySettingsStore, accessibilityController,
            mcpSettingsStore, mcpConnectionManager,
            modelRepository, engineRepository
        )

        val state = viewModel.uiState.value
        assertTrue(state is UiState.Success)
        assertEquals(ThemeMode.SYSTEM, (state as UiState.Success).data.theme)
    }

    @Test
    fun `downloaded model count reflects repository`() = runTest {
        every { settingsRepository.observeSettings() } returns flowOf(AppSettings())
        every { modelRepository.observeDownloaded() } returns flowOf(
            listOf(
                mockk<io.androllm.core.models.Model>(relaxed = true),
                mockk<io.androllm.core.models.Model>(relaxed = true)
            )
        )

        val viewModel = SettingsViewModel(
            context, settingsRepository, preferencesDataStore, memoryManager, cloudGateway, attachmentSettingsStore, voiceSettingsStore, voiceController,
            wakeWordEngine, whisperModelManager, whisperSpeechRecognizer,
            automationSettingsStore, toolRegistry, accessibilitySettingsStore, accessibilityController,
            mcpSettingsStore, mcpConnectionManager,
            modelRepository, engineRepository
        )

        assertEquals(2, viewModel.downloadedModelCount.value)
    }

    @Test
    fun `backend label reflects active engine backend`() = runTest {
        every { settingsRepository.observeSettings() } returns flowOf(AppSettings())
        every { engineRepository.engineState } returns MutableStateFlow(
            EngineState.Ready(
                model = io.androllm.engine.models.EngineModelInfo(
                    id = "m1",
                    filePath = "",
                    contextLength = 2048,
                    vocabSize = 0,
                    backend = io.androllm.engine.models.BackendType.GPU
                )
            )
        )

        val viewModel = SettingsViewModel(
            context, settingsRepository, preferencesDataStore, memoryManager, cloudGateway, attachmentSettingsStore, voiceSettingsStore, voiceController,
            wakeWordEngine, whisperModelManager, whisperSpeechRecognizer,
            automationSettingsStore, toolRegistry, accessibilitySettingsStore, accessibilityController,
            mcpSettingsStore, mcpConnectionManager,
            modelRepository, engineRepository
        )

        assertEquals("GPU", viewModel.backendLabel.value)
    }

    @Test
    fun `signOut does not crash when auth is unavailable`() = runTest {
        every { settingsRepository.observeSettings() } returns flowOf(AppSettings())

        val viewModel = SettingsViewModel(
            context, settingsRepository, preferencesDataStore, memoryManager, cloudGateway, attachmentSettingsStore, voiceSettingsStore, voiceController,
            wakeWordEngine, whisperModelManager, whisperSpeechRecognizer,
            automationSettingsStore, toolRegistry, accessibilitySettingsStore, accessibilityController,
            mcpSettingsStore, mcpConnectionManager,
            modelRepository, engineRepository
        )

        viewModel.signOut()
    }
}


