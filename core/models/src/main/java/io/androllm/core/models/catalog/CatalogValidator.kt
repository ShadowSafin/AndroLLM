package io.androllm.core.models.catalog

/**
 * Validates catalog metadata without touching the network. Rules:
 * - ids must be unique
 * - required text fields must be non-blank
 * - family / architecture / runtimeFormat must be consistent with the
 *   [ModelMetadataRegistry] (registry-driven — no hardcoded family lists)
 * - fileFormat / mimeType / containerType must be present and consistent
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
            requireBlank(model.version, "version")
            requireBlank(model.fileFormat, "fileFormat")
            requireBlank(model.mimeType, "mimeType")

            // ── registry-driven family / architecture / format consistency ──
            val familySpec = ModelMetadataRegistry.familyFor(model.family)
            if (familySpec == null) {
                errors += "$id: family '${model.family}' is not in the model metadata registry"
            } else {
                if (model.architecture.isNotBlank() && model.architecture !in familySpec.architectures) {
                    errors += "$id: architecture '${model.architecture}' does not belong to family '${model.family}' " +
                        "(registry allows: ${familySpec.architectures.sorted().joinToString(", ")})"
                }
                val format = when (model.runtimeFormat.uppercase()) {
                    "LITERTLM" -> ModelMetadataRegistry.ContainerFormat.LITERTLM
                    "TFLITE" -> ModelMetadataRegistry.ContainerFormat.TFLITE
                    else -> null
                }
                if (format == null) {
                    errors += "$id: runtimeFormat '${model.runtimeFormat}' is not a known container format " +
                        "(LITERTLM / TFLITE)"
                } else if (format !in familySpec.containerFormats) {
                    errors += "$id: family '${model.family}' does not support format ${model.runtimeFormat} " +
                        "(registry allows: ${familySpec.containerFormats.joinToString(", ")})"
                }
            }

            // ── file format / MIME type / extension consistency ──
            val upperFormat = model.fileFormat.uppercase()
            if (model.fileFormat.isNotBlank()) {
                if (upperFormat != "LITERTLM" && upperFormat != "TFLITE") {
                    errors += "$id: fileFormat '${model.fileFormat}' must be LITERTLM or TFLITE"
                } else {
                    if (!model.runtimeFormat.equals(upperFormat, ignoreCase = true)) {
                        errors += "$id: fileFormat $upperFormat does not match runtimeFormat '${model.runtimeFormat}'"
                    }
                    val expectedMime = ModelMetadataRegistry.mimeTypeFor(upperFormat)
                    if (expectedMime == null || !model.mimeType.equals(expectedMime, ignoreCase = true)) {
                        errors += "$id: mimeType '${model.mimeType}' does not match fileFormat $upperFormat " +
                            "(expected $expectedMime)"
                    }
                    val extension = if (upperFormat == "LITERTLM") ".litertlm" else ".tflite"
                    if (!model.fileName.lowercase().endsWith(extension)) {
                        errors += "$id: fileName '${model.fileName}' does not match fileFormat $upperFormat"
                    }
                }
            }

            // ── container type: required + registered for .litertlm, absent for .tflite ──
            if (upperFormat == "LITERTLM" && model.fileName.lowercase().endsWith(".litertlm")) {
                if (model.containerType.isNullOrBlank()) {
                    errors += "$id: containerType is required for .litertlm entries"
                } else if (!ModelMetadataRegistry.isKnownContainerType(model.containerType)) {
                    errors += "$id: containerType '${model.containerType}' is not a registered LlmModelType " +
                        "(registered: ${ModelMetadataRegistry.allContainerTypes.sorted().joinToString(", ")})"
                }
            }
            if (upperFormat == "TFLITE" && !model.containerType.isNullOrBlank()) {
                errors += "$id: containerType must be empty for .tflite entries (they carry no LlmModelType proto)"
            }

            // LiteRT-only runtime: the app runs .litertlm containers (chat)
            // and .tflite flatbuffers (embeddings). A catalog entry pointing
            // at a GGUF file (the pre-migration schema) cannot be loaded —
            // rejecting it here makes a stale remote catalog fail validation
            // so the bundled LiteRT catalog is kept instead of being replaced
            // by an old 101-model GGUF list.
            if (model.fileName.isNotBlank() && !isLiteRtFileName(model.fileName)) {
                errors += "$id: '${model.fileName}' is not a LiteRT artifact (.litertlm / .tflite) — GGUF models are not supported by this runtime"
            }

            // The LiteRT-LM chat Engine only loads .litertlm containers; a raw
            // .tflite chat entry fails native init with "Unsupported file
            // format". .tflite is legal only for non-chat pipelines
            // (embedding / speech).
            if ("CHAT" in model.categories && model.fileName.endsWith(".tflite", ignoreCase = true)) {
                errors += "$id: chat models must be .litertlm containers — .tflite is only supported for embedding/speech pipelines"
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

    private fun isLiteRtFileName(fileName: String): Boolean {
        val lower = fileName.lowercase()
        return lower.endsWith(".litertlm") || lower.endsWith(".tflite")
    }
}