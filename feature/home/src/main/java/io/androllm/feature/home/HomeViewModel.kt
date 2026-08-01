package io.androllm.feature.home

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.androllm.core.common.BaseViewModel
import io.androllm.core.common.UiState
import io.androllm.core.database.repository.ConversationRepository
import io.androllm.core.database.repository.SettingsRepository
import io.androllm.core.models.Conversation
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * ViewModel for the home screen.
 * Loads recent conversations and the current model load state.
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val conversationRepository: ConversationRepository,
    private val settingsRepository: SettingsRepository
) : BaseViewModel() {

    private val _uiState = MutableStateFlow<UiState<HomeData>>(UiState.Loading())
    val uiState: StateFlow<UiState<HomeData>> = _uiState

    init {
        observeRecentConversations()
        observeSettings()
    }

    private fun observeRecentConversations() {
        conversationRepository.observeRecent()
            .onEach { conversations ->
                _uiState.value = UiState.Success(
                    HomeData(
                        recentConversations = conversations,
                        isModelLoaded = (_uiState.value as? UiState.Success)?.data?.isModelLoaded ?: false
                    )
                )
            }
            .launchIn(viewModelScope)
    }

    private fun observeSettings() {
        viewModelScope.launch {
            settingsRepository.observeSettings().collect { settings ->
                _uiState.value = UiState.Success(
                    HomeData(
                        recentConversations = (_uiState.value as? UiState.Success)?.data?.recentConversations ?: emptyList(),
                        isModelLoaded = settings.modelPath != null
                    )
                )
            }
        }
    }

    /**
     * Refreshes the home data.
     */
    fun refresh() {
        // Flow-based observation keeps data fresh automatically.
    }
}

/**
 * Data loaded on the home screen.
 */
data class HomeData(
    val recentConversations: List<Conversation> = emptyList(),
    val isModelLoaded: Boolean = false
)
