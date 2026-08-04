package io.androllm.core.models.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecommendationEngineTest {

    private fun model(
        id: String,
        sizeBytes: Long,
        minRamGb: Float,
        recommendedRamGb: Float,
        quantLevel: String,
        downloads: Long = 1000,
        likes: Long = 100,
        trendingScore: Long = 50,
        parameters: String = "7B",
        categories: List<String> = listOf("CHAT"),
        recommended: Boolean = false,
        isGated: Boolean = false,
        status: String = "STABLE",
        hidden: Boolean = false
    ) = CatalogModel(
        id = id, name = "Model $id", description = "desc", family = "Test",
        architecture = "llama", categories = categories, tags = emptyList(),
        license = "Apache-2.0", author = "T", repoId = "t/$id-gguf",
        fileName = "$id-$quantLevel.gguf",
        downloadUrl = "https://huggingface.co/t/$id-gguf/resolve/main/$id-$quantLevel.gguf",
        sizeBytes = sizeBytes, parameters = parameters, quantization = quantLevel,
        contextLength = 8192, chatTemplate = "chatml", minRamGb = minRamGb,
        recommendedRamGb = recommendedRamGb, downloads = downloads, likes = likes,
        trendingScore = trendingScore, isGated = isGated, status = status, hidden = hidden
    )

    private fun gb(value: Double): Long = (value * 1_000_000_000).toLong()

    private val candidates = listOf(
        model("big70b", gb(41.0), 48.0f, 64.0f, "Q4_K_M", parameters = "70B"),
        model("mid13b", gb(8.1), 8.0f, 12.0f, "Q4_K_M", parameters = "13B"),
        model("small3b", gb(1.9), 2.0f, 4.0f, "Q4_K_M", parameters = "3B", downloads = 50_000, likes = 2000),
        model("tiny1b", gb(0.7), 1.0f, 2.0f, "Q5_K_M", parameters = "1B", recommended = true),
        model("gated7b", gb(4.5), 4.0f, 8.0f, "Q4_K_M", parameters = "7B", isGated = true),
        model("archived3b", gb(1.9), 2.0f, 4.0f, "Q4_K_M", parameters = "3B", status = "ARCHIVED"),
        model("hidden3b", gb(1.9), 2.0f, 4.0f, "Q4_K_M", parameters = "3B", hidden = true)
    )

    @Test
    fun excludesGatedArchivedAndHidden() {
        val result = RecommendationEngine.recommend(candidates, 8.0f, topN = 20)
        val ids = result.map { it.model.id }
        assertTrue("gated excluded", ids.none { it == "gated7b" })
        assertTrue("archived excluded", ids.none { it == "archived3b" })
        assertTrue("hidden excluded", ids.none { it == "hidden3b" })
    }

    @Test
    fun smallRamDeviceRanksSmallModelsFirst() {
        val result = RecommendationEngine.recommend(candidates, 4.0f, topN = 10)
        val ids = result.map { it.model.id }
        assertTrue("tiny fits 4GB", ids.contains("tiny1b"))
        assertTrue("small3b fits 4GB", ids.contains("small3b"))
        assertTrue("mid13b ranks above small models", ids.indexOf("mid13b") > ids.indexOf("small3b"))
        assertTrue("big70b ranks above small models", ids.indexOf("big70b") > ids.indexOf("small3b"))
    }

    @Test
    fun largeRamDeviceCanSeeBigModels() {
        val result = RecommendationEngine.recommend(candidates, 64.0f, topN = 20)
        val ids = result.map { it.model.id }
        assertTrue(ids.contains("big70b"))
        assertTrue(ids.contains("mid13b"))
        assertTrue(ids.contains("small3b"))
    }

    @Test
    fun recommendationsCarryReasons() {
        val result = RecommendationEngine.recommend(candidates, 8.0f, topN = 1)
        val rec = result.first()
        assertTrue(rec.reasons.isNotEmpty())
        assertTrue(rec.reasons.any { it.contains("GB") || it.contains("quantization") || it.contains("parameters") })
    }

    @Test
    fun emptyCatalogYieldsEmpty() {
        assertTrue(RecommendationEngine.recommend(emptyList(), 8.0f).isEmpty())
    }

    @Test
    fun respectsTopN() {
        assertEquals(2, RecommendationEngine.recommend(candidates, 8.0f, topN = 2).size)
    }

    @Test
    fun prefersComfortableFitOverTightFitOnEqualPopularity() {
        val tight = model("tight4b", gb(2.9), 8.0f, 9.0f, "Q4_K_M", parameters = "3.9B")
        val comfy = model("comfy3b", gb(1.8), 2.0f, 4.0f, "Q4_K_M", parameters = "3B")
        val list = listOf(tight, comfy)
        val result = RecommendationEngine.recommend(list, 8.0f, topN = 2)
        assertEquals("comfy3b", result.first().model.id)
    }
}
