package io.androllm.feature.models

import android.content.Context
import io.androllm.core.common.Result
import io.androllm.core.common.UiState
import io.androllm.core.database.repository.ModelRepository
import io.androllm.core.datastore.PreferencesDataStore
import io.androllm.core.models.Model
import io.androllm.core.models.catalog.CatalogParser
import io.androllm.core.models.catalog.CatalogRepository
import io.androllm.core.models.catalog.CatalogSource
import io.androllm.core.models.catalog.CatalogState
import io.androllm.core.network.repository.ModelRepositoryProvider
import io.androllm.core.network.repository.RepositoryRegistry
import io.androllm.engine.api.EngineRepository
import io.androllm.engine.api.EngineState
import io.androllm.engine.backend.BackendCapabilities
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
    private val catalogRepository: CatalogRepository = mockk(relaxed = true)
    private val preferencesDataStore: PreferencesDataStore = mockk(relaxed = true)

    private val engineState = MutableStateFlow<EngineState>(EngineState.Unloaded)
    private val catalogState = MutableStateFlow<CatalogState>(sampleReadyState())

    private fun sampleReadyState(): CatalogState {
        val json = """{"schemaVersion":2,"models":[{"id":"litertlm-qwen3-0.6b","name":"Qwen3 0.6B LiteRT",""" +
            """"family":"Qwen","architecture":"qwen3","categories":["CHAT"],"tags":["fast"],""" +
            """"license":"Apache-2.0","author":"Alibaba","repoId":"litert-community/Qwen3-0.6B",""" +
            """"fileName":"Qwen3-0.6B.litertlm",""" +
            """"downloadUrl":"https://huggingface.co/litert-community/Qwen3-0.6B/resolve/main/Qwen3-0.6B.litertlm",""" +
            """"sizeBytes":614236160,"parameters":"0.6B","quantization":"Q8",""" +
            """"contextLength":4096,"minRamGb":2.0,"recommendedRamGb":4.0,"downloads":100,"likes":5}]}"""
        val parsed = CatalogParser.parse(json)
        return CatalogState.Ready(parsed.catalog, CatalogSource.BUNDLED)
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        coEvery { engineRepository.initialize() } returns Result.Success(Unit)
        every { engineRepository.engineState } returns engineState
        every { engineRepository.performanceStats } returns MutableStateFlow(null)
        every { engineRepository.memoryStats } returns MutableStateFlow(null)
        every { engineRepository.backendCapabilities } returns MutableStateFlow(BackendCapabilities.UNKNOWN)
        every { modelRepository.observeAllModels() } returns flowOf(emptyList())
        every { repositoryRegistry.getActiveProvider() } returns repositoryProvider
        every { repositoryProvider.searchModels(any()) } returns flowOf(Result.Success(emptyList()))
        every { downloadManager.observeProgress(any()) } returns flowOf(null)
        every { catalogRepository.state } returns catalogState
        every { catalogRepository.refreshing } returns MutableStateFlow(false)
        every { preferencesDataStore.backendPreference } returns MutableStateFlow("AUTO")
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(): ModelsViewModel {
        every { context.getExternalFilesDir(any()) } returns null
        every { context.filesDir } returns File(System.getProperty("java.io.tmpdir"))
        return ModelsViewModel(
            context,
            modelRepository,
            engineRepository,
            repositoryRegistry,
            downloadManager,
            catalogRepository,
            preferencesDataStore
        )
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
