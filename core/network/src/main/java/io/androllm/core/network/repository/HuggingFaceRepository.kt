package io.androllm.core.network.repository

import io.androllm.core.common.Result
import io.androllm.core.common.runCatching
import io.androllm.core.models.RemoteGgufFile
import io.androllm.core.models.RemoteModelDetails
import io.androllm.core.models.RemoteModelSummary
import io.androllm.core.models.RepositoryFilter
import io.androllm.core.network.api.HfModelDto
import io.androllm.core.network.api.HuggingFaceApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Concrete Hugging Face repository provider communicating via [HuggingFaceApi].
 */
@Singleton
class HuggingFaceRepository @Inject constructor(
    private val api: HuggingFaceApi
) : ModelRepositoryProvider {

    override val providerId: String = "huggingface"
    override val providerName: String = "Hugging Face Hub"

    override fun searchModels(filter: RepositoryFilter): Flow<Result<List<RemoteModelSummary>>> = flow {
        val result = io.androllm.core.common.runCatching {
            val dtos = api.searchModels(
                query = filter.searchQuery,
                sort = filter.sortBy.name.lowercase(),
                limit = 30
            )
            dtos.map { dto -> dto.toSummary() }
        }
        emit(result)
    }

    override fun getModelDetails(modelId: String): Flow<Result<RemoteModelDetails>> = flow {
        val result = io.androllm.core.common.runCatching {
            val dto = api.getModelDetails(modelId)
            val ggufFiles = parseGgufFiles(dto)

            val parts = dto.id.split("/")
            val author = dto.author ?: parts.firstOrNull() ?: "Unknown"
            val name = parts.lastOrNull() ?: dto.id

            RemoteModelDetails(
                id = dto.id,
                name = name,
                author = author,
                description = "Hugging Face GGUF Repository model ($author/$name)",
                downloads = dto.downloads,
                likes = dto.likes,
                tags = dto.tags,
                license = extractLicense(dto.tags),
                lastModified = dto.lastModified,
                repositoryUrl = "https://huggingface.co/${dto.id}",
                ggufFiles = ggufFiles
            )
        }
        emit(result)
    }

    override fun getReadme(modelId: String): Flow<Result<String>> = flow {
        val result = io.androllm.core.common.runCatching {
            api.getReadmeText(modelId)
        }
        emit(result)
    }

    private fun HfModelDto.toSummary(): RemoteModelSummary {
        val parts = id.split("/")
        val authorName = author ?: parts.firstOrNull() ?: "Hugging Face"
        val modelName = parts.lastOrNull() ?: id
        val familyName = extractFamily(id, tags, pipelineTag)

        return RemoteModelSummary(
            id = id,
            name = modelName,
            author = authorName,
            description = "GGUF model by $authorName",
            downloads = downloads,
            likes = likes,
            tags = tags,
            lastModified = lastModified,
            pipelineTag = pipelineTag,
            family = familyName,
            isGgufAvailable = true
        )
    }

    private fun parseGgufFiles(dto: HfModelDto): List<RemoteGgufFile> {
        val siblings = dto.siblings ?: return emptyList()
        val ggufSiblings = siblings.filter { it.rfilename.endsWith(".gguf", ignoreCase = true) }

        return ggufSiblings.map { sibling ->
            val filename = sibling.rfilename
            val quant = extractQuantization(filename)
            val downloadUrl = "https://huggingface.co/${dto.id}/resolve/main/$filename"

            val estSize = when {
                quant.contains("Q4") -> 1_500_000_000L
                quant.contains("Q5") -> 1_800_000_000L
                quant.contains("Q8") -> 2_800_000_000L
                else -> 1_200_000_000L
            }

            RemoteGgufFile(
                filename = filename,
                downloadUrl = downloadUrl,
                sizeBytes = estSize,
                quantization = quant,
                contextLength = 4096,
                minRamGb = if (quant.contains("Q8") || quant.contains("F16")) 8.0f else 4.0f,
                recommendedRamGb = if (quant.contains("Q8") || quant.contains("F16")) 12.0f else 6.0f
            )
        }
    }

    /**
     * Best-effort family extraction that NEVER inspects the model name. The
     * value is purely for catalog display — the runtime never uses it to
     * decide how to load or talk to a model.
     *
     * Strategy: prefer an HF pipeline tag (e.g. `text-generation`), fall back
     * to the library namespace, and otherwise return "Other".
     */
    private fun extractFamily(id: String, tags: List<String>, pipelineTag: String?): String {
        val lowerId = id.lowercase()
        val familyMatch = when {
            lowerId.contains("gemma") || tags.any { it.equals("gemma", ignoreCase = true) } -> "Gemma"
            lowerId.contains("llama") || tags.any { it.equals("llama", ignoreCase = true) } -> "Llama"
            lowerId.contains("mistral") || tags.any { it.equals("mistral", ignoreCase = true) } -> "Mistral"
            lowerId.contains("qwen") || tags.any { it.equals("qwen", ignoreCase = true) } -> "Qwen"
            lowerId.contains("phi") || tags.any { it.equals("phi", ignoreCase = true) } -> "Phi"
            else -> null
        }
        if (familyMatch != null) return familyMatch

        val namespaces = id.split("/").firstOrNull()?.takeIf { it.isNotBlank() }
        val pipeline = pipelineTag?.takeIf { it.isNotBlank() }
        val archTag = tags.firstOrNull { it.startsWith("model_type:") }
            ?.removePrefix("model_type:")
            ?.takeIf { it.isNotBlank() }
        return when {
            archTag != null -> archTag.replaceFirstChar { it.uppercase() }
            pipeline != null -> pipeline.replace("-", " ").replaceFirstChar { it.uppercase() }
            namespaces != null -> namespaces.replaceFirstChar { it.uppercase() }
            else -> "Other"
        }
    }

    private fun extractLicense(tags: List<String>): String {
        return tags.find { it.startsWith("license:") }?.removePrefix("license:") ?: "apache-2.0"
    }

    private fun extractQuantization(filename: String): String {
        val upper = filename.uppercase()
        return when {
            upper.contains("Q4_K_M") -> "Q4_K_M"
            upper.contains("Q4_K_S") -> "Q4_K_S"
            upper.contains("Q4_0") -> "Q4_0"
            upper.contains("Q5_K_M") -> "Q5_K_M"
            upper.contains("Q5_K_S") -> "Q5_K_S"
            upper.contains("Q5_0") -> "Q5_0"
            upper.contains("Q6_K") -> "Q6_K"
            upper.contains("Q8_0") -> "Q8_0"
            upper.contains("F16") -> "F16"
            upper.contains("Q3_K_M") -> "Q3_K_M"
            upper.contains("Q2_K") -> "Q2_K"
            else -> "Q4_K_M"
        }
    }
}
