package io.androllm.core.network.api

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class HfModelDto(
    val id: String = "",
    val author: String? = null,
    val downloads: Long = 0,
    val likes: Long = 0,
    val tags: List<String> = emptyList(),
    @SerialName("lastModified")
    val lastModified: String = "",
    @SerialName("pipeline_tag")
    val pipelineTag: String = "text-generation",
    val siblings: List<HfSiblingDto>? = null
)

@Serializable
data class HfSiblingDto(
    val rfilename: String = ""
)

/**
 * Direct REST API client communicating with Hugging Face official endpoints.
 */
@Singleton
class HuggingFaceApi @Inject constructor(
    private val httpClient: HttpClient
) {
    companion object {
        private const val BASE_URL = "https://huggingface.co"
    }

    /**
     * Searches Hugging Face models for GGUF entries matching [query] and [sort].
     */
    suspend fun searchModels(
        query: String = "",
        sort: String = "downloads",
        limit: Int = 30
    ): List<HfModelDto> {
        val sortParam = when (sort.lowercase()) {
            "likes" -> "likes"
            "last_modified" -> "lastModified"
            else -> "downloads"
        }

        val url = "$BASE_URL/api/models?search=${query.trim()}&filter=gguf&sort=$sortParam&direction=-1&limit=$limit"
        return httpClient.get(url).body()
    }

    /**
     * Fetches metadata and file list for a specific Hugging Face model [modelId].
     */
    suspend fun getModelDetails(modelId: String): HfModelDto {
        val url = "$BASE_URL/api/models/$modelId"
        return httpClient.get(url).body()
    }

    /**
     * Fetches raw README.md markdown text for [modelId].
     */
    suspend fun getReadmeText(modelId: String): String {
        val url = "$BASE_URL/$modelId/raw/main/README.md"
        val response = httpClient.get(url)
        return response.bodyAsText()
    }
}
