package io.androllm.feature.profile

import android.content.Context
import io.androllm.core.common.UiState
import io.androllm.core.database.repository.ConversationRepository
import io.androllm.core.database.repository.ModelRepository
import io.androllm.core.telemetry.TelemetryRepository
import io.androllm.core.telemetry.TelemetrySample
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for the profile ViewModel (Firebase is unavailable in unit tests,
 * so identity stays null and the app behaves as guest mode).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProfileViewModelTest {

    private val appContext: Context = mockk(relaxed = true)
    private val conversationRepository: ConversationRepository = mockk(relaxed = true)
    private val modelRepository: ModelRepository = mockk(relaxed = true)
    private val telemetryRepository: TelemetryRepository = mockk(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        every { conversationRepository.observeActive() } returns flowOf(emptyList())
        every { modelRepository.observeAllModels() } returns flowOf(emptyList())
        every { telemetryRepository.deviceMetrics } returns MutableStateFlow(null)
        every { telemetryRepository.history } returns MutableStateFlow(
            listOf(
                TelemetrySample(
                    timestampMs = 1L, tokensPerSecond = 12.5f, ramUsedMb = 2048f,
                    ramTotalMb = 8192f, gpuMemoryMb = 0f, kvCacheMb = 0f,
                    promptTokens = 0L, generatedTokens = 0L, isGenerating = false
                )
            )
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `guest mode has null user`() = runTest {
        val viewModel = ProfileViewModel(appContext, conversationRepository, modelRepository, telemetryRepository)
        assertEquals(null, viewModel.user.value)
    }

    @Test
    fun `state aggregates real counts`() = runTest {
        val viewModel = ProfileViewModel(appContext, conversationRepository, modelRepository, telemetryRepository)
        val state = viewModel.uiState.value
        assertTrue(state is UiState.Success)
        val data = (state as UiState.Success).data
        assertEquals(0, data.conversationCount)
        assertEquals(0, data.modelCount)
        assertEquals(12.5f, data.tokensPerSecond)
    }

    @Test
    fun `refresh reads device metrics`() = runTest {
        val viewModel = ProfileViewModel(appContext, conversationRepository, modelRepository, telemetryRepository)
        viewModel.refresh()
        assertNotNull(viewModel.uiState.value)
    }
}
