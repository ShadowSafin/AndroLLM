package io.androllm.feature.models

import android.content.Context
import io.androllm.core.common.Result
import io.androllm.core.common.UiState
import io.androllm.core.database.repository.ModelRepository
import io.androllm.core.models.Model
import io.androllm.core.network.repository.ModelRepositoryProvider
import io.androllm.core.network.repository.RepositoryRegistry
import io.androllm.engine.api.EngineRepository
import io.androllm.engine.api.EngineState
import io.androllm.feature.models.downloader.DownloadManager
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.io.File
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
 * Tests for the models screen ViewModel.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ModelsViewModelTest {

    private val context: Context = mockk(relaxed = true)
    private val modelRepository: ModelRepository = mockk(relaxed = true)
    private val engineRepository: EngineRepository = mockk(relaxed = true)
    private val repositoryRegistry: RepositoryRegistry = mockk(relaxed = true)
    private val repositoryProvider: ModelRepositoryProvider = mockk(relaxed = true)
    private val downloadManager: DownloadManager = mockk(relaxed = true)

    private val engineState = MutableStateFlow<EngineState>(EngineState.Unloaded)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        coEvery { engineRepository.initialize() } returns Result.Success(Unit)
        every { engineRepository.engineState } returns engineState
        every { modelRepository.observeAllModels() } returns flowOf(emptyList())
        every { repositoryRegistry.getActiveProvider() } returns repositoryProvider
        every { repositoryProvider.searchModels(any()) } returns flowOf(Result.Success(emptyList()))
        every { downloadManager.observeProgress(any()) } returns flowOf(null)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(): ModelsViewModel {
        every { context.getExternalFilesDir(any()) } returns null
        every { context.filesDir } returns File(System.getProperty("java.io.tmpdir"))
        return ModelsViewModel(context, modelRepository, engineRepository, repositoryRegistry, downloadManager)
    }

    @Test
    fun `initial state is success with catalog models present`() = runTest {
        val viewModel = createViewModel()

        val state = viewModel.uiState.value
        assertTrue(state is UiState.Success)
        val data = (state as UiState.Success).data
        assertEquals(0, data.installedModels.size)
        assertTrue(data.catalogModels.isNotEmpty())
    }

    @Test
    fun `state reflects installed models`() = runTest {
        every { modelRepository.observeAllModels() } returns flowOf(
            listOf(Model(id = "model-1", name = "Test Model"))
        )

        val viewModel = createViewModel()

        val state = viewModel.uiState.value
        assertTrue(state is UiState.Success)
        val data = (state as UiState.Success).data
        assertEquals(1, data.installedModels.size)
        assertEquals("Test Model", data.installedModels.first().name)
    }

    @Test
    fun `selectTab updates active tab`() = runTest {
        val viewModel = createViewModel()
        viewModel.selectTab(ModelsTab.CATALOG)

        val state = viewModel.uiState.value as UiState.Success
        assertEquals(ModelsTab.CATALOG, state.data.selectedTab)
    }
}
