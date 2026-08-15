package io.androllm.feature.models.catalog

import io.androllm.core.models.DownloadStatus
import io.androllm.core.models.Model
import io.androllm.core.models.ModelFormat
import io.androllm.core.models.ModelStatus
import io.androllm.core.models.catalog.CatalogModel

/**
 * Converts a metadata-driven [CatalogModel] into the domain [Model] used by the
 * database and the download/load pipeline. Downloaded models keep the catalog id,
 * so the catalog UI can reflect their download state via the installed models list.
 */
/**
 * Maps the catalog's `runtimeFormat`/file extension to the domain [ModelFormat].
 * The catalog is LiteRT-only: `.litertlm` containers for chat, `.tflite`
 * flatbuffers for embeddings. Anything else (legacy GGUF entries) maps to
 * [ModelFormat.GGUF] so the UI can still show the download state, but such
 * files are rejected at load time by the LiteRT artifact validator.
 */
private fun CatalogModel.domainFormat(): ModelFormat = when {
    runtimeFormat.equals("litertlm", ignoreCase = true) ||
        fileName.endsWith(".litertlm", ignoreCase = true) -> ModelFormat.LITERTLM

    runtimeFormat.equals("tflite", ignoreCase = true) ||
        fileName.endsWith(".tflite", ignoreCase = true) -> ModelFormat.TFLITE

    else -> ModelFormat.GGUF
}

fun CatalogModel.toDownloadModel(): Model = Model(
    id = id,
    name = name,
    description = description,
    filePath = null,
    fileSize = sizeBytes,
    format = domainFormat(),
    parameters = parameters,
    quantization = quantization,
    contextLength = contextLength,
    downloadUrl = downloadUrl,
    isDownloaded = false,
    downloadStatus = DownloadStatus.NOT_DOWNLOADED,
    status = ModelStatus.NOT_LOADED,
    sha256 = sha256,
    companionUrl = companionUrl.takeIf { it.isNotBlank() },
    architecture = architecture,
    family = family,
    minRamGb = minRamGb,
    recommendedRamGb = recommendedRamGb,
    license = license,
    stopSequences = stopSequences
)
