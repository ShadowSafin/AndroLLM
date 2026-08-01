package io.androllm.core.network

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for network DTO serialization.
 */
class DtoSerializationTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `model dto serializes and deserializes`() {
        val dto = ModelDto(
            id = "model-1",
            name = "Test",
            fileUrl = "https://example.com/model.gguf",
            fileSize = 1024L,
            format = "gguf",
            parameters = "8B"
        )
        val encoded = json.encodeToString(ModelDto.serializer(), dto)
        val decoded = json.decodeFromString(ModelDto.serializer(), encoded)
        assertEquals(dto, decoded)
    }

    @Test
    fun `catalog dto handles empty lists`() {
        val catalog = ModelCatalogDto()
        assertEquals(0, catalog.total)
        assertEquals(1, catalog.page)
    }

    @Test
    fun `deserialization ignores unknown keys`() {
        val jsonText = """{"id":"m1","name":"M","unknown_field":123}"""
        val decoded = json.decodeFromString(ModelDto.serializer(), jsonText)
        assertEquals("m1", decoded.id)
    }
}
