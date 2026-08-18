package io.androllm.feature.models.downloader

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.androllm.core.database.AppDatabase
import io.androllm.core.models.DownloadStatus
import io.androllm.engine.utils.LiteRtValidator
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import timber.log.Timber

/**
 * WorkManager worker executing background model download with Android notifications,
 * HTTP redirect handling, byte-range resume, real-time progress, GGUF validation,
 * and database status synchronization.
 */
class ModelDownloadWorker(
    private val context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val KEY_MODEL_ID = "model_id"
        const val KEY_MODEL_NAME = "model_name"
        const val KEY_DOWNLOAD_URL = "download_url"
        const val KEY_TARGET_PATH = "target_path"
        const val KEY_EXPECTED_SHA256 = "expected_sha256"
        const val KEY_COMPANION_URL = "companion_url"

        const val KEY_PROGRESS_PERCENT = "progress_percent"
        const val KEY_BYTES_DOWNLOADED = "bytes_downloaded"
        const val KEY_TOTAL_BYTES = "total_bytes"
        const val KEY_SPEED_BYTES_PER_SEC = "speed_bytes_per_sec"
        const val KEY_ETA_SECONDS = "eta_seconds"
        const val KEY_ERROR_MESSAGE = "error_message"

        private const val CHANNEL_ID = "download_channel"
        private const val NOTIFICATION_ID = 4001
        private const val SPEED_SMOOTHING_FACTOR = 0.3f
        private const val MIN_SPEED_UPDATE_MS = 500L
    }

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    override suspend fun doWork(): Result {
        val modelId = inputData.getString(KEY_MODEL_ID) ?: return Result.failure()
        val modelName = inputData.getString(KEY_MODEL_NAME) ?: "Model"
        val downloadUrl = inputData.getString(KEY_DOWNLOAD_URL) ?: return Result.failure()
        val targetPath = inputData.getString(KEY_TARGET_PATH) ?: return Result.failure()
        val expectedSha256 = inputData.getString(KEY_EXPECTED_SHA256)
        val companionUrl = inputData.getString(KEY_COMPANION_URL).orEmpty()

        createNotificationChannel()

        val targetFile = File(targetPath)
        targetFile.parentFile?.mkdirs()

        var downloadedBytes = if (targetFile.exists()) targetFile.length() else 0L

        return try {
            val connection = openConnectionWithRedirects(downloadUrl, downloadedBytes)

            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK && responseCode != HttpURLConnection.HTTP_PARTIAL) {
                markDatabaseFailed(modelId)
                showFailureNotification(modelName, "HTTP Error $responseCode")
                return Result.failure(workDataOf(KEY_ERROR_MESSAGE to "HTTP Error $responseCode"))
            }

            val contentLength = connection.contentLengthLong
            val totalBytes = if (responseCode == HttpURLConnection.HTTP_PARTIAL) contentLength + downloadedBytes else contentLength

            val appendMode = responseCode == HttpURLConnection.HTTP_PARTIAL
            val outputStream = FileOutputStream(targetFile, appendMode)

            val inputStream = connection.inputStream
            val buffer = ByteArray(64 * 1024)
            var bytesRead: Int
            val startTime = System.currentTimeMillis()
            var lastProgressTime = startTime
            var lastReportedBytes = downloadedBytes
            var smoothedSpeedBytesPerSec = 0f

            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                if (isStopped) {
                    outputStream.close()
                    inputStream.close()
                    markDatabasePaused(modelId)
                    return Result.failure(workDataOf(KEY_ERROR_MESSAGE to "Download paused"))
                }

                outputStream.write(buffer, 0, bytesRead)
                downloadedBytes += bytesRead

                val currentTime = System.currentTimeMillis()
                val elapsedSinceUpdate = currentTime - lastProgressTime
                if (elapsedSinceUpdate >= MIN_SPEED_UPDATE_MS) {
                    // Delta-based speed: bytes downloaded in this interval / time elapsed
                    val deltaBytes = (downloadedBytes - lastReportedBytes).toFloat()
                    val deltaTimeSec = (elapsedSinceUpdate / 1000f).coerceAtLeast(0.001f)
                    val instantSpeedBytesPerSec = deltaBytes / deltaTimeSec

                    // Exponential moving average for smooth display
                    if (smoothedSpeedBytesPerSec == 0f) {
                        smoothedSpeedBytesPerSec = instantSpeedBytesPerSec
                    } else {
                        smoothedSpeedBytesPerSec = SPEED_SMOOTHING_FACTOR * instantSpeedBytesPerSec +
                            (1f - SPEED_SMOOTHING_FACTOR) * smoothedSpeedBytesPerSec
                    }

                    val remainingBytes = totalBytes - downloadedBytes
                    val etaSeconds = if (smoothedSpeedBytesPerSec > 0f) {
                        (remainingBytes / smoothedSpeedBytesPerSec).toLong()
                    } else {
                        0L
                    }
                    val progressPercent = if (totalBytes > 0) ((downloadedBytes * 100) / totalBytes).toInt() else 0

                    val progressData = workDataOf(
                        KEY_PROGRESS_PERCENT to progressPercent,
                        KEY_BYTES_DOWNLOADED to downloadedBytes,
                        KEY_TOTAL_BYTES to totalBytes,
                        KEY_SPEED_BYTES_PER_SEC to smoothedSpeedBytesPerSec,
                        KEY_ETA_SECONDS to etaSeconds
                    )
                    setProgress(progressData)

                    updateNotification(modelName, progressPercent, downloadedBytes, totalBytes, smoothedSpeedBytesPerSec, etaSeconds)
                    lastProgressTime = currentTime
                    lastReportedBytes = downloadedBytes
                }
            }

            outputStream.flush()
            outputStream.close()
            inputStream.close()

            // Optional SHA256 Verification if length == 64
            if (!expectedSha256.isNullOrBlank() && expectedSha256.length == 64) {
                val actualSha256 = LiteRtValidator.calculateSha256(targetFile.absolutePath)
                if (actualSha256 != null && !actualSha256.equals(expectedSha256, ignoreCase = true)) {
                    targetFile.delete()
                    markDatabaseFailed(modelId)
                    showFailureNotification(modelName, "SHA256 checksum mismatch")
                    return Result.failure(workDataOf(KEY_ERROR_MESSAGE to "SHA256 checksum mismatch"))
                }
            }

            // LiteRT Artifact Header Validation (.litertlm container or .tflite)
            val validation = LiteRtValidator.validateHeader(targetFile.absolutePath)
            if (!validation.isValid) {
                targetFile.delete()
                markDatabaseFailed(modelId)
                showFailureNotification(modelName, validation.errorMessage)
                return Result.failure(workDataOf(KEY_ERROR_MESSAGE to validation.errorMessage))
            }

            // Auto-import into Database
            AppDatabase.getInstance(applicationContext).modelDao().updateDownloadState(
                id = modelId,
                isDownloaded = true,
                downloadStatus = DownloadStatus.DOWNLOADED.name,
                filePath = targetFile.absolutePath,
                updatedAt = System.currentTimeMillis()
            )

            // LiteRT artifacts carry their tokenizer/chat template inside the
            // container (or, for .tflite embedding models, next to the file) —
            // there is no GGUF header to enrich the record with, so the model
            // row keeps its catalog metadata as-is.

            // Companion artifact (e.g. the Gemma 3 sentencepiece tokenizer for
            // the EmbeddingGemma .tflite): downloaded next to the main file as
            // `tokenizer.model` so the LiteRT embedding engine finds it.
            if (companionUrl.isNotBlank()) {
                val tokenizerFile = File(targetFile.parentFile, "tokenizer.model")
                downloadCompanion(companionUrl, tokenizerFile, modelName)
            }

            showSuccessNotification(modelName)

            Result.success(
                workDataOf(
                    KEY_TARGET_PATH to targetFile.absolutePath,
                    KEY_PROGRESS_PERCENT to 100
                )
            )
        } catch (e: Exception) {
            Timber.e(e, "Error downloading model $modelName from $downloadUrl")
            markDatabaseFailed(modelId)
            showFailureNotification(modelName, e.message ?: "Network failure")
            Result.retry()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Model Downloads",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows live LiteRT model download progress"
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun updateNotification(
        name: String,
        progress: Int,
        downloadedBytes: Long,
        totalBytes: Long,
        speedBytesPerSec: Float,
        etaSeconds: Long
    ) {
        val downloadedText = "${downloadedBytes.formatSize()} / ${totalBytes.formatSize()}"
        val speedText = speedBytesPerSec.formatSpeed()
        val etaText = if (etaSeconds > 0) " • ${etaSeconds}s remaining" else ""
        val statusText = "$downloadedText • $speedText$etaText"

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("Downloading $name")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(100, progress, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun showSuccessNotification(name: String) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("✓ $name Downloaded")
            .setContentText("Model successfully verified and imported into Installed Models.")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun showFailureNotification(name: String, error: String) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("⚠ Download Failed: $name")
            .setContentText(error)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun openConnectionWithRedirects(initialUrl: String, downloadedBytes: Long): HttpURLConnection {
        var redirectCount = 0
        var currentUrl = initialUrl

        while (redirectCount < 10) {
            val url = URL(currentUrl)
            val connection = url.openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = true
            connection.connectTimeout = 30_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("User-Agent", "AndroLLM/1.0 (Android)")

            if (downloadedBytes > 0) {
                connection.setRequestProperty("Range", "bytes=$downloadedBytes-")
            }

            connection.connect()

            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_MOVED_PERM ||
                responseCode == HttpURLConnection.HTTP_MOVED_TEMP ||
                responseCode == HttpURLConnection.HTTP_SEE_OTHER ||
                responseCode == 307 || responseCode == 308
            ) {
                val newUrl = connection.getHeaderField("Location")
                connection.disconnect()
                if (newUrl.isNullOrBlank()) {
                    throw IllegalStateException("Redirected with empty Location header")
                }
                currentUrl = newUrl
                redirectCount++
            } else {
                return connection
            }
        }
        throw IllegalStateException("Too many HTTP redirects")
    }

    /**
     * Downloads a companion artifact (e.g. the sentencepiece tokenizer) next
     * to a downloaded model. Small enough to be a plain blocking download.
     * Failures here do not fail the model download — the embedding engine
     * reports a clear "tokenizer not found" error if the file is missing.
     */
    private fun downloadCompanion(url: String, target: File, modelName: String) {
        try {
            target.parentFile?.mkdirs()
            if (target.exists() && target.length() > 0) return
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = true
            connection.connectTimeout = 30_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("User-Agent", "AndroLLM/1.0 (Android)")
            connection.connect()
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                Timber.w("Companion download for $modelName failed: HTTP ${connection.responseCode}")
                return
            }
            connection.inputStream.use { input ->
                FileOutputStream(target).use { output ->
                    input.copyTo(output)
                }
            }
            Timber.i("Companion artifact downloaded for $modelName: ${target.name}")
        } catch (e: Exception) {
            Timber.w(e, "Companion download for $modelName failed")
        }
    }

    private suspend fun markDatabaseFailed(modelId: String) {
        runCatching {
            AppDatabase.getInstance(applicationContext).modelDao().updateDownloadState(
                id = modelId,
                isDownloaded = false,
                downloadStatus = DownloadStatus.ERROR.name,
                filePath = null,
                updatedAt = System.currentTimeMillis()
            )
        }
    }

    private suspend fun markDatabasePaused(modelId: String) {
        runCatching {
            AppDatabase.getInstance(applicationContext).modelDao().updateDownloadState(
                id = modelId,
                isDownloaded = false,
                downloadStatus = DownloadStatus.PAUSED.name,
                filePath = null,
                updatedAt = System.currentTimeMillis()
            )
        }
    }

    private fun Long.formatSize(): String {
        return when {
            this < 1024 -> "$this B"
            this < 1024 * 1024 -> String.format(java.util.Locale.getDefault(), "%.1f KB", this / 1024.0)
            this < 1024 * 1024 * 1024 -> String.format(java.util.Locale.getDefault(), "%.1f MB", this / (1024.0 * 1024.0))
            else -> String.format(java.util.Locale.getDefault(), "%.1f GB", this / (1024.0 * 1024.0 * 1024.0))
        }
    }

    /**
     * Formats bytes/sec into a human-readable speed string using binary units.
     *
     * Conversion rules:
     *   < 1024 B     -> "512 B/s"
     *   < 1024 KB    -> "845 KB/s"
     *   < 1024 MB    -> "9.82 MB/s"
     *   >= 1024 MB   -> "1.50 GB/s"
     */
    private fun Float.formatSpeed(): String {
        return when {
            this < 1024f -> String.format(java.util.Locale.getDefault(), "%.0f B/s", this)
            this < 1024f * 1024f -> String.format(java.util.Locale.getDefault(), "%.0f KB/s", this / 1024f)
            this < 1024f * 1024f * 1024f -> String.format(java.util.Locale.getDefault(), "%.2f MB/s", this / (1024f * 1024f))
            else -> String.format(java.util.Locale.getDefault(), "%.2f GB/s", this / (1024f * 1024f * 1024f))
        }
    }
}
