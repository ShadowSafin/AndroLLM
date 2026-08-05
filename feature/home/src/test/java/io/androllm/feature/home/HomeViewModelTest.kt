package io.androllm.feature.home

import io.androllm.core.common.UiState
import io.androllm.core.database.repository.ConversationRepository
import io.androllm.core.database.repository.SettingsRepository
import io.androllm.core.models.AppSettings
import io.androllm.core.telemetry.TelemetryRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
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

/**
 * Tests for the home screen ViewModel.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    private val conversationRepository: ConversationRepository = mockk(relaxed = true)
    private val settingsRepository: SettingsRepository = mockk(relaxed = true)
    private val telemetryRepository: TelemetryRepository = mockk(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        every { telemetryRepository.deviceMetrics } returns MutableStateFlow(null)
        every { telemetryRepository.history } returns MutableStateFlow(emptyList())
        every { telemetryRepository.generationHistory } returns MutableStateFlow(emptyList())
        every { telemetryRepository.currentModelName } returns MutableStateFlow("")
        every { telemetryRepository.isSampling } returns MutableStateFlow(false)
        every { telemetryRepository.isModelLoaded() } returns false
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state is success with empty conversations`() = runTest {
        every { conversationRepository.observeRecent() } returns flowOf(emptyList())
        every { settingsRepository.observeSettings() } returns flowOf(AppSettings())

        val viewModel = HomeViewModel(conversationRepository, settingsRepository, telemetryRepository)

        val state = viewModel.uiState.value
        assertTrue(state is UiState.Success)
        val data = (state as UiState.Success).data
        assertEquals(0, data.recentConversations.size)
        assertEquals(false, data.isModelLoaded)
    }

    @Test
    fun `starts sampling telemetry on creation`() = runTest {
        every { conversationRepository.observeRecent() } returns flowOf(emptyList())
        every { settingsRepository.observeSettings() } returns flowOf(AppSettings())

        HomeViewModel(conversationRepository, settingsRepository, telemetryRepository)

        verify { telemetryRepository.startSampling() }
    }

    @Test
    fun `exposes latest telemetry values`() = runTest {
        every { conversationRepository.observeRecent() } returns flowOf(emptyList())
        every { settingsRepository.observeSettings() } returns flowOf(AppSettings())

        val viewModel = HomeViewModel(conversationRepository, settingsRepository, telemetryRepository)

        val telemetry = viewModel.telemetry.value
        assertEquals(0f, telemetry.ramTotalMb)
        assertTrue(telemetry.speedHistory.isEmpty())
    }
}
