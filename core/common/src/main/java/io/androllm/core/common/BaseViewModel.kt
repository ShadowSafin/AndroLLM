package io.androllm.core.common

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Base ViewModel class that provides common functionality for all ViewModels.
 * Handles UI state management, error handling, and coroutine scope.
 */
abstract class BaseViewModel : ViewModel() {
    private val scope: CoroutineScope = viewModelScope

    /**
     * Global UI state for this ViewModel.
     * Subclasses should expose specific state flows instead of this directly.
     */
    private val _uiState = MutableStateFlow<UiState<Any>>(UiState.Loading())

    /**
     * Updates the UI state safely.
     */
    protected fun updateUiState(state: UiState<Any>) {
        _uiState.value = state
    }

    /**
     * Sets loading state with optional message.
     */
    protected fun setLoading(message: String? = null) {
        _uiState.value = UiState.Loading(message)
    }

    /**
     * Sets success state with data.
     */
    protected fun <T> setSuccess(data: T) {
        _uiState.value = UiState.Success(data as Any)
    }

    /**
     * Sets error state with throwable and user-friendly message.
     */
    protected fun setError(throwable: Throwable, userMessage: String) {
        _uiState.value = UiState.Error(throwable, userMessage)
    }

    /**
     * Sets empty state with message.
     */
    protected fun setEmpty(message: String) {
        _uiState.value = UiState.Empty(message)
    }

    /**
     * Executes a suspending function and handles the result as UI state.
     */
    protected fun <T> executeWithState(
        block: suspend () -> Result<T>,
        onSuccess: (T) -> Unit = { updateUiState(UiState.Success(it as Any)) },
        onError: (Throwable) -> Unit = { e -> updateUiState(UiState.Error(e, e.localizedMessage ?: "An error occurred")) }
    ) {
        scope.launch {
            updateUiState(UiState.Loading())
            val result = block()
            when (result) {
                is Result.Success -> onSuccess(result.data)
                is Result.Error -> onError(result.exception)
            }
        }
    }

    /**
     * Executes a suspending function and collects the flow result.
     */
    protected fun <T> collectFlowWithState(
        flow: kotlinx.coroutines.flow.Flow<Result<T>>,
        onSuccess: (T) -> Unit = { updateUiState(UiState.Success(it as Any)) },
        onError: (Throwable) -> Unit = { e -> updateUiState(UiState.Error(e, e.localizedMessage ?: "An error occurred")) }
    ) {
        scope.launch {
            updateUiState(UiState.Loading())
            flow.collect { result ->
                when (result) {
                    is Result.Success -> onSuccess(result.data)
                    is Result.Error -> onError(result.exception)
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        // Clean up resources if needed
    }

    /**
     * Handles a [Result] and forwards success/error to UI state.
     */
    fun <T> handleResult(result: Result<T>, onSuccess: (T) -> Unit = {}) {
        when (result) {
            is Result.Success -> onSuccess(result.data)
            is Result.Error -> setError(result.exception, result.exception.localizedMessage ?: "An error occurred")
        }
    }
}
