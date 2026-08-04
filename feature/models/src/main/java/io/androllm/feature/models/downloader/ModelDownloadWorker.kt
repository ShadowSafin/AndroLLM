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
import io.androllm.engine.utils.GgufValidator
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

        const val KEY_PROGRESS_PERCENT = "progress_percent"
        const val KEY_BYTES_DOWNLOADED = "bytes_downloaded"
        const val KEY_TOTAL_BYTES = "total_bytes"
        const val KEY_SPEED_MBPS = "speed_mbps"
        const val KEY_ETA_SECONDS = "eta_seconds"
        const val KEY_ERROR_MESSAGE = "error_message"

        private const val CHANNEL_ID = "download_channel"
        private const val NOTIFICATION_ID = 4001
    }

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    override suspend fun doWork(): Result {
        val modelId = inputData.getString(KEY_MODEL_ID) ?: return Result.failure()
        val modelName = inputData.getString(KEY_MODEL_NAME) ?: "Model"
        val downloadUrl = inputData.getString(KEY_DOWNLOAD_URL) ?: return Result.failure()
        val targetPath = inputData.getString(KEY_TARGET_PATH) ?: return Result.failure()
        val expectedSha256 = inputData.getString(KEY_EXPECTED_SHA256)

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
                if (currentTime - lastProgressTime > 500) {
                    val elapsedTimeSec = ((currentTime - startTime) / 1000f).coerceAtLeast(0.1f)
                    val speedBytesPerSec = downloadedBytes / elapsedTimeSec
                    val speedMbps = (speedBytesPerSec * 8) / (1024f * 1024f)
                    val remainingBytes = totalBytes - downloadedBytes
                    val etaSeconds = if (speedBytesPerSec > 0) (remainingBytes / speedBytesPerSec).toLong() else 0L
                    val progressPercent = if (totalBytes > 0) ((downloadedBytes * 100) / totalBytes).toInt() else 0

                    val progressData = workDataOf(
                        KEY_PROGRESS_PERCENT to progressPercent,
                        KEY_BYTES_DOWNLOADED to downloadedBytes,
                        KEY_TOTAL_BYTES to totalBytes,
                        KEY_SPEED_MBPS to speedMbps,
                        KEY_ETA_SECONDS to etaSeconds
                    )
                    setProgress(progressData)

                    updateNotification(modelName, progressPercent, downloadedBytes, totalBytes, speedMbps, etaSeconds)
                    lastProgressTime = currentTime
                }
            }

            outputStream.flush()
            outputStream.close()
            inputStream.close()

            // Optional SHA256 Verification if length == 64
            if (!expectedSha256.isNullOrBlank() && expectedSha256.length == 64) {
                val actualSha256 = GgufValidator.calculateSha256(targetFile.absolutePath)
                if (actualSha256 != null && !actualSha256.equals(expectedSha256, ignoreCase = true)) {
                    targetFile.delete()
                    markDatabaseFailed(modelId)
                    showFailureNotification(modelName, "SHA256 checksum mismatch")
                    return Result.failure(workDataOf(KEY_ERROR_MESSAGE to "SHA256 checksum mismatch"))
                }
            }

            // GGUF Binary Header Validation
            val validation = GgufValidator.validateHeader(targetFile.absolutePath)
            if (!validation.isValid) {
                targetFile.delete()
                markDatabaseFailed(modelId)
                showFailureNotification(modelName, "Invalid GGUF binary format")
                return Result.failure(workDataOf(KEY_ERROR_MESSAGE to "Invalid GGUF binary format"))
            }

            // Auto-import into Database
            AppDatabase.getInstance(applicationContext).modelDao().updateDownloadState(
                id = modelId,
                isDownloaded = true,
                downloadStatus = DownloadStatus.DOWNLOADED.name,
                filePath = targetFile.absolutePath,
                updatedAt = System.currentTimeMillis()
            )

            // Enrich the model record with GGUF header metadata
            AppDatabase.getInstance(applicationContext).modelDao().updateDownloadMetadata(
                id = modelId,
                architecture = validation.architecture,
                quantization = validation.fileType,
                contextLength = validation.contextLength.toInt().coerceAtLeast(1024),
                license = validation.license.ifBlank { "Apache-2.0" },
                updatedAt = System.currentTimeMillis()
            )

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
                description = "Shows live GGUF model download progress"
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun updateNotification(
        name: String,
        progress: Int,
        downloadedBytes: Long,
        totalBytes: Long,
        speedMbps: Float,
        etaSeconds: Long
    ) {
        val downloadedText = "${downloadedBytes.formatSize()} / ${totalBytes.formatSize()}"
        val statusText = "$downloadedText • ${"%.1f".format(speedMbps)} MB/s • ${etaSeconds}s remaining"

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
}
