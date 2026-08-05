package io.androllm.feature.prompts

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.androllm.core.common.BaseViewModel
import io.androllm.core.datastore.PreferencesDataStore
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * ViewModel for the prompt library: search, category filters, and persistent
 * favorites (stored in DataStore).
 */
@HiltViewModel
class PromptLibraryViewModel @Inject constructor(
    private val preferencesDataStore: PreferencesDataStore
) : BaseViewModel() {

    private val _searchQuery = MutableStateFlow("")
    private val _selectedCategory = MutableStateFlow(PromptCategory.ALL)

    val uiState: StateFlow<PromptLibraryUiState> = combine(
        _searchQuery,
        _selectedCategory,
        preferencesDataStore.favoritePromptIds
    ) { query, category, favorites ->
        val filtered = PromptLibrary.prompts.filter { template ->
            val matchesCategory = category == PromptCategory.ALL || template.category == category
            val q = query.trim()
            val matchesQuery = q.isEmpty() ||
                template.title.contains(q, ignoreCase = true) ||
                template.description.contains(q, ignoreCase = true) ||
                template.text.contains(q, ignoreCase = true)
            matchesCategory && matchesQuery
        }
        PromptLibraryUiState(
            query = query,
            selectedCategory = category,
            prompts = filtered,
            favorites = favorites,
            categories = PromptCategory.entries
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = PromptLibraryUiState()
    )

    fun updateQuery(query: String) {
        _searchQuery.value = query
    }

    fun selectCategory(category: PromptCategory) {
        _selectedCategory.value = category
    }

    fun toggleFavorite(id: String) {
        viewModelScope.launch {
            val current = uiState.value.favorites
            preferencesDataStore.setPromptFavorite(id, id !in current)
        }
    }
}

/**
 * UI state for the prompt library.
 */
data class PromptLibraryUiState(
    val query: String = "",
    val selectedCategory: PromptCategory = PromptCategory.ALL,
    val prompts: List<PromptTemplate> = PromptLibrary.prompts,
    val favorites: Set<String> = emptySet(),
    val categories: List<PromptCategory> = PromptCategory.entries
) {
    val favoriteCount: Int get() = favorites.size
}
