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
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.URI
import java.net.URL
import kotlinx.coroutines.CancellationException
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
        const val KEY_EXPECTED_SIZE = "expected_size"
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

        // ---- Pre-flight validation -------------------------------------------------
        // Reject malformed URLs (wrong scheme, garbage) before any network I/O.
        if (!isValidDownloadUrl(downloadUrl)) {
            val msg = "Invalid download URL: ${downloadUrl.ifBlank { "(empty)" }}"
            Timber.e("$msg for model $modelName")
            return failPermanent(modelId, modelName, msg)
        }

        // Make sure the target directory exists and is writable before downloading.
        val parentDir = targetFile.parentFile
        if (parentDir != null && !parentDir.exists() && !parentDir.mkdirs()) {
            val msg = "Cannot create download directory: ${parentDir.absolutePath}"
            return failPermanent(modelId, modelName, msg)
        }
        if (parentDir != null && !parentDir.canWrite()) {
            val msg = "Download directory is not writable: ${parentDir.absolutePath}"
            return failPermanent(modelId, modelName, msg)
        }

        var downloadedBytes = if (targetFile.exists()) targetFile.length() else 0L
        val expectedSize = inputData.getLong(KEY_EXPECTED_SIZE, 0L)

        // Reject the download up-front when the device clearly lacks space for it.
        if (expectedSize > 0 && parentDir != null) {
            val needed = (expectedSize - downloadedBytes).coerceAtLeast(0L)
            val usable = parentDir.usableSpace
            if (usable > 0 && usable < needed) {
                val msg = "Insufficient storage space (need ${needed.formatSize()}, have ${usable.formatSize()})"
                return failPermanent(modelId, modelName, msg)
            }
        }

        Timber.i(
            "Download start: model=%s url=%s target=%s expectedSize=%d sha256=%s",
            modelName, downloadUrl, targetPath, expectedSize, expectedSha256 ?: "none"
        )

        return try {
            val connection = openConnectionWithRedirects(downloadUrl, downloadedBytes)

            val responseCode = connection.responseCode
            val contentLength = connection.contentLengthLong
            val isPartial = responseCode == HttpURLConnection.HTTP_PARTIAL
            Timber.i(
                "Response %d from %s (content-length=%d, resume-offset=%d)",
                responseCode, connection.url, contentLength, downloadedBytes
            )

            when (responseCode) {
                HttpURLConnection.HTTP_OK -> {
                    // Server ignored our Range request: the file was truncated
                    // above, so restart the byte counter from scratch.
                    if (downloadedBytes > 0) {
                        Timber.w("Server ignored Range request; restarting download from 0")
                        downloadedBytes = 0
                    }
                }
                HttpURLConnection.HTTP_PARTIAL -> {
                    // Content-Length is the *remaining* bytes; total = offset + remaining.
                }
                else -> {
                    val msg = "HTTP Error $responseCode"
                    if (isHttpFailurePermanent(responseCode)) {
                        return failPermanent(modelId, modelName, msg)
                    }
                    return failTransient(modelId, modelName, msg)
                }
            }

            // Total size: Content-Length when present, else the catalog size,
            // else unknown (chunked transfer) → -1 (indeterminate progress).
            val totalBytes = resolveTotalBytes(contentLength, isPartial, downloadedBytes, expectedSize)
            Timber.i("Total download size: %d bytes", totalBytes)

            // Publish the first progress frame immediately so the UI never
            // shows "0 B / 0 B" while the connection is being established.
            publishProgress(modelName, downloadedBytes, totalBytes, 0f, 0L, 0)

            val appendMode = isPartial
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
                    val etaSeconds = if (smoothedSpeedBytesPerSec > 0f && remainingBytes > 0L) {
                        (remainingBytes / smoothedSpeedBytesPerSec).toLong()
                    } else {
                        0L
                    }
                    val progressPercent = if (totalBytes > 0) ((downloadedBytes * 100) / totalBytes).toInt() else 0

                    publishProgress(modelName, downloadedBytes, totalBytes, smoothedSpeedBytesPerSec, etaSeconds, progressPercent)
                    lastProgressTime = currentTime
                    lastReportedBytes = downloadedBytes
                }
            }

            outputStream.flush()
            outputStream.close()
            inputStream.close()

            Timber.i("Download complete for %s: %d bytes in %d ms", modelName, downloadedBytes, System.currentTimeMillis() - startTime)

            // Size verification: a truncated transfer (server closed the body
            // before Content-Length) or a size that contradicts the catalog is
            // a corrupted artifact — fail before any checksum/header pass.
            if (totalBytes > 0 && downloadedBytes != totalBytes) {
                val msg = "Download truncated (${downloadedBytes.formatSize()} of ${totalBytes.formatSize()})"
                return failPermanent(modelId, modelName, msg, deleteFile = true)
            }
            if (expectedSize > 0) {
                val actualSize = targetFile.length()
                if (actualSize != expectedSize) {
                    return failPermanent(
                        modelId, modelName,
                        "File size mismatch (expected $expectedSize bytes, got $actualSize)",
                        deleteFile = true
                    )
                }
            }

            // Optional SHA256 Verification if length == 64
            if (!expectedSha256.isNullOrBlank() && expectedSha256.length == 64) {
                val actualSha256 = LiteRtValidator.calculateSha256(targetFile.absolutePath)
                if (actualSha256 != null && !actualSha256.equals(expectedSha256, ignoreCase = true)) {
                    return failPermanent(modelId, modelName, "SHA256 checksum mismatch", deleteFile = true)
                }
            }

            // LiteRT Artifact Header Validation (.litertlm container or .tflite)
            val validation = LiteRtValidator.validateHeader(targetFile.absolutePath)
            if (!validation.isValid) {
                return failPermanent(modelId, modelName, validation.errorMessage, deleteFile = true)
            }

            Timber.i("Download verified for %s: size=%d bytes", modelName, targetFile.length())

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
        } catch (e: CancellationException) {
            // WorkManager cancelled us (pause/cancel/replace): never swallow
            // this into a failure path, or cancelled work gets retried.
            throw e
        } catch (e: MalformedURLException) {
            failPermanent(modelId, modelName, "Invalid download URL: ${e.message}")
        } catch (e: FileNotFoundException) {
            Timber.e(e, "Cannot write model file for $modelName")
            failPermanent(modelId, modelName, "Cannot write download file: ${e.message ?: "permission or path error"}")
        } catch (e: IOException) {
            // Transient network-level failures (timeout, reset, DNS, TLS): let
            // WorkManager retry with backoff instead of failing permanently.
            Timber.e(e, "Network failure downloading model $modelName from $downloadUrl")
            failTransient(modelId, modelName, e.message ?: "Network failure")
        } catch (e: Exception) {
            Timber.e(e, "Error downloading model $modelName from $downloadUrl")
            failPermanent(modelId, modelName, e.message ?: "Unexpected download error")
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
                // Resolve relative Location headers (Location may be a path).
                currentUrl = URL(url, newUrl).toString()
                Timber.i("Redirect %d for %s → %s", redirectCount + 1, initialUrl, currentUrl)
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

    /**
     * Publishes a progress frame to WorkManager and mirrors it in the
     * notification. Called with the very first frame (0 bytes) as soon as the
     * headers are known so the UI never shows "0 B / 0 B".
     */
    private suspend fun publishProgress(
        modelName: String,
        downloadedBytes: Long,
        totalBytes: Long,
        speedBytesPerSec: Float,
        etaSeconds: Long,
        progressPercent: Int
    ) {
        setProgress(
            workDataOf(
                KEY_PROGRESS_PERCENT to progressPercent,
                KEY_BYTES_DOWNLOADED to downloadedBytes,
                KEY_TOTAL_BYTES to totalBytes,
                KEY_SPEED_BYTES_PER_SEC to speedBytesPerSec,
                KEY_ETA_SECONDS to etaSeconds
            )
        )
        updateNotification(modelName, progressPercent, downloadedBytes, totalBytes, speedBytesPerSec, etaSeconds)
    }

    /**
     * Permanent failure: the download can never succeed as-is (bad URL, HTTP
     * 4xx, corrupt artifact, no space). The error reason is published both in
     * the progress data and in the result output so the UI can display it.
     */
    private suspend fun failPermanent(
        modelId: String,
        modelName: String,
        message: String,
        deleteFile: Boolean = false
    ): Result {
        Timber.e("Permanent download failure for $modelName: $message")
        if (deleteFile) {
            inputData.getString(KEY_TARGET_PATH)?.let { File(it).delete() }
        }
        markDatabaseFailed(modelId)
        showFailureNotification(modelName, message)
        setProgress(workDataOf(KEY_ERROR_MESSAGE to message))
        return Result.failure(workDataOf(KEY_ERROR_MESSAGE to message))
    }

    /**
     * Transient failure (timeout, connection reset, TLS hiccup, HTTP 5xx):
     * surface the reason, keep any partial file for Range resume, and let
     * WorkManager retry with backoff.
     */
    private suspend fun failTransient(modelId: String, modelName: String, message: String): Result {
        Timber.w("Transient download failure for $modelName: $message — will retry")
        markDatabaseFailed(modelId)
        showFailureNotification(modelName, message)
        setProgress(workDataOf(KEY_ERROR_MESSAGE to message))
        return Result.retry()
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

/**
 * True when an HTTP status code is a permanent client error (4xx) that retry
 * cannot fix. 408 (Request Timeout) and 429 (Too Many Requests) are treated as
 * transient, as is any 5xx (including [HttpURLConnection.HTTP_INTERNAL_ERROR]).
 */
internal fun isHttpFailurePermanent(responseCode: Int): Boolean =
    responseCode in 400..499 && responseCode != 408 && responseCode != 429

/**
 * Resolves the total download size from the response and catalog metadata.
 *
 * @param contentLength  the response's Content-Length (-1 when unknown/chunked)
 * @param isPartial      true when the server answered 206 (Range honored); the
 *                       Content-Length is then only the *remaining* bytes
 * @param downloadedBytes the resume offset for a partial response
 * @param expectedSize   the catalog file size (0 when unknown)
 * @return the total size in bytes, or -1 when nothing is known (indeterminate)
 */
internal fun resolveTotalBytes(
    contentLength: Long,
    isPartial: Boolean,
    downloadedBytes: Long,
    expectedSize: Long
): Long = when {
    contentLength > 0 && isPartial -> contentLength + downloadedBytes
    contentLength > 0 -> contentLength
    expectedSize > 0 -> expectedSize
    else -> -1L
}

/**
 * True when the URL is usable for a download: non-blank, parseable, and with
 * an http/https scheme (any other scheme cannot be opened via HttpURLConnection).
 */
internal fun isValidDownloadUrl(url: String): Boolean {
    val uri = try {
        URI.create(url)
    } catch (e: Exception) {
        null
    }
    return uri != null && (uri.scheme == "http" || uri.scheme == "https")
}
