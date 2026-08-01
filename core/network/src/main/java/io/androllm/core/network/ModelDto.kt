package io.androllm.core.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * DTO for a model entry in the remote catalog.
 */
@Serializable
data class ModelDto(
    val id: String,
    val name: String,
    val description: String = "",
    val author: String = "",
    @SerialName("file_url")
    val fileUrl: String? = null,
    @SerialName("file_size")
    val fileSize: Long = 0,
    val format: String = "gguf",
    val parameters: String = "",
    val quantization: String = "",
    @SerialName("context_length")
    val contextLength: Int = 4096,
    val license: String = ""
)

/**
 * DTO for a paginated model catalog response.
 */
@Serializable
data class ModelCatalogDto(
    val models: List<ModelDto> = emptyList(),
    val total: Int = 0,
    val page: Int = 1,
    @SerialName("page_size")
    val pageSize: Int = 20
)
