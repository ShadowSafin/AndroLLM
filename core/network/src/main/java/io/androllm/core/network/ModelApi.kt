package io.androllm.core.network

import io.androllm.core.common.Result
import io.androllm.core.common.runCatching
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import javax.inject.Inject
import javax.inject.Singleton

/**
 * API client for the remote model catalog.
 * Prepared for Phase 2 - currently returns empty catalogs.
 */
@Singleton
class ModelApi @Inject constructor(
    private val client: HttpClient
) {

    /**
     * Fetches the model catalog.
     */
    suspend fun getModelCatalog(page: Int = 1, pageSize: Int = 20): Result<ModelCatalogDto> =
        io.androllm.core.common.runCatching {
        // TODO: Point to real catalog endpoint in Phase 2.
        ModelCatalogDto()
    }

    /**
     * Fetches a single model by ID.
     */
    suspend fun getModelById(id: String): Result<ModelDto> = io.androllm.core.common.runCatching {
        // TODO: Point to real catalog endpoint in Phase 2.
        throw IllegalStateException("Model catalog is not available in Phase 1")
    }

    /**
     * Downloads a model file to the given destination.
     */
    suspend fun downloadModel(modelId: String, destination: java.io.File): Result<Long> =
        io.androllm.core.common.runCatching {
        // TODO: Implement streaming download with progress in Phase 2.
        throw IllegalStateException("Model downloads will be available in Phase 2")
    }
}
