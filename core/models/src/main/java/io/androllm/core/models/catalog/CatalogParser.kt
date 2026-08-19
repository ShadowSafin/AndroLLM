package io.androllm.core.models.catalog

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Parses a catalog JSON document into [CatalogFile].
 * Unknown enum values in categories/modality/status are skipped with a warning
 * instead of failing, so the parser stays forward-compatible with newer catalogs.
 */
object CatalogParser {

    private const val CURRENT_SCHEMA_VERSION = 2

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    data class ParseResult(
        val catalog: CatalogFile,
        val warnings: List<String>
    )

    fun parse(jsonText: String): ParseResult {
        val catalog = try {
            json.decodeFromString(CatalogFile.serializer(), jsonText)
        } catch (e: SerializationException) {
            throw IllegalArgumentException("Invalid catalog JSON: ${e.message}", e)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid catalog JSON: ${e.message}", e)
        }

        if (catalog.schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw IllegalArgumentException(
                "Unsupported catalog schema version ${catalog.schemaVersion}, expected $CURRENT_SCHEMA_VERSION"
            )
        }

        val warnings = mutableListOf<String>()
        catalog.models.forEach { model ->
            if (model.id.isBlank()) warnings += "Model at unknown index has a blank id"
            model.categories.filter { CatalogCategory.fromValue(it) == null }
                .forEach { warnings += "${model.id}: unknown category '$it' skipped" }
            if (Modality.fromValue(model.modality) == Modality.TEXT && model.modality != "TEXT") {
                warnings += "${model.id}: unknown modality '${model.modality}', defaulted to TEXT"
            }
            if (CatalogStatus.fromValue(model.status) == CatalogStatus.STABLE && model.status != "STABLE") {
                warnings += "${model.id}: unknown status '${model.status}', defaulted to STABLE"
            }
        }
        return ParseResult(catalog, warnings)
    }
}
