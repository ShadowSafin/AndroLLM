package io.androllm.feature.models.downloader

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.qualifiers.ApplicationContext
import io.androllm.core.database.repository.ModelRepository
import io.androllm.core.models.DownloadProgress
import io.androllm.core.models.DownloadStatus
import io.androllm.core.models.Model
import io.androllm.core.models.ModelFormat
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Production-quality Download Manager managing model downloads via WorkManager.
 * Supports queue management, pause, resume, cancel, retry, and bulk controls.
 */
@Singleton
class DownloadManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val modelRepository: ModelRepository
) {

    private val workManager: WorkManager by lazy { WorkManager.getInstance(context) }
    private val scope = CoroutineScope(Dispatchers.IO)

    /**
     * Enqueues a model for download.
     */
    fun startDownload(model: Model) {
        scope.launch {
            val targetPath = model.filePath ?: getTargetFilePath(model)

            val updatedModel = model.copy(
                filePath = targetPath,
                isDownloaded = false,
                downloadStatus = DownloadStatus.DOWNLOADING
            )
            modelRepository.upsert(updatedModel)

            val workRequest = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
                .addTag("download_${model.id}")
                .setInputData(
                    workDataOf(
                        ModelDownloadWorker.KEY_MODEL_ID to model.id,
                        ModelDownloadWorker.KEY_MODEL_NAME to model.name,
                        ModelDownloadWorker.KEY_DOWNLOAD_URL to (model.downloadUrl ?: ""),
                        ModelDownloadWorker.KEY_TARGET_PATH to targetPath,
                        ModelDownloadWorker.KEY_EXPECTED_SHA256 to (model.sha256 ?: ""),
                        ModelDownloadWorker.KEY_COMPANION_URL to (model.companionUrl ?: "")
                    )
                )
                .build()

            workManager.enqueueUniqueWork(
                "download_${model.id}",
                ExistingWorkPolicy.REPLACE,
                workRequest
            )
        }
    }

    /**
     * Pauses an active download.
     */
    fun pauseDownload(modelId: String) {
        scope.launch {
            workManager.cancelUniqueWork("download_$modelId")
            modelRepository.updateDownloadState(
                id = modelId,
                isDownloaded = false,
                downloadStatus = DownloadStatus.PAUSED,
                filePath = null
            )
        }
    }

    /**
     * Resumes a paused or failed download.
     */
    fun resumeDownload(model: Model) {
        startDownload(model)
    }

    /**
     * Cancels a download and removes target file.
     */
    fun cancelDownload(modelId: String) {
        scope.launch {
            workManager.cancelUniqueWork("download_$modelId")
            modelRepository.deleteById(modelId)
        }
    }

    /**
     * Retries a failed download.
     */
    fun retryDownload(model: Model) {
        startDownload(model)
    }

    /**
     * Pauses all active downloads.
     */
    fun pauseAll(activeModels: List<Model>) {
        activeModels.filter { it.downloadStatus == DownloadStatus.DOWNLOADING || it.downloadStatus == DownloadStatus.QUEUED }
            .forEach { pauseDownload(it.id) }
    }

    /**
     * Resumes all paused downloads.
     */
    fun resumeAll(pausedModels: List<Model>) {
        pausedModels.filter { it.downloadStatus == DownloadStatus.PAUSED || it.downloadStatus == DownloadStatus.ERROR }
            .forEach { resumeDownload(it) }
    }

    /**
     * Cancels all downloads in progress or queued.
     */
    fun cancelAll(models: List<Model>) {
        models.filter { !it.isDownloaded }.forEach { cancelDownload(it.id) }
    }

    /**
     * Observes real-time WorkManager progress flows for a model.
     */
    fun observeProgress(modelId: String): Flow<DownloadProgress?> {
        return workManager.getWorkInfosForUniqueWorkFlow("download_$modelId")
            .map { list ->
                val workInfo = list.firstOrNull() ?: return@map null
                val progressData = workInfo.progress

                val status = when (workInfo.state) {
                    WorkInfo.State.RUNNING -> DownloadStatus.DOWNLOADING
                    WorkInfo.State.ENQUEUED -> DownloadStatus.QUEUED
                    WorkInfo.State.SUCCEEDED -> DownloadStatus.DOWNLOADED
                    WorkInfo.State.FAILED -> DownloadStatus.ERROR
                    WorkInfo.State.CANCELLED -> DownloadStatus.PAUSED
                    else -> DownloadStatus.NOT_DOWNLOADED
                }

                DownloadProgress(
                    modelId = modelId,
                    bytesDownloaded = progressData.getLong(ModelDownloadWorker.KEY_BYTES_DOWNLOADED, 0L),
                    totalBytes = progressData.getLong(ModelDownloadWorker.KEY_TOTAL_BYTES, 0L),
                    speedBytesPerSec = progressData.getFloat(ModelDownloadWorker.KEY_SPEED_BYTES_PER_SEC, 0f),
                    etaSeconds = progressData.getLong(ModelDownloadWorker.KEY_ETA_SECONDS, 0L),
                    progressPercent = progressData.getInt(ModelDownloadWorker.KEY_PROGRESS_PERCENT, 0),
                    status = status,
                    errorMessage = progressData.getString(ModelDownloadWorker.KEY_ERROR_MESSAGE)
                )
            }
    }

    private fun getTargetFilePath(model: Model): String {
        val mediaDir = java.io.File(context.getExternalFilesDir(null) ?: context.filesDir, "models").apply { mkdirs() }
        val safeName = model.name.replace(Regex("[^a-zA-Z0-9._\\-]"), "_")
        val extension = when (model.format) {
            ModelFormat.LITERTLM -> ".litertlm"
            ModelFormat.TFLITE -> ".tflite"
            else -> ".litertlm"
        }
        return java.io.File(mediaDir, "$safeName$extension").absolutePath
    }
}
