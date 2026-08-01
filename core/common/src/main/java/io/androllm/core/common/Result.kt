package io.androllm.core.common

/**
 * A generic result wrapper that represents either a success with data or a failure with an exception.
 * This is used throughout the data layer to handle operation results without throwing exceptions.
 *
 * @param <T> The type of the successful result data
 */
sealed interface Result<out T> {
    /**
     * Represents a successful operation with data.
     */
    data class Success<out T>(val data: T) : Result<T>

    /**
     * Represents a failed operation with an exception.
     */
    data class Error(val exception: Exception) : Result<Nothing>

    companion object {
        fun <T> success(data: T): Result<T> = Success(data)
        fun <T> error(exception: Exception): Result<T> = Error(exception)
        fun <T> error(message: String): Result<T> = Error(Exception(message))
        fun <T> error(throwable: Throwable): Result<T> = Error(Exception(throwable.message ?: "Unknown error", throwable))
    }
}

/**
 * Extension functions for Result to make it easier to work with.
 */
inline fun <T> Result<T>.onSuccess(action: (T) -> Unit): Result<T> {
    if (this is Result.Success) action(data)
    return this
}

inline fun <T> Result<T>.onError(action: (Exception) -> Unit): Result<T> {
    if (this is Result.Error) action(exception)
    return this
}

inline fun <T, R> Result<T>.map(transform: (T) -> R): Result<R> = when (this) {
    is Result.Success -> Result.Success(transform(data))
    is Result.Error -> this
}

inline fun <T, R> Result<T>.flatMap(transform: (T) -> Result<R>): Result<R> = when (this) {
    is Result.Success -> transform(data)
    is Result.Error -> this
}

inline fun <T> Result<T>.getOrNull(): T? = when (this) {
    is Result.Success -> data
    is Result.Error -> null
}

inline fun <T> Result<T>.getOrThrow(): T = when (this) {
    is Result.Success -> data
    is Result.Error -> throw exception
}

inline fun <T> Result<T>.getOrDefault(defaultValue: T): T = when (this) {
    is Result.Success -> data
    is Result.Error -> defaultValue
}

inline fun <T> Result<T>.getOrElse(defaultValue: () -> T): T = when (this) {
    is Result.Success -> data
    is Result.Error -> defaultValue()
}

fun <T> Result<T>.isSuccess(): Boolean = this is Result.Success
fun <T> Result<T>.isError(): Boolean = this is Result.Error

/**
 * Executes a block of code and wraps the result in a Result type.
 * Catches any exceptions and returns them as Result.Error.
 */
inline fun <T> runCatching(block: () -> T): Result<T> {
    return try {
        Result.Success(block())
    } catch (e: Exception) {
        Result.Error(e)
    }
}
