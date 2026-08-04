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
fun CatalogModel.toDownloadModel(): Model = Model(
    id = id,
    name = name,
    description = description,
    filePath = null,
    fileSize = sizeBytes,
    format = ModelFormat.GGUF,
    parameters = parameters,
    quantization = quantization,
    contextLength = contextLength,
    downloadUrl = downloadUrl,
    isDownloaded = false,
    downloadStatus = DownloadStatus.NOT_DOWNLOADED,
    status = ModelStatus.NOT_LOADED,
    sha256 = sha256,
    architecture = architecture,
    family = family,
    minRamGb = minRamGb,
    recommendedRamGb = recommendedRamGb,
    license = license
)
