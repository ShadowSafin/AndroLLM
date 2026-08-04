package io.androllm.core.models.catalog

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CatalogParserTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun sampleModel(overrides: Map<String, String> = emptyMap()) = buildString {
        append(
            """{"id":"test-1","name":"Test Model","family":"Test","architecture":"llama",""" +
                """"categories":["CHAT"],"tags":["fast"],"license":"Apache-2.0","author":"T",""" +
                """"repoId":"t/test-gguf","fileName":"test-q4_k_m.gguf",""" +
                """"downloadUrl":"https://huggingface.co/t/test-gguf/resolve/main/test-q4_k_m.gguf",""" +
                """"sizeBytes":1000000000,"parameters":"1.5B","quantization":"Q4_K_M",""" +
                """"contextLength":8192,"minRamGb":2.0,"recommendedRamGb":4.0,"downloads":100,"likes":5}"""
        )
    }

    @Test
    fun parsesValidCatalog() {
        val text = """{"schemaVersion":1,"models":[${sampleModel()}]}"""
        val result = CatalogParser.parse(text)
        assertEquals(1, result.catalog.models.size)
        assertTrue(result.warnings.isEmpty())
        val model = result.catalog.models.first()
        assertEquals(QuantLevel.Q4, model.quantLevel)
        assertEquals(1.5, model.parameterCountB!!, 0.001)
        assertEquals(listOf(CatalogCategory.CHAT), model.categoryValues)
    }

    @Test
    fun rejectsUnsupportedSchemaVersion() {
        val text = """{"schemaVersion":99,"models":[${sampleModel()}]}"""
        try {
            CatalogParser.parse(text)
            fail("Expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("schema version"))
        }
    }

    @Test
    fun rejectsInvalidJson() {
        try {
            CatalogParser.parse("not json")
            fail("Expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("Invalid catalog JSON"))
        }
    }

    @Test
    fun warnsOnUnknownEnumValuesWithoutFailing() {
        val text = """{"schemaVersion":1,"models":[${sampleModel(mapOf())}]}"""
        val withUnknown = text.replace("\"categories\":[\"CHAT\"]", "\"categories\":[\"CHAT\",\"MADE_UP\"]")
        val result = CatalogParser.parse(withUnknown)
        assertEquals(1, result.catalog.models.size)
        assertTrue(result.warnings.any { it.contains("MADE_UP") })
        assertEquals(listOf(CatalogCategory.CHAT), result.catalog.models.first().categoryValues)
    }

    @Test
    fun defaultsAreAppliedForMissingOptionalFields() {
        val text = """{"schemaVersion":1,"models":[{"id":"m1","name":"M1"}]}"""
        val result = CatalogParser.parse(text)
        val model = result.catalog.models.first()
        assertEquals("", model.architecture)
        assertEquals("Apache-2.0", model.license)
        assertEquals(4096, model.contextLength)
    }

    @Test
    fun serializesAndRoundTrips() {
        val text = """{"schemaVersion":1,"models":[${sampleModel()}]}"""
        val catalog = CatalogParser.parse(text).catalog
        val reEncoded = json.encodeToString(CatalogFile.serializer(), catalog)
        val reparsed = CatalogParser.parse(reEncoded).catalog
        assertEquals(catalog.models.first().id, reparsed.models.first().id)
        assertEquals(catalog.models.first().quantization, reparsed.models.first().quantization)
    }
}
