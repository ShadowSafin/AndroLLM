package io.androllm.feature.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.androllm.core.datastore.PreferencesDataStore
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Drives the multi-page introduction: pager position and the one-shot
 * "onboarding completed" flag persisted through [PreferencesDataStore].
 */
@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val preferencesDataStore: PreferencesDataStore
) : ViewModel() {

    /** Total number of introduction pages. */
    val pageCount: Int = 5

    private val _currentPage = MutableStateFlow(0)
    val currentPage: StateFlow<Int> = _currentPage.asStateFlow()

    val isLastPage: Boolean get() = _currentPage.value >= pageCount - 1

    fun next() = setPage(_currentPage.value + 1)

    fun back() = setPage(_currentPage.value - 1)

    /**
     * Moves to [page] (clamped to the valid range). The pager reports swipes
     * back into the ViewModel through this entry point.
     */
    fun setPage(page: Int) {
        _currentPage.value = page.coerceIn(0, pageCount - 1)
    }

    /**
     * Persists the completion flag, then invokes [onDone]. Safe to call from
     * both the Skip affordance and the final Get Started button.
     */
    fun complete(onDone: () -> Unit) {
        viewModelScope.launch {
            preferencesDataStore.setOnboardingCompleted(true)
            onDone()
        }
    }
}
