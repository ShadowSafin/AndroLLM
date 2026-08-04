package io.androllm.core.database

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Data access object for models.
 */
@Dao
interface ModelDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(model: ModelEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(models: List<ModelEntity>)

    @Update
    suspend fun update(model: ModelEntity)

    @Delete
    suspend fun delete(model: ModelEntity)

    @Query("DELETE FROM models WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM models")
    suspend fun deleteAll()

    @Query("SELECT * FROM models WHERE id = :id")
    fun observeById(id: String): Flow<ModelEntity?>

    @Query("SELECT * FROM models ORDER BY name ASC")
    fun observeAll(): Flow<List<ModelEntity>>

    @Query("SELECT * FROM models WHERE is_downloaded = 1 ORDER BY name ASC")
    fun observeDownloaded(): Flow<List<ModelEntity>>

    @Query("SELECT * FROM models WHERE id = :id")
    suspend fun getById(id: String): ModelEntity?

    @Query("SELECT * FROM models ORDER BY name ASC")
    suspend fun getAll(): List<ModelEntity>

    @Query("SELECT * FROM models WHERE is_downloaded = 1 ORDER BY name ASC")
    suspend fun getDownloaded(): List<ModelEntity>

    @Query("SELECT COUNT(*) FROM models WHERE id = :id")
    suspend fun existsById(id: String): Boolean

    @Query("SELECT COUNT(*) FROM models")
    suspend fun count(): Int

    @Query("UPDATE models SET is_loaded = :isLoaded, status = :status, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateLoadState(id: String, isLoaded: Boolean, status: String, updatedAt: Long)

    @Query("UPDATE models SET is_downloaded = :isDownloaded, download_status = :downloadStatus, file_path = :filePath, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateDownloadState(
        id: String,
        isDownloaded: Boolean,
        downloadStatus: String,
        filePath: String?,
        updatedAt: Long
    )

    @Query("UPDATE models SET architecture = :architecture, quantization = :quantization, context_length = :contextLength, license = :license, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateDownloadMetadata(
        id: String,
        architecture: String,
        quantization: String,
        contextLength: Int,
        license: String,
        updatedAt: Long
    )

    @Query("UPDATE models SET is_favorite = :isFavorite WHERE id = :id")
    suspend fun updateFavorite(id: String, isFavorite: Boolean)

    @Query("UPDATE models SET is_default = 0")
    suspend fun clearDefaults()

    @Query("UPDATE models SET is_default = 1 WHERE id = :id")
    suspend fun setDefault(id: String)

    @Query("UPDATE models SET name = :name, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateName(id: String, name: String, updatedAt: Long)

    @Query("UPDATE models SET last_used_date = :timestamp WHERE id = :id")
    suspend fun updateLastUsed(id: String, timestamp: Long)

    @Query("SELECT * FROM models WHERE is_default = 1 LIMIT 1")
    suspend fun getDefaultModel(): ModelEntity?

    @Query("SELECT * FROM models WHERE name LIKE '%' || :query || '%' OR description LIKE '%' || :query || '%' ORDER BY name ASC")
    fun searchModels(query: String): Flow<List<ModelEntity>>
}
