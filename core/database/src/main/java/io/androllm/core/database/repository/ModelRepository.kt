package io.androllm.core.database.repository

import io.androllm.core.common.BaseRepository
import io.androllm.core.common.Result
import io.androllm.core.common.runCatching
import io.androllm.core.database.ModelDao
import io.androllm.core.database.toDomain
import io.androllm.core.database.toEntity
import io.androllm.core.models.DownloadStatus
import io.androllm.core.models.Model
import io.androllm.core.models.ModelStatus
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Repository for models backed by Room.
 */
@Singleton
class ModelRepository @Inject constructor(
    private val modelDao: ModelDao
) : BaseRepository<Model, String> {

    override fun getById(id: String): Flow<Result<Model>> =
        modelDao.observeById(id).map { entity ->
            entity?.toDomain()?.let { Result.success(it) } ?: Result.error("Model not found: $id")
        }

    override fun getAll(): Flow<Result<List<Model>>> =
        modelDao.observeAll().map { entities ->
            Result.success(entities.map { it.toDomain() })
        }

    /**
     * Observes all models as domain models.
     */
    fun observeAllModels(): Flow<List<Model>> =
        modelDao.observeAll().map { entities -> entities.map { it.toDomain() } }

    /**
     * Observes downloaded models only.
     */
    fun observeDownloaded(): Flow<List<Model>> =
        modelDao.observeDownloaded().map { entities -> entities.map { it.toDomain() } }

    override suspend fun upsert(entity: Model): Result<String> = io.androllm.core.common.runCatching {
        modelDao.upsert(entity.toEntity())
        entity.id
    }

    override suspend fun deleteById(id: String): Result<Unit> = io.androllm.core.common.runCatching {
        modelDao.deleteById(id)
    }

    override suspend fun deleteAll(): Result<Unit> = io.androllm.core.common.runCatching {
        modelDao.deleteAll()
    }

    override suspend fun existsById(id: String): Result<Boolean> = io.androllm.core.common.runCatching {
        modelDao.existsById(id)
    }

    override suspend fun count(): Result<Int> = io.androllm.core.common.runCatching {
        modelDao.count()
    }

    /**
     * Marks a model as loaded or unloaded.
     */
    suspend fun updateLoadState(id: String, isLoaded: Boolean, status: ModelStatus): Result<Unit> = io.androllm.core.common.runCatching {
        modelDao.updateLoadState(id, isLoaded, status.name, System.currentTimeMillis())
    }

    /**
     * Updates the download state of a model.
     */
    suspend fun updateDownloadState(
        id: String,
        isDownloaded: Boolean,
        downloadStatus: DownloadStatus,
        filePath: String?
    ): Result<Unit> = io.androllm.core.common.runCatching {
        modelDao.updateDownloadState(id, isDownloaded, downloadStatus.name, filePath, System.currentTimeMillis())
    }

    /**
     * Enriches a downloaded model with GGUF header metadata.
     */
    suspend fun updateDownloadMetadata(
        id: String,
        architecture: String,
        quantization: String,
        contextLength: Int,
        license: String
    ): Result<Unit> = io.androllm.core.common.runCatching {
        modelDao.updateDownloadMetadata(id, architecture, quantization, contextLength, license, System.currentTimeMillis())
    }

    suspend fun setFavorite(id: String, isFavorite: Boolean): Result<Unit> = io.androllm.core.common.runCatching {
        modelDao.updateFavorite(id, isFavorite)
    }

    suspend fun setDefaultModel(id: String): Result<Unit> = io.androllm.core.common.runCatching {
        modelDao.clearDefaults()
        modelDao.setDefault(id)
    }

    suspend fun getDefaultModel(): Result<Model?> = io.androllm.core.common.runCatching {
        modelDao.getDefaultModel()?.toDomain()
    }

    suspend fun renameModel(id: String, newName: String): Result<Unit> = io.androllm.core.common.runCatching {
        modelDao.updateName(id, newName, System.currentTimeMillis())
    }

    suspend fun updateLastUsed(id: String): Result<Unit> = io.androllm.core.common.runCatching {
        modelDao.updateLastUsed(id, System.currentTimeMillis())
    }

    fun searchModels(query: String): Flow<List<Model>> =
        modelDao.searchModels(query).map { entities -> entities.map { it.toDomain() } }
}
