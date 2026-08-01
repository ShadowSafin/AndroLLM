package io.androllm.core.network

import android.content.Context
import io.androllm.core.common.AppConstants
import io.androllm.core.common.Result
import io.androllm.core.common.runCatching
import io.androllm.core.models.DownloadStatus
import io.androllm.core.models.Model
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Manages model downloads with progress tracking.
 * Prepared for Phase 2 - currently a stub.
 */
@Singleton
class DownloadManager @Inject constructor(
    private val context: Context,
    private val modelApi: ModelApi
) {

    private val _downloadProgress = MutableStateFlow<Map<String, Float>>(emptyMap())

    /**
     * Current download progress per model ID (0f..1f).
     */
    val downloadProgress: Flow<Map<String, Float>> = _downloadProgress

    /**
     * Downloads a model to the app's models directory.
     */
    suspend fun downloadModel(model: Model): Result<Long> {
        val targetDir = java.io.File(context.getExternalFilesDir(null), "models").apply { mkdirs() }
        val targetFile = java.io.File(targetDir, "${model.id}.gguf")
        return modelApi.downloadModel(model.id, targetFile)
    }

    /**
     * Returns the status of a download.
     */
    fun getDownloadStatus(modelId: String): DownloadStatus =
        if (_downloadProgress.value.containsKey(modelId)) DownloadStatus.DOWNLOADING
        else DownloadStatus.NOT_DOWNLOADED
}
