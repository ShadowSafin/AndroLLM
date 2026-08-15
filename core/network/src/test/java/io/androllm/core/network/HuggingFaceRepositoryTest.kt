package io.androllm.core.network

import io.androllm.core.common.getOrNull
import io.androllm.core.models.RepositoryFilter
import io.androllm.core.network.api.HfModelDto
import io.androllm.core.network.api.HfSiblingDto
import io.androllm.core.network.api.HuggingFaceApi
import io.androllm.core.network.repository.HuggingFaceRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HuggingFaceRepositoryTest {

    private val api: HuggingFaceApi = mockk()
    private val repository = HuggingFaceRepository(api)

    @Test
    fun `searchModels parses DTOs into RemoteModelSummary`() = runTest {
        val dtos = listOf(
            HfModelDto(
                id = "litert-community/Qwen3-0.6B",
                author = "litert-community",
                downloads = 50000,
                likes = 1200,
                tags = listOf("litert-lm", "litertlm", "qwen"),
                lastModified = "2026-05-10"
            ),
            // A GGUF-only repo must be dropped — the LiteRT runtime cannot
            // load it and the download would fail header validation.
            HfModelDto(
                id = "google/gemma-3-1b-it",
                author = "google",
                downloads = 50000,
                likes = 1200,
                tags = listOf("gguf", "gemma"),
                lastModified = "2026-05-10"
            )
        )
        coEvery { api.searchModels(any(), any(), any()) } returns dtos

        val result = repository.searchModels(RepositoryFilter(searchQuery = "gemma")).first()
        val list = result.getOrNull()

        assertNotNull(list)
        // Only the LiteRT-tagged repo survives the artifact filter.
        assertEquals(1, list?.size)
        val model = list?.first()
        assertEquals("litert-community/Qwen3-0.6B", model?.id)
        assertEquals("Qwen3-0.6B", model?.name)
        assertEquals("litert-community", model?.author)
        assertEquals("Qwen", model?.family)
    }

    @Test
    fun `getModelDetails extracts GGUF files and quantization`() = runTest {
        val dto = HfModelDto(
            id = "TheBloke/TinyLlama-1.1B-Chat-v1.0-GGUF",
            author = "TheBloke",
            downloads = 100000,
            likes = 3400,
            tags = listOf("license:apache-2.0"),
            siblings = listOf(
                HfSiblingDto("tinyllama-1.1b-chat-v1.0.Q4_K_M.gguf"),
                HfSiblingDto("tinyllama-1.1b-chat-v1.0.Q8_0.gguf"),
                HfSiblingDto("README.md")
            )
        )
        coEvery { api.getModelDetails("TheBloke/TinyLlama-1.1B-Chat-v1.0-GGUF") } returns dto

        val result = repository.getModelDetails("TheBloke/TinyLlama-1.1B-Chat-v1.0-GGUF").first()
        val details = result.getOrNull()

        assertNotNull(details)
        assertEquals(2, details?.ggufFiles?.size)
        val q4File = details?.ggufFiles?.find { it.quantization == "Q4_K_M" }
        assertNotNull(q4File)
        assertTrue(q4File?.downloadUrl?.contains("tinyllama-1.1b-chat-v1.0.Q4_K_M.gguf") == true)
    }

    @Test
    fun `getReadme returns raw README text`() = runTest {
        coEvery { api.getReadmeText("TheBloke/TinyLlama-1.1B") } returns "# TinyLlama Model Card"

        val result = repository.getReadme("TheBloke/TinyLlama-1.1B").first()
        assertEquals("# TinyLlama Model Card", result.getOrNull())
    }
}
