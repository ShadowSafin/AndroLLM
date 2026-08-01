package io.androllm.core.common

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Base class for all use cases that return a single result.
 * Use cases represent a single business logic operation.
 *
 * @param <T> The type of the result
 * @param <P> The type of the input parameters (Unit if no parameters)
 */
abstract class UseCase<T, P> {
    abstract operator fun invoke(params: P): Result<T>

    /**
     * Executes the use case as a Flow for reactive updates.
     */
    fun asFlow(params: P): Flow<Result<T>> = flow { emit(invoke(params)) }
}

/**
 * Base class for use cases with no input parameters.
 */
abstract class NoParamUseCase<T> : UseCase<T, Unit>() {
    final override operator fun invoke(params: Unit): Result<T> = execute()

    abstract fun execute(): Result<T>

    fun asFlow(): Flow<Result<T>> = flow { emit(execute()) }
}

/**
 * Base class for use cases that return a Flow (for reactive data).
 */
abstract class FlowUseCase<T, P> {
    abstract operator fun invoke(params: P): Flow<Result<T>>
}

/**
 * Base class for flow use cases with no input parameters.
 */
abstract class NoParamFlowUseCase<T> : FlowUseCase<T, Unit>() {
    final override operator fun invoke(params: Unit): Flow<Result<T>> = execute()

    abstract fun execute(): Flow<Result<T>>
}

/**
 * Functional interfaces for simpler use cases.
 */
@FunctionalInterface
interface UseCase1<T, P1> {
    operator fun invoke(p1: P1): Result<T>
}

@FunctionalInterface
interface UseCase2<T, P1, P2> {
    operator fun invoke(p1: P1, p2: P2): Result<T>
}

@FunctionalInterface
interface UseCase3<T, P1, P2, P3> {
    operator fun invoke(p1: P1, p2: P2, p3: P3): Result<T>
}
