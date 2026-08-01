package io.androllm.core.common

import kotlinx.coroutines.flow.Flow

/**
 * Base repository interface defining common operations for all repositories.
 * Repositories should implement this interface to provide a consistent API.
 *
 * @param <T> The entity type this repository manages
 * @param <ID> The identifier type for the entity
 */
interface BaseRepository<T, ID> {
    /**
     * Gets an entity by its ID as a Flow for reactive updates.
     */
    fun getById(id: ID): Flow<Result<T>>

    /**
     * Gets all entities as a Flow for reactive updates.
     */
    fun getAll(): Flow<Result<List<T>>>

    /**
     * Inserts or updates an entity.
     */
    suspend fun upsert(entity: T): Result<ID>

    /**
     * Deletes an entity by its ID.
     */
    suspend fun deleteById(id: ID): Result<Unit>

    /**
     * Deletes all entities.
     */
    suspend fun deleteAll(): Result<Unit>

    /**
     * Checks if an entity exists by ID.
     */
    suspend fun existsById(id: ID): Result<Boolean>

    /**
     * Gets the count of entities.
     */
    suspend fun count(): Result<Int>
}

/**
 * Paging support for repositories that need pagination.
 */
interface PagingRepository<T, ID> : BaseRepository<T, ID> {
    /**
     * Gets a page of entities.
     */
    suspend fun getPage(page: Int, pageSize: Int): Result<PagingResult<T>>

    /**
     * Gets a page of entities as a Flow.
     */
    fun getPageFlow(page: Int, pageSize: Int): Flow<Result<PagingResult<T>>>
}

/**
 * Result of a paging operation.
 */
data class PagingResult<T>(
    val items: List<T>,
    val currentPage: Int,
    val pageSize: Int,
    val totalItems: Long,
    val totalPages: Int
) {
    val hasNextPage: Boolean = currentPage < totalPages
    val hasPreviousPage: Boolean = currentPage > 1
    val isEmpty: Boolean = items.isEmpty()
    val isNotEmpty: Boolean = items.isNotEmpty()
}

/**
 * Search repository interface for repositories that support search.
 */
interface SearchRepository<T, ID> : BaseRepository<T, ID> {
    /**
     * Searches for entities matching the query.
     */
    suspend fun search(query: String, page: Int, pageSize: Int): Result<PagingResult<T>>

    /**
     * Searches for entities matching the query as a Flow.
     */
    fun searchFlow(query: String, page: Int, pageSize: Int): Flow<Result<PagingResult<T>>>
}
