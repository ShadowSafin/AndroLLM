package io.androllm.feature.settings

import android.content.Context
import io.androllm.core.common.UiState
import io.androllm.core.database.repository.SettingsRepository
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

import io.androllm.core.voice.wakeword.WakeWordEngine

/**
 * Tests for the settings screen ViewModel.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val context: Context = mockk(relaxed = true)
    private val settingsRepository: SettingsRepository = mockk(relaxed = true)
    private val memoryManager: MemoryManager = mockk(relaxed = true)
    private val voiceSettingsStore: VoiceSettingsStore = mockk(relaxed = true)
    private val voiceController: VoiceAssistantController = mockk(relaxed = true)
    private val wakeWordEngine: WakeWordEngine = mockk(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        every { context.getExternalFilesDir(any()) } returns
            java.io.File(System.getProperty("java.io.tmpdir"), "androllm_test_logs")
        every { context.getExternalFilesDir(null) } returns
            java.io.File(System.getProperty("java.io.tmpdir"), "androllm_test_external")
        every { context.filesDir } returns
            java.io.File(System.getProperty("java.io.tmpdir"), "androllm_test_files")
        every { memoryManager.settings } returns flowOf(MemorySettings())
        coEvery { memoryManager.currentSettings() } returns MemorySettings()
        coEvery { memoryManager.getInspectorStats() } returns MemoryInspectorStats()
        every { voiceSettingsStore.settings } returns flowOf(VoiceSettings())
        coEvery { voiceSettingsStore.current() } returns VoiceSettings()
        every { voiceController.state } returns MutableStateFlow(VoiceUiState())
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

        val viewModel = SettingsViewModel(context, settingsRepository, memoryManager, voiceSettingsStore, voiceController, wakeWordEngine)

        val state = viewModel.uiState.value
        assertTrue(state is UiState.Success)
        val data = (state as UiState.Success).data
        assertEquals(ThemeMode.DARK, data.theme)
        assertTrue(data.developerMode)
    }

    @Test
    fun `defaults are used when no settings exist`() = runTest {
        every { settingsRepository.observeSettings() } returns flowOf(AppSettings())

        val viewModel = SettingsViewModel(context, settingsRepository, memoryManager, voiceSettingsStore, voiceController, wakeWordEngine)

        val state = viewModel.uiState.value
        assertTrue(state is UiState.Success)
        assertEquals(ThemeMode.SYSTEM, (state as UiState.Success).data.theme)
    }
}


