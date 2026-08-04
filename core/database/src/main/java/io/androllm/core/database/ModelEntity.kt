package io.androllm.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import io.androllm.core.common.AppConstants
import io.androllm.core.models.DownloadStatus
import io.androllm.core.models.Model
import io.androllm.core.models.ModelFormat
import io.androllm.core.models.ModelStatus

/**
 * Room entity representing an LLM model.
 */
@Entity(
    tableName = AppConstants.Database.MODEL_TABLE,
    indices = [Index(value = ["name"], unique = true)]
)
data class ModelEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    val description: String = "",
    @ColumnInfo(name = "file_path")
    val filePath: String? = null,
    @ColumnInfo(name = "file_size")
    val fileSize: Long = 0,
    val format: String = ModelFormat.GGUF.name,
    val parameters: String = "",
    val quantization: String = "",
    @ColumnInfo(name = "context_length")
    val contextLength: Int = 4096,
    @ColumnInfo(name = "download_url")
    val downloadUrl: String? = null,
    @ColumnInfo(name = "is_downloaded")
    val isDownloaded: Boolean = false,
    @ColumnInfo(name = "is_loaded")
    val isLoaded: Boolean = false,
    @ColumnInfo(name = "download_status")
    val downloadStatus: String = DownloadStatus.NOT_DOWNLOADED.name,
    val status: String = ModelStatus.NOT_LOADED.name,
    @ColumnInfo(name = "created_at")
    val createdAt: Long = 0,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = 0,
    val sha256: String? = null,
    val architecture: String = "llama",
    val family: String = "Llama",
    val license: String = "Apache-2.0",
    @ColumnInfo(name = "min_ram_gb")
    val minRamGb: Float = 4.0f,
    @ColumnInfo(name = "recommended_ram_gb")
    val recommendedRamGb: Float = 8.0f,
    @ColumnInfo(name = "is_favorite")
    val isFavorite: Boolean = false,
    @ColumnInfo(name = "is_default")
    val isDefault: Boolean = false,
    @ColumnInfo(name = "added_date")
    val addedDate: Long = 0,
    @ColumnInfo(name = "last_used_date")
    val lastUsedDate: Long = 0
)

/**
 * Maps a database entity to the domain model.
 */
fun ModelEntity.toDomain(): Model = Model(
    id = id,
    name = name,
    description = description,
    filePath = filePath,
    fileSize = fileSize,
    format = runCatching { ModelFormat.valueOf(format) }.getOrDefault(ModelFormat.UNKNOWN),
    parameters = parameters,
    quantization = quantization,
    contextLength = contextLength,
    downloadUrl = downloadUrl,
    isDownloaded = isDownloaded,
    isLoaded = isLoaded,
    downloadStatus = runCatching { DownloadStatus.valueOf(downloadStatus) }.getOrDefault(DownloadStatus.NOT_DOWNLOADED),
    status = runCatching { ModelStatus.valueOf(status) }.getOrDefault(ModelStatus.NOT_LOADED),
    createdAt = createdAt,
    updatedAt = updatedAt,
    sha256 = sha256,
    architecture = architecture,
    family = family,
    license = license,
    minRamGb = minRamGb,
    recommendedRamGb = recommendedRamGb,
    isFavorite = isFavorite,
    isDefault = isDefault,
    addedDate = addedDate,
    lastUsedDate = lastUsedDate
)

/**
 * Maps a domain model to the database entity.
 */
fun Model.toEntity(): ModelEntity = ModelEntity(
    id = id,
    name = name,
    description = description,
    filePath = filePath,
    fileSize = fileSize,
    format = format.name,
    parameters = parameters,
    quantization = quantization,
    contextLength = contextLength,
    downloadUrl = downloadUrl,
    isDownloaded = isDownloaded,
    isLoaded = isLoaded,
    downloadStatus = downloadStatus.name,
    status = status.name,
    createdAt = createdAt,
    updatedAt = updatedAt,
    sha256 = sha256,
    architecture = architecture,
    family = family,
    license = license,
    minRamGb = minRamGb,
    recommendedRamGb = recommendedRamGb,
    isFavorite = isFavorite,
    isDefault = isDefault,
    addedDate = addedDate,
    lastUsedDate = lastUsedDate
)
