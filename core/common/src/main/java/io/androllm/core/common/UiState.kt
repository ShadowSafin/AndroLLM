package io.androllm.core.common

/**
 * Base class for all UI states in the application.
 * Ensures immutable state and provides common functionality.
 *
 * @param <T> The type of data this state holds
 */
sealed interface UiState<out T> {
    /**
     * State when data is loading
     */
    data class Loading<T>(val message: String? = null) : UiState<T>

    /**
     * State when data has been loaded successfully
     */
    data class Success<T>(val data: T) : UiState<T>

    /**
     * State when an error has occurred
     */
    data class Error<T>(val throwable: Throwable, val userMessage: String) : UiState<T>

    /**
     * State when there is no data (empty state)
     */
    data class Empty<T>(val message: String) : UiState<T>

    companion object {
        fun <T> loading(message: String? = null): UiState<T> = Loading(message)
        fun <T> success(data: T): UiState<T> = Success(data)
        fun <T> error(throwable: Throwable, userMessage: String): UiState<T> = Error(throwable, userMessage)
        fun <T> empty(message: String): UiState<T> = Empty(message)
    }
}

/**
 * Extension functions for UiState
 */
fun <T> UiState<T>.isLoading(): Boolean = this is UiState.Loading
fun <T> UiState<T>.isSuccess(): Boolean = this is UiState.Success
fun <T> UiState<T>.isError(): Boolean = this is UiState.Error
fun <T> UiState<T>.isEmpty(): Boolean = this is UiState.Empty

fun <T> UiState<T>.getOrNull(): T? = when (this) {
    is UiState.Success -> data
    else -> null
}

fun <T> UiState<T>.getOrThrow(): T = when (this) {
    is UiState.Success -> data
    is UiState.Error -> throw throwable
    else -> throw IllegalStateException("State is not Success: $this")
}

fun <T> UiState<T>.getErrorOrNull(): Throwable? = when (this) {
    is UiState.Error -> throwable
    else -> null
}
