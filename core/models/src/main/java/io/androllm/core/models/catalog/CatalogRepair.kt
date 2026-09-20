package io.androllm.core.models.catalog

/**
 * Automatic catalog repair (req §1).
 *
 * The validator rejects invalid entries; this pass runs FIRST and fixes what
 * can be derived safely so fewer entries are rejected:
 * - fileFormat / runtimeFormat derived from the file extension
 * - mimeType derived from the format
 * - version defaulted when blank
 * - containerType defaulted to the generic fallback for .litertlm
 * - supportedBackends normalized: legacy "GPU" → "VULKAN" (the LiteRT GPU
 *   delegate runs on Vulkan — "GPU" parses to nothing and silently drops GPU
 *   acceleration for every model that declares it)
 * - supportsVulkan mirrored from supportsGpu (same reason)
 *
 * Every fix is reported in [RepairResult.notes] — repair is observable, never
 * silent. Anything that cannot be derived (bad URL, unknown family, missing
 * name) is left for [CatalogValidator] to reject.
 */
object CatalogRepair {

    data class RepairResult(
        val models: List<CatalogModel>,
        /** Human-readable per-model fix notes, e.g. "id: backends [CPU, GPU] → [CPU, VULKAN]". */
        val notes: List<String> = emptyList(),
    )

    fun repairAll(models: List<CatalogModel>): RepairResult {
        val notes = mutableListOf<String>()
        return RepairResult(models.map { repairOne(it, notes) }, notes)
    }

    private fun repairOne(model: CatalogModel, notes: MutableList<String>): CatalogModel {
        var out = model
        val lower = model.fileName.lowercase()
        val extFormat = when {
            lower.endsWith(".litertlm") -> "LITERTLM"
            lower.endsWith(".tflite") -> "TFLITE"
            else -> null
        }

        if (out.fileFormat.isBlank() && extFormat != null) {
            out = out.copy(fileFormat = extFormat)
            notes += "${model.id}: fileFormat derived from extension → $extFormat"
        }
        if (out.runtimeFormat.isBlank() && extFormat != null) {
            out = out.copy(runtimeFormat = extFormat)
            notes += "${model.id}: runtimeFormat derived from extension → $extFormat"
        } else if (out.runtimeFormat.equals("GGUF", ignoreCase = true) && extFormat != null) {
            out = out.copy(runtimeFormat = extFormat)
            notes += "${model.id}: stale runtimeFormat GGUF → $extFormat"
        }
        val formatUpper = out.fileFormat.uppercase()
        if (out.mimeType.isBlank()) {
            val mime = ModelMetadataRegistry.mimeTypeFor(formatUpper)
            if (mime != null) {
                out = out.copy(mimeType = mime)
                notes += "${model.id}: mimeType derived → $mime"
            }
        }
        if (out.version.isBlank()) {
            out = out.copy(version = "1.0.0")
            notes += "${model.id}: version defaulted → 1.0.0"
        }
        if (formatUpper == "LITERTLM" && out.containerType.isNullOrBlank()) {
            out = out.copy(containerType = "generic_model")
            notes += "${model.id}: containerType defaulted → generic_model"
        }

        val normalized = out.supportedBackends.map { backend ->
            when (backend.trim().uppercase()) {
                "GPU" -> "VULKAN"
                "VULKAN GPU" -> "VULKAN"
                else -> backend.trim().uppercase()
            }
        }.distinct()
        if (normalized != out.supportedBackends) {
            out = out.copy(supportedBackends = normalized)
            notes += "${model.id}: supportedBackends ${model.supportedBackends} → $normalized"
        }
        if (out.supportsGpu && !out.supportsVulkan) {
            out = out.copy(supportsVulkan = true)
            notes += "${model.id}: supportsVulkan mirrored from supportsGpu"
        }
        return out
    }
}
