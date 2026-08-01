package io.androllm.core.models

import kotlinx.serialization.Serializable

/**
 * Summary of a model hosted on a remote repository (e.g. Hugging Face).
 */
@Serializable
data class RemoteModelSummary(
    val id: String,
    val name: String,
    val author: String,
    val description: String = "",
    val downloads: Long = 0,
    val likes: Long = 0,
    val tags: List<String> = emptyList(),
    val lastModified: String = "",
    val pipelineTag: String = "text-generation",
    val family: String = "Unknown",
    val isGgufAvailable: Boolean = true
)

/**
 * Detailed metadata of a remote model including file variants and license.
 */
@Serializable
data class RemoteModelDetails(
    val id: String,
    val name: String,
    val author: String,
    val description: String = "",
    val downloads: Long = 0,
    val likes: Long = 0,
    val tags: List<String> = emptyList(),
    val license: String = "Unknown",
    val lastModified: String = "",
    val repositoryUrl: String = "",
    val ggufFiles: List<RemoteGgufFile> = emptyList()
)

/**
 * A specific downloadable GGUF file entry from a remote repository.
 */
@Serializable
data class RemoteGgufFile(
    val filename: String,
    val downloadUrl: String,
    val sizeBytes: Long = 0,
    val quantization: String = "Q4_K_M",
    val contextLength: Int = 4096,
    val sha256: String? = null,
    val minRamGb: Float = 4.0f,
    val recommendedRamGb: Float = 8.0f
)

/**
 * Filter parameters for searching remote model repositories.
 */
data class RepositoryFilter(
    val searchQuery: String = "",
    val family: String? = null,
    val quantization: String? = null,
    val sortBy: SortBy = SortBy.DOWNLOADS,
    val page: Int = 1
)

enum class SortBy {
    DOWNLOADS,
    LIKES,
    LAST_MODIFIED
}

/**
 * Real-time download progress snapshot for active/queued downloads.
 */
@Serializable
data class DownloadProgress(
    val modelId: String,
    val bytesDownloaded: Long = 0,
    val totalBytes: Long = 0,
    val speedMbps: Float = 0f,
    val etaSeconds: Long = 0,
    val progressPercent: Int = 0,
    val status: DownloadStatus = DownloadStatus.NOT_DOWNLOADED,
    val errorMessage: String? = null
)
