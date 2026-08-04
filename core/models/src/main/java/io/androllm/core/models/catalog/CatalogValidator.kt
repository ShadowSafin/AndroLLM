package io.androllm.core.models.catalog

/**
 * Validates catalog metadata without touching the network. Rules:
 * - ids must be unique
 * - required text fields must be non-blank
 * - architecture must be supported by the vendored llama.cpp build
 * - quantization must classify to a known level (unknown -> warning, catalog may ship newer quants)
 * - sha256 must be 64 hex chars when present
 * - downloadUrl must be https
 * - sizes / context / RAM hints must be positive
 */
object CatalogValidator {

    data class ValidationReport(
        val errors: List<String> = emptyList(),
        val warnings: List<String> = emptyList()
    ) {
        val isValid: Boolean get() = errors.isEmpty()
    }

    fun validate(models: List<CatalogModel>): ValidationReport {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        val seenIds = mutableSetOf<String>()
        models.forEach { model ->
            val id = model.id
            if (id in seenIds) errors += "Duplicate model id '$id'"
            seenIds += id

            fun requireBlank(value: String, field: String) {
                if (value.isBlank()) errors += "$id: '$field' is required"
            }

            requireBlank(model.name, "name")
            requireBlank(model.family, "family")
            requireBlank(model.architecture, "architecture")
            requireBlank(model.repoId, "repoId")
            requireBlank(model.fileName, "fileName")
            requireBlank(model.downloadUrl, "downloadUrl")
            requireBlank(model.quantization, "quantization")

            if (model.architecture.isNotBlank() && !SupportedArchitectures.isSupported(model.architecture)) {
                errors += "$id: architecture '${model.architecture}' is not supported by this llama.cpp build"
            }

            if (model.quantization.isNotBlank() && !QuantClassifier.isKnown(model.quantization)) {
                warnings += "$id: quantization '${model.quantization}' does not match a known llama.cpp type"
            }

            val sha = model.sha256
            if (sha != null && !sha.matches(Regex("^[0-9a-fA-F]{64}$"))) {
                errors += "$id: sha256 must be 64 hex characters"
            }

            if (!model.downloadUrl.startsWith("https://")) {
                errors += "$id: downloadUrl must be https"
            }

            if (model.sizeBytes <= 0) warnings += "$id: sizeBytes is missing or non-positive"
            if (model.contextLength < 256) warnings += "$id: contextLength ${model.contextLength} is suspiciously small"
            if (model.minRamGb <= 0f || model.recommendedRamGb <= 0f) {
                warnings += "$id: RAM hints must be positive"
            }
            if (model.parameters.isBlank()) warnings += "$id: parameters missing"
            if (model.license.isBlank()) warnings += "$id: license missing"
            if (model.categories.isEmpty()) warnings += "$id: no categories assigned"
            if (model.downloads < 0 || model.likes < 0) errors += "$id: popularity metrics cannot be negative"
        }
        return ValidationReport(errors, warnings)
    }
}
