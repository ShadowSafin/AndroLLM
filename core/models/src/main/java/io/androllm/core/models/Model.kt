package io.androllm.core.models

import io.androllm.core.common.AppConstants
import kotlinx.serialization.Serializable

/**
 * Categories for catalog model organization.
 */
enum class ModelCategory {
    RECOMMENDED,
    CHAT,
    REASONING,
    MOBILE_OPTIMIZED
}

/**
 * Represents an LLM model available to the app.
 * Supports GGUF/GGML/SafeTensors formats for local inference.
 */
@Serializable
data class Model(
    val id: String,
    val name: String,
    val description: String = "",
    val filePath: String? = null,
    val fileSize: Long = 0,
    val format: ModelFormat = ModelFormat.GGUF,
    val parameters: String = "",
    val quantization: String = "",
    val contextLength: Int = AppConstants.Model.DEFAULT_CONTEXT_LENGTH,
    val downloadUrl: String? = null,
    val isDownloaded: Boolean = false,
    val isLoaded: Boolean = false,
    val downloadStatus: DownloadStatus = DownloadStatus.NOT_DOWNLOADED,
    val status: ModelStatus = ModelStatus.NOT_LOADED,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val sha256: String? = null,
    val architecture: String = "llama",
    val family: String = "Llama",
    val minRamGb: Float = 4.0f,
    val recommendedRamGb: Float = 8.0f,
    val isFavorite: Boolean = false,
    val isDefault: Boolean = false,
    val addedDate: Long = 0,
    val lastUsedDate: Long = 0,
    val category: ModelCategory = ModelCategory.CHAT,
    val badges: List<String> = emptyList(),
    val strengths: List<String> = emptyList(),
    val weaknesses: List<String> = emptyList(),
    val expectedTokSec: String = "30-50 tok/s",
    val license: String = "Apache-2.0"
)
