package io.androllm.core.models.catalog

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogAssetTest {

    @Test
    fun bundledCatalogParsesAndValidates() {
        val file = File("src/main/assets/catalog_v1.json")
        assertTrue("asset not found: ${file.absolutePath}", file.exists())
        val result = CatalogParser.parse(file.readText())
        assertTrue("parse warnings: ${result.warnings}", result.warnings.isEmpty())
        val report = CatalogValidator.validate(result.catalog.models)
        assertTrue(
            "validation errors:\n${report.errors.joinToString("\n")}\nwarnings:\n${report.warnings.joinToString("\n")}",
            report.errors.isEmpty()
        )
        assertEquals(1, result.catalog.schemaVersion)
        // LiteRT catalog: 7 curated containers (6 chat .litertlm + 1 embedding .tflite).
        assertTrue("catalog unexpectedly small: ${result.catalog.models.size}", result.catalog.models.size >= 7)
        // Every chat entry must be a .litertlm container; the embedding entry a .tflite flatbuffer.
        val nonLiteRt = result.catalog.models.filter {
            !it.fileName.endsWith(".litertlm", ignoreCase = true) &&
                !it.fileName.endsWith(".tflite", ignoreCase = true)
        }
        assertTrue(
            "entries with non-LiteRT artifacts: ${nonLiteRt.map { it.id }}",
            nonLiteRt.isEmpty()
        )
        val ids = result.catalog.models.map { it.id }.toSet()
        assertEquals(ids.size, result.catalog.models.size)
    }
}
