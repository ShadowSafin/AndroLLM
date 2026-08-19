package io.androllm.core.models.catalog

import io.androllm.core.models.catalog.CatalogFilters
import io.androllm.core.models.catalog.CatalogSortOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelSearchEngineTest {

    private fun model(
        id: String,
        name: String,
        family: String = "Qwen",
        architecture: String = "qwen2",
        categories: List<String> = listOf("CHAT"),
        tags: List<String> = emptyList(),
        license: String = "Apache-2.0",
        author: String = "Qwen",
        repoId: String = "Qwen/$id-gguf",
        fileName: String = "$id-q4_k_m.gguf",
        sizeBytes: Long = 1_000_000_000,
        parameters: String = "1.5B",
        quantization: String = "Q4_K_M",
        contextLength: Int = 8192,
        downloads: Long = 100,
        likes: Long = 10,
        trendingScore: Long = 5,
        chatTemplate: String? = "chatml",
        modality: String = "TEXT",
        status: String = "STABLE",
        minRamGb: Float = 2.0f,
        sections: List<String> = emptyList(),
        expectedTokSec: String? = null,
        recommended: Boolean = false
    ) = CatalogModel(
        id = id, name = name, description = "A test model from $family", family = family,
        architecture = architecture, categories = categories, tags = tags, license = license,
        author = author, repoId = repoId, fileName = fileName, downloadUrl = "https://huggingface.co/$repoId/resolve/main/$fileName",
        sizeBytes = sizeBytes, parameters = parameters, quantization = quantization,
        contextLength = contextLength, chatTemplate = chatTemplate, minRamGb = minRamGb,
        downloads = downloads, likes = likes, trendingScore = trendingScore, modality = modality, status = status,
        sections = sections, expectedTokSec = expectedTokSec, recommended = recommended
    )

    private val models = listOf(
        model("tiny", "Tiny Chat", parameters = "0.5B", downloads = 9000,
            sections = listOf(CatalogSections.TINY), expectedTokSec = "25-35"),
        model("mid", "Mid Assistant", parameters = "1.5B", sizeBytes = 2_000_000_000,
            sections = listOf(CatalogSections.FEATURED), expectedTokSec = "12-18", recommended = true),
        model("codey", "Code Wizard", family = "DeepSeek", architecture = "deepseek",
            categories = listOf("CODE", "CHAT"), tags = listOf("code"), author = "DeepSeek",
            parameters = "7B", quantization = "Q5_K_M", contextLength = 16384, downloads = 50_000,
            likes = 500, trendingScore = 300, sections = listOf(CatalogSections.FEATURED), expectedTokSec = "8-12"),
        model("encoder", "Tiny Embedder", family = "BERT", architecture = "bert",
            categories = listOf("EMBEDDING"), chatTemplate = null, modality = "EMBEDDING",
            license = "MIT", parameters = "110M", quantization = "F16", sizeBytes = 500_000_000,
            sections = listOf(CatalogSections.EMBEDDING), expectedTokSec = null)
    )

    @Test
    fun emptyQueryReturnsAll() {
        assertEquals(4, ModelSearchEngine.search(models, "").size)
        assertEquals(4, ModelSearchEngine.search(models, "   ").size)
    }

    @Test
    fun searchesAcrossAllTwelveKeys() {
        assertTrue(ModelSearchEngine.search(models, "tiny").any { it.id == "tiny" })
        assertTrue(ModelSearchEngine.search(models, "code").any { it.id == "codey" })
        assertTrue(ModelSearchEngine.search(models, "bert").any { it.id == "encoder" })
        assertTrue(ModelSearchEngine.search(models, "deepseek").any { it.id == "codey" })
        assertTrue(ModelSearchEngine.search(models, "mit").any { it.id == "encoder" })
        assertTrue(ModelSearchEngine.search(models, "q5_k_m").any { it.id == "codey" })
        assertTrue(ModelSearchEngine.search(models, "16384").any { it.id == "codey" })
        assertTrue(ModelSearchEngine.search(models, "0.5B").any { it.id == "tiny" })
        assertTrue(ModelSearchEngine.search(models, "2,000,000,000").isEmpty())
        assertTrue(ModelSearchEngine.search(models, "2000000000").any { it.id == "mid" })
    }

    @Test
    fun multiTokenQueryRequiresAllTokens() {
        assertEquals(listOf("codey"), ModelSearchEngine.search(models, "code 7B").map { it.id })
        assertTrue(ModelSearchEngine.search(models, "code llama").isEmpty())
    }

    @Test
    fun filtersByEachDimension() {
        assertEquals(listOf("codey"), ModelSearchEngine.filter(models, CatalogFilters(families = setOf("DeepSeek"))).map { it.id })
        assertEquals(listOf("codey"), ModelSearchEngine.filter(models, CatalogFilters(architectures = setOf("deepseek"))).map { it.id })
        assertEquals(listOf("codey"), ModelSearchEngine.filter(models, CatalogFilters(categories = setOf(CatalogCategory.CODE))).map { it.id })
        assertEquals(listOf("encoder"), ModelSearchEngine.filter(models, CatalogFilters(licenses = setOf("MIT"))).map { it.id })
        assertEquals(listOf("codey"), ModelSearchEngine.filter(models, CatalogFilters(quantLevels = setOf(QuantLevel.Q5))).map { it.id })
        assertEquals(listOf("mid"), ModelSearchEngine.filter(models, CatalogFilters(minSizeBytes = 1_500_000_000)).map { it.id })
        assertEquals(listOf("tiny", "codey", "encoder"), ModelSearchEngine.filter(models, CatalogFilters(maxSizeBytes = 1_100_000_000)).map { it.id })
        assertEquals(listOf("codey"), ModelSearchEngine.filter(models, CatalogFilters(minContextLength = 10000)).map { it.id })
        assertEquals(listOf("tiny", "encoder"), ModelSearchEngine.filter(models, CatalogFilters(maxParametersB = 1.0)).map { it.id })
        assertEquals(listOf("codey"), ModelSearchEngine.filter(models, CatalogFilters(minParametersB = 5.0)).map { it.id })
        assertEquals(listOf("tiny", "mid", "codey"), ModelSearchEngine.filter(models, CatalogFilters(onlyWithChatTemplate = true)).map { it.id })
        assertEquals(listOf("encoder"), ModelSearchEngine.filter(models, CatalogFilters(modalities = setOf(Modality.EMBEDDING))).map { it.id })
        assertEquals(4, ModelSearchEngine.filter(models, CatalogFilters(statuses = setOf(CatalogStatus.STABLE))).size)
        assertEquals(4, ModelSearchEngine.filter(models, CatalogFilters(onlyUngated = true)).size)
        assertEquals(listOf("codey"), ModelSearchEngine.filter(models, CatalogFilters(tags = setOf("code"))).map { it.id })
        assertEquals(listOf("tiny"), ModelSearchEngine.filter(models, CatalogFilters(sections = setOf(CatalogSections.TINY))).map { it.id })
        assertEquals(listOf("mid", "codey"), ModelSearchEngine.filter(models, CatalogFilters(sections = setOf(CatalogSections.FEATURED))).map { it.id })
    }

    @Test
    fun combinedFiltersAreAnded() {
        val result = ModelSearchEngine.filter(
            models,
            CatalogFilters(families = setOf("DeepSeek"), quantLevels = setOf(QuantLevel.Q5))
        )
        assertEquals(listOf("codey"), result.map { it.id })
        val chatDeepSeek = ModelSearchEngine.filter(
            models,
            CatalogFilters(families = setOf("DeepSeek"), categories = setOf(CatalogCategory.CHAT))
        )
        assertEquals(listOf("codey"), chatDeepSeek.map { it.id })
    }

    @Test
    fun sortsInAllOrders() {
        assertEquals(listOf("codey", "mid", "tiny", "encoder"),
            ModelSearchEngine.sort(models, CatalogSortOption.NAME).map { it.id })
        assertEquals(listOf("encoder", "tiny", "codey", "mid"),
            ModelSearchEngine.sort(models, CatalogSortOption.SIZE_ASC).map { it.id })
        assertEquals(listOf("mid", "tiny", "codey", "encoder"),
            ModelSearchEngine.sort(models, CatalogSortOption.SIZE_DESC).map { it.id })
        assertEquals(listOf("codey", "tiny", "mid", "encoder"),
            ModelSearchEngine.sort(models, CatalogSortOption.DOWNLOADS).map { it.id })
        assertEquals(listOf("codey", "tiny", "mid", "encoder"),
            ModelSearchEngine.sort(models, CatalogSortOption.LIKES).map { it.id })
        assertEquals(listOf("codey", "tiny", "mid", "encoder"),
            ModelSearchEngine.sort(models, CatalogSortOption.TRENDING).map { it.id })
        assertEquals(listOf("encoder", "tiny", "mid", "codey"),
            ModelSearchEngine.sort(models, CatalogSortOption.SMALLEST_PARAMS).map { it.id })
        assertEquals(listOf("codey", "mid", "tiny", "encoder"),
            ModelSearchEngine.sort(models, CatalogSortOption.LARGEST_PARAMS).map { it.id })
        assertEquals(listOf("codey", "tiny", "mid", "encoder"),
            ModelSearchEngine.sort(models, CatalogSortOption.LONGEST_CONTEXT).map { it.id })
        assertEquals(listOf("tiny", "mid", "codey", "encoder"),
            ModelSearchEngine.sort(models, CatalogSortOption.LEAST_RAM).map { it.id })
    }

    @Test
    fun fastestSortUsesTokSecMidpoint() {
        // tiny(30) > mid(15) > codey(10) > encoder(unknown -> last)
        assertEquals(listOf("tiny", "mid", "codey", "encoder"),
            ModelSearchEngine.sort(models, CatalogSortOption.FASTEST).map { it.id })
    }

    @Test
    fun recommendedSortPutsRecommendedFirstThenTrending() {
        // mid is recommended; the rest fall back to trendingScore then downloads.
        assertEquals(listOf("mid", "codey", "tiny", "encoder"),
            ModelSearchEngine.sort(models, CatalogSortOption.RECOMMENDED).map { it.id })
    }
}
