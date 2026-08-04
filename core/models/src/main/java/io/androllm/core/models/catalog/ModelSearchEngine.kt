package io.androllm.core.models.catalog

/**
 * 16 filter dimensions. A null/empty dimension means "no constraint".
 */
data class CatalogFilters(
    val families: Set<String> = emptySet(),
    val architectures: Set<String> = emptySet(),
    val categories: Set<CatalogCategory> = emptySet(),
    val tags: Set<String> = emptySet(),
    val licenses: Set<String> = emptySet(),
    val quantLevels: Set<QuantLevel> = emptySet(),
    val minSizeBytes: Long? = null,
    val maxSizeBytes: Long? = null,
    val minContextLength: Int? = null,
    val maxContextLength: Int? = null,
    val minParametersB: Double? = null,
    val maxParametersB: Double? = null,
    val onlyWithChatTemplate: Boolean = false,
    val onlyUngated: Boolean = false,
    val modalities: Set<Modality> = emptySet(),
    val statuses: Set<CatalogStatus> = emptySet()
) {
    companion object {
        val EMPTY = CatalogFilters()
    }
}

enum class CatalogSortOption(val label: String) {
    NAME("Name A-Z"),
    SIZE_ASC("Size: smallest first"),
    SIZE_DESC("Size: largest first"),
    DOWNLOADS("Downloads"),
    LIKES("Likes"),
    TRENDING("Trending"),
    NEWEST("Newest"),
    SMALLEST_PARAMS("Fewest parameters"),
    LARGEST_PARAMS("Most parameters"),
    LONGEST_CONTEXT("Longest context"),
    LEAST_RAM("Least RAM required")
}

/**
 * Search / filter / sort over catalog metadata. Pure functions, no dependencies.
 *
 * Search covers 12 keys: name, description, family, architecture, categories,
 * tags, license, author, quantization, sizeBytes, contextLength, parameters.
 * [CatalogFilters] covers 16 filter dimensions. [CatalogSortOption] covers 11 orders.
 */
object ModelSearchEngine {

    private fun searchableText(model: CatalogModel): String = buildString {
        append(model.name).append(' ')
        append(model.description).append(' ')
        append(model.family).append(' ')
        append(model.architecture).append(' ')
        append(model.categories.joinToString(" ")).append(' ')
        append(model.tags.joinToString(" ")).append(' ')
        append(model.license).append(' ')
        append(model.author).append(' ')
        append(model.quantization).append(' ')
        append(model.sizeBytes).append(' ')
        append(model.contextLength).append(' ')
        append(model.parameters).append(' ')
        append(model.modelType ?: "")
    }.lowercase()

    /**
     * All query tokens must match at least one of the 12 search keys
     * (substring match, case-insensitive).
     */
    fun search(models: List<CatalogModel>, query: String): List<CatalogModel> {
        val tokens = query.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return models
        return models.filter { model ->
            val haystack = searchableText(model)
            tokens.all { token ->
                haystack.contains(token.lowercase()) ||
                    model.repoId.lowercase().contains(token.lowercase()) ||
                    model.fileName.lowercase().contains(token.lowercase())
            }
        }
    }

    fun search(
        models: List<CatalogModel>,
        query: String,
        filters: CatalogFilters,
        sort: CatalogSortOption
    ): List<CatalogModel> {
        return sort(filter(search(models, query), filters), sort)
    }

    fun filter(models: List<CatalogModel>, filters: CatalogFilters): List<CatalogModel> {
        return models.filter { model ->
            (filters.families.isEmpty() || model.family in filters.families) &&
                (filters.architectures.isEmpty() || model.architecture in filters.architectures) &&
                (filters.categories.isEmpty() || model.categoryValues.any { it in filters.categories }) &&
                (filters.tags.isEmpty() || model.tags.any { it in filters.tags }) &&
                (filters.licenses.isEmpty() || model.license in filters.licenses) &&
                (filters.quantLevels.isEmpty() || model.quantLevel in filters.quantLevels) &&
                (filters.minSizeBytes == null || model.sizeBytes >= filters.minSizeBytes) &&
                (filters.maxSizeBytes == null || model.sizeBytes <= filters.maxSizeBytes) &&
                (filters.minContextLength == null || model.contextLength >= filters.minContextLength) &&
                (filters.maxContextLength == null || model.contextLength <= filters.maxContextLength) &&
                (filters.minParametersB == null || (model.parameterCountB ?: 0.0) >= filters.minParametersB) &&
                (filters.maxParametersB == null || (model.parameterCountB ?: Double.MAX_VALUE) <= filters.maxParametersB) &&
                (!filters.onlyWithChatTemplate || !model.chatTemplate.isNullOrBlank()) &&
                (!filters.onlyUngated || !model.isGated) &&
                (filters.modalities.isEmpty() || model.modalityValue in filters.modalities) &&
                (filters.statuses.isEmpty() || model.statusValue in filters.statuses)
        }
    }

    fun sort(models: List<CatalogModel>, option: CatalogSortOption): List<CatalogModel> {
        return when (option) {
            CatalogSortOption.NAME -> models.sortedBy { it.name.lowercase() }
            CatalogSortOption.SIZE_ASC -> models.sortedBy { it.sizeBytes }
            CatalogSortOption.SIZE_DESC -> models.sortedByDescending { it.sizeBytes }
            CatalogSortOption.DOWNLOADS -> models.sortedByDescending { it.downloads }
            CatalogSortOption.LIKES -> models.sortedByDescending { it.likes }
            CatalogSortOption.TRENDING -> models.sortedByDescending { it.trendingScore }
            CatalogSortOption.NEWEST -> models.sortedByDescending { it.publishedAt }
            CatalogSortOption.SMALLEST_PARAMS ->
                models.sortedBy { it.parameterCountB ?: Double.MAX_VALUE }
            CatalogSortOption.LARGEST_PARAMS ->
                models.sortedByDescending { it.parameterCountB ?: Double.MIN_VALUE }
            CatalogSortOption.LONGEST_CONTEXT -> models.sortedByDescending { it.contextLength }
            CatalogSortOption.LEAST_RAM -> models.sortedBy { it.minRamGb }
        }
    }
}
