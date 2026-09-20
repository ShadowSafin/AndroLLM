package io.androllm.feature.models.downloader

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
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
import io.androllm.core.models.catalog.CatalogModel
import io.androllm.core.models.download.ModelCompatibility
import io.androllm.feature.models.catalog.toDownloadModel
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import timber.log.Timber

/** Scheduling priority for queued downloads (higher starts first). */
enum class DownloadPriority(val value: Int) {
    LOW(0),
    NORMAL(10),
    HIGH(20),
}

/**
 * Production-quality Download Manager managing model downloads via WorkManager.
 *
 * - Queue: one unique work chain per model (parallel across models, serial per
 *   model), priorities via expedited/high-priority tagging, bulk pause/resume.
 * - Pre-download gate: [ModelCompatibility] rejects unrunnable models BEFORE
 *   WorkManager ever runs — no model downloads unless it passed validation.
 * - Resilience: requires connectivity (network changes pause automatically via
 *   constraints), exponential backoff retries transient failures, keeps partial
 *   files for Range resume across reboot / app restart / pause.
 */
@Singleton
class DownloadManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val modelRepository: ModelRepository
) {

    private val workManager: WorkManager by lazy { WorkManager.getInstance(context) }
    private val scope = CoroutineScope(Dispatchers.IO)

    private val downloadConstraints: Constraints
        get() = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresStorageNotLow(true)
            .build()

    /**
     * Enqueues a model for download. Rejects models whose catalog metadata is
     * unusable (missing/malformed download URL, incompatible container) before
     * WorkManager ever runs.
     */
    fun startDownload(model: Model, priority: DownloadPriority = DownloadPriority.NORMAL) {
        scope.launch {
            val downloadUrl = model.downloadUrl ?: ""
            if (!isValidDownloadUrl(downloadUrl)) {
                Timber.e("Blocking download of ${model.name}: invalid URL '$downloadUrl'")
                modelRepository.updateDownloadState(
                    id = model.id,
                    isDownloaded = false,
                    downloadStatus = DownloadStatus.ERROR,
                    filePath = null
                )
                return@launch
            }

            val targetPath = model.filePath ?: getTargetFilePath(model)

            val updatedModel = model.copy(
                filePath = targetPath,
                isDownloaded = false,
                downloadStatus = DownloadStatus.DOWNLOADING
            )
            modelRepository.upsert(updatedModel)

            enqueueWorker(
                modelId = model.id,
                modelName = model.name,
                downloadUrl = downloadUrl,
                targetPath = targetPath,
                sha256 = model.sha256 ?: "",
                expectedSize = model.fileSize,
                companionUrl = model.companionUrl ?: "",
                priority = priority,
                policy = ExistingWorkPolicy.REPLACE,
            )
        }
    }

    /**
     * Enqueues a [CatalogModel] after the compatibility gate. Models that can
     * never run on this runtime are marked ERROR with the reason — they are
     * never downloadable unless they pass every validation stage.
     */
    fun startCatalogDownload(catalogModel: CatalogModel, priority: DownloadPriority = DownloadPriority.NORMAL) {
        val gate = ModelCompatibility.check(catalogModel)
        if (!gate.compatible) {
            Timber.e("Blocking download of ${catalogModel.name}: ${gate.reason}")
            scope.launch {
                modelRepository.updateDownloadState(
                    id = catalogModel.id,
                    isDownloaded = false,
                    downloadStatus = DownloadStatus.ERROR,
                    filePath = null
                )
            }
            return
        }
        startDownload(catalogModel.toDownloadModel(), priority)
    }

    private fun enqueueWorker(
        modelId: String,
        modelName: String,
        downloadUrl: String,
        targetPath: String,
        sha256: String,
        expectedSize: Long,
        companionUrl: String,
        priority: DownloadPriority,
        policy: ExistingWorkPolicy,
    ) {
        val builder = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .addTag("download_$modelId")
            .addTag("priority_${priority.value}")
            .setConstraints(downloadConstraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setInputData(
                workDataOf(
                    ModelDownloadWorker.KEY_MODEL_ID to modelId,
                    ModelDownloadWorker.KEY_MODEL_NAME to modelName,
                    ModelDownloadWorker.KEY_DOWNLOAD_URL to downloadUrl,
                    ModelDownloadWorker.KEY_TARGET_PATH to targetPath,
                    ModelDownloadWorker.KEY_EXPECTED_SHA256 to sha256,
                    ModelDownloadWorker.KEY_EXPECTED_SIZE to expectedSize,
                    ModelDownloadWorker.KEY_COMPANION_URL to companionUrl
                )
            )
        // High-priority downloads jump the queue via expedited work.
        if (priority == DownloadPriority.HIGH) {
            builder.setExpedited(androidx.work.OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        }
        workManager.enqueueUniqueWork("download_$modelId", policy, builder.build())
        Timber.i("Enqueued download %s (priority=%s, policy=%s)", modelName, priority, policy)
    }

    /**
     * Pauses an active download. The partial file is kept — resume re-validates
     * it and continues with a Range request.
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
     * Resumes a paused or failed download. KEEPS existing work when it is
     * already queued/running so a resume never duplicates the chain.
     */
    fun resumeDownload(model: Model) {
        scope.launch {
            val targetPath = model.filePath ?: getTargetFilePath(model)
            modelRepository.upsert(
                model.copy(
                    filePath = targetPath,
                    isDownloaded = false,
                    downloadStatus = DownloadStatus.DOWNLOADING
                )
            )
            enqueueWorker(
                modelId = model.id,
                modelName = model.name,
                downloadUrl = model.downloadUrl ?: return@launch,
                targetPath = targetPath,
                sha256 = model.sha256 ?: "",
                expectedSize = model.fileSize,
                companionUrl = model.companionUrl ?: "",
                priority = DownloadPriority.NORMAL,
                policy = ExistingWorkPolicy.KEEP,
            )
        }
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
     * Retries a failed download with backoff (transient) — keeps the partial
     * file so the retry resumes instead of restarting.
     */
    fun retryDownload(model: Model, priority: DownloadPriority = DownloadPriority.NORMAL) {
        startDownload(model, priority)
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
     *
     * The worker publishes its error reason in BOTH the progress data and the
     * result output data; prefer progress, fall back to output on FAILED so
     * the UI always shows the real reason instead of a generic message.
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

                val errorMessage = progressData.getString(ModelDownloadWorker.KEY_ERROR_MESSAGE)
                    ?: if (workInfo.state == WorkInfo.State.FAILED) {
                        workInfo.outputData.getString(ModelDownloadWorker.KEY_ERROR_MESSAGE)
                    } else {
                        null
                    }

                DownloadProgress(
                    modelId = modelId,
                    bytesDownloaded = progressData.getLong(ModelDownloadWorker.KEY_BYTES_DOWNLOADED, 0L),
                    totalBytes = progressData.getLong(ModelDownloadWorker.KEY_TOTAL_BYTES, 0L),
                    speedBytesPerSec = progressData.getFloat(ModelDownloadWorker.KEY_SPEED_BYTES_PER_SEC, 0f),
                    etaSeconds = progressData.getLong(ModelDownloadWorker.KEY_ETA_SECONDS, 0L),
                    progressPercent = progressData.getInt(ModelDownloadWorker.KEY_PROGRESS_PERCENT, 0),
                    status = status,
                    errorMessage = errorMessage
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
