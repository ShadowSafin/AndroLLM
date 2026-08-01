package io.androllm.core.network.repository

import io.androllm.core.common.Result
import io.androllm.core.models.RemoteModelDetails
import io.androllm.core.models.RemoteModelSummary
import io.androllm.core.models.RepositoryFilter
import kotlinx.coroutines.flow.Flow

/**
 * Modular repository provider interface abstraction.
 * Decouples the UI from specific online model sources (Hugging Face, Cloudflare R2, GitHub, etc.).
 */
interface ModelRepositoryProvider {

    /**
     * Unique identifier of this repository provider (e.g. "huggingface").
     */
    val providerId: String

    /**
     * Display name of this repository provider (e.g. "Hugging Face Hub").
     */
    val providerName: String

    /**
     * Searches models matching [filter].
     */
    fun searchModels(filter: RepositoryFilter): Flow<Result<List<RemoteModelSummary>>>

    /**
     * Fetches detailed metadata for a model identified by [modelId].
     */
    fun getModelDetails(modelId: String): Flow<Result<RemoteModelDetails>>

    /**
     * Fetches the raw README markdown content for [modelId].
     */
    fun getReadme(modelId: String): Flow<Result<String>>
}
