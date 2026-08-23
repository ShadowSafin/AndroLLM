package io.androllm.feature.prompts

import io.androllm.core.datastore.PreferencesDataStore
import io.mockk.coVerify
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

/**
 * Tests for the prompt library ViewModel.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PromptLibraryViewModelTest {

    private val preferencesDataStore: PreferencesDataStore = mockk(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        every { preferencesDataStore.favoritePromptIds } returns MutableStateFlow(emptySet())
        every { preferencesDataStore.studioDefaultTemplate } returns flowOf(null)
        every { preferencesDataStore.studioAutoPreview } returns flowOf(true)
        every { preferencesDataStore.studioShowAdvanced } returns flowOf(false)
        every { preferencesDataStore.studioSaveHistory } returns flowOf(true)
        every { preferencesDataStore.studioEnableRefinement } returns flowOf(true)
        every { preferencesDataStore.studioHistoryJson } returns flowOf("[]")
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state shows the full library`() = runTest {
        val viewModel = PromptLibraryViewModel(preferencesDataStore)
        assertEquals(PromptLibrary.prompts.size, viewModel.uiState.value.prompts.size)
        assertEquals(PromptCategory.ALL, viewModel.uiState.value.selectedCategory)
    }

    @Test
    fun `search filters by title and description`() = runTest {
        val viewModel = PromptLibraryViewModel(preferencesDataStore)
        viewModel.updateQuery("sql")
        assertTrue(viewModel.uiState.value.prompts.any { it.id == "sql_query" })
        viewModel.updateQuery("zzzznonexistent")
        assertTrue(viewModel.uiState.value.prompts.isEmpty())
    }

    @Test
    fun `category filter narrows results`() = runTest {
        val viewModel = PromptLibraryViewModel(preferencesDataStore)
        viewModel.selectCategory(PromptCategory.MATH)
        val prompts = viewModel.uiState.value.prompts
        assertTrue(prompts.isNotEmpty())
        assertTrue(prompts.all { it.category == PromptCategory.MATH })
    }

    @Test
    fun `favorites persist through toggle`() = runTest {
        val viewModel = PromptLibraryViewModel(preferencesDataStore)
        val target = PromptLibrary.prompts.first().id
        viewModel.toggleFavorite(target)
        coVerify { preferencesDataStore.setPromptFavorite(target, true) }
    }
}
