package io.androllm.feature.settings

import android.content.Context
import io.androllm.core.common.UiState
import io.androllm.core.database.repository.SettingsRepository
import io.androllm.core.models.AppSettings
import io.androllm.core.models.ThemeMode
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
 * Tests for the settings screen ViewModel.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val context: Context = mockk(relaxed = true)
    private val settingsRepository: SettingsRepository = mockk(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
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

        val viewModel = SettingsViewModel(context, settingsRepository)

        val state = viewModel.uiState.value
        assertTrue(state is UiState.Success)
        val data = (state as UiState.Success).data
        assertEquals(ThemeMode.DARK, data.theme)
        assertTrue(data.developerMode)
    }

    @Test
    fun `defaults are used when no settings exist`() = runTest {
        every { settingsRepository.observeSettings() } returns flowOf(AppSettings())

        val viewModel = SettingsViewModel(context, settingsRepository)

        val state = viewModel.uiState.value
        assertTrue(state is UiState.Success)
        assertEquals(ThemeMode.SYSTEM, (state as UiState.Success).data.theme)
    }
}
