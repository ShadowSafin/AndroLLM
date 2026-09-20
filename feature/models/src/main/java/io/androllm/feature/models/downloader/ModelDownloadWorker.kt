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
import io.androllm.core.models.download.FailureReason
import io.androllm.core.models.download.FileCheck
import io.androllm.core.models.download.PreflightInfo
import io.androllm.core.models.download.RepairAction
import io.androllm.core.models.download.ValidationFailure
import io.androllm.core.models.download.formatBytes
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
 * Production-grade model download worker.
 *
 * Pipeline (every stage logs expected/actual/URL/headers/container/result):
 *
 * 1. PREFLIGHT — HEAD handshake (redirects, Content-Length, Accept-Ranges,
 *    ETag/X-Linked-ETag LFS hash, Last-Modified, MIME). HEAD failures fall back
 *    to a 1-byte Range probe, then to plain GET — recovery, never hard failure.
 * 2. RESUME CHECK — partial file assessed before any byte is appended:
 *    oversized or wrong-magic partials are deleted and restarted.
 * 3. STREAM — GET with Range resume, redirect following, identity encoding
 *    (no gzip lying about Content-Length), 416 repair, progress/speed/ETA.
 * 4. VERIFY — network truth first: finished bytes vs server-declared size,
 *    then SHA-256 (catalog, else LFS tag recovered from headers), then the
 *    LiteRT container header. Catalog size is ADVISORY: when the server
 *    disagrees, the server wins and the mismatch is logged, not fatal.
 * 5. REPAIR — every failure maps to a RepairAction: resume the tail, restart
 *    a corrupted resume, full re-download on hash mismatch (transient retry),
 *    or a permanent, human-readable ❌ report for gated/unsupported artifacts.
 *
 * Partial files survive reboot, app restart, pause and network loss: the file
 * lives at the deterministic target path and resume re-validates it.
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
        /** HTTP 416 — no named constant on Android's HttpURLConnection. */
        private const val HTTP_RANGE_NOT_SATISFIABLE = 416
        private const val SPEED_SMOOTHING_FACTOR = 0.3f
        private const val MIN_SPEED_UPDATE_MS = 500L
        private const val MAX_REDIRECTS = 10
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
        val catalogSize = inputData.getLong(KEY_EXPECTED_SIZE, 0L)

        // ---- Stage 1: preflight HEAD (network truth; best-effort) ------------------
        val preflight = performPreflight(downloadUrl, modelName)
        val effectiveSha = expectedSha256?.takeIf { it.length == 64 }
            ?: preflight?.recoveredSha256()?.also {
                Timber.i("Preflight %s: recovered LFS sha256 %s… from headers", modelName, it.take(16))
            }
        val preflightSize = preflight?.authoritativeSize
        if (preflightSize != null && catalogSize > 0 && preflightSize != catalogSize) {
            // §8: never trust the hardcoded size — the server wins, the catalog
            // mismatch is a warning for the next catalog refresh, not a failure.
            Timber.w(
                "Preflight %s: server declares %d bytes but catalog says %d — trusting server",
                modelName, preflightSize, catalogSize
            )
        }
        if (preflight != null) {
            Timber.i(
                "Preflight %s: status=%d final=%s length=%s ranges=%s etag=%s mime=%s gated=%s chunked=%s headers=%s",
                modelName, preflight.httpStatus, preflight.finalUrl,
                preflight.contentLength?.toString() ?: "unknown",
                preflight.supportsRanges, preflight.eTag ?: "none",
                preflight.mimeType ?: "unknown", preflight.gated, preflight.chunked,
                preflight.responseHeaders
            )
        } else {
            Timber.w("Preflight %s: HEAD unavailable — proceeding with GET recovery", modelName)
        }
        if (preflight?.gated == true) {
            val failure = ValidationFailure(
                reason = FailureReason.GATED_MODEL,
                url = downloadUrl,
                detail = "Gated model — HuggingFace answered 401/403. A user access " +
                    "token is required to download this artifact.",
                suggestedAction = "Sign in with a HuggingFace token that accepted the model's license, then retry.",
                repairAction = RepairAction.REQUIRE_USER_ACTION,
            )
            return failPermanent(modelId, modelName, failure.formatReport(modelName))
        }

        // ---- Stage 2: assess the partial file before resuming ----------------------
        // The server truth may still be unknown (preflight failed, chunked); use
        // whatever size signal exists, or skip the oversize check until GET.
        val resumeBudget = preflightSize ?: catalogSize.takeIf { it > 0 } ?: -1L
        val partialProblem = if (downloadedBytes > 0) {
            LiteRtValidator.assessPartial(targetPath, downloadedBytes, resumeBudget)
        } else {
            null
        }
        if (partialProblem != null) {
            // Policy: model files are never deleted — the stale bytes are
            // truncated in place and the model entry is kept.
            Timber.w("Resume check %s: %s — truncating partial and restarting", modelName, partialProblem.detail)
            truncateInPlace(targetFile)
            downloadedBytes = 0L
        }

        // Self-healing metadata: when the server declares a different size than
        // the catalog, update the model entry with the new file size instead of
        // failing or deleting anything.
        if (preflightSize != null && preflightSize > 0 && catalogSize > 0 && preflightSize != catalogSize) {
            Timber.w(
                "Size update %s: catalog=%d → server=%d; persisting new file size",
                modelName, catalogSize, preflightSize
            )
            runCatching {
                AppDatabase.getInstance(applicationContext).modelDao()
                    .updateFileSize(modelId, preflightSize, System.currentTimeMillis())
            }
        }

        // Fast path: the file is already complete (previous attempt finished
        // writing but verification/import did not run, e.g. reboot mid-verify).
        // Skip the network entirely and go straight to verification.
        val completeSize = preflightSize ?: catalogSize.takeIf { it > 0 }
        if (completeSize != null && downloadedBytes == completeSize) {
            Timber.i("Fast path %s: file already complete (%d bytes) — verifying", modelName, downloadedBytes)
            return verifyAndFinish(
                modelId, modelName, targetFile,
                networkSize = preflightSize,
                catalogSize = preflightSize ?: catalogSize,
                effectiveSha = effectiveSha,
                companionUrl = companionUrl,
            )
        }

        // Reject the download up-front when the device clearly lacks space for it.
        val sizeHint = preflightSize ?: catalogSize.takeIf { it > 0 } ?: 0L
        if (sizeHint > 0 && parentDir != null) {
            val needed = (sizeHint - downloadedBytes).coerceAtLeast(0L)
            val usable = parentDir.usableSpace
            if (usable > 0 && usable < needed) {
                val failure = ValidationFailure(
                    reason = FailureReason.INSUFFICIENT_STORAGE,
                    expectedBytes = needed,
                    actualBytes = usable,
                    detail = "Insufficient storage space.",
                    suggestedAction = "Free ${needed.formatBytes()} and retry.",
                    repairAction = RepairAction.REQUIRE_USER_ACTION,
                )
                return failPermanent(modelId, modelName, failure.formatReport(modelName))
            }
        }

        Timber.i(
            "Download start: model=%s url=%s target=%s resume=%d catalogSize=%d sha256=%s",
            modelName, downloadUrl, targetPath, downloadedBytes, catalogSize, effectiveSha ?: "none"
        )

        return try {
            val connection = openConnectionWithRedirects(downloadUrl, downloadedBytes)

            val responseCode = connection.responseCode
            val contentLength = connection.contentLengthLong
            val isPartial = responseCode == HttpURLConnection.HTTP_PARTIAL
            Timber.i(
                "Response %d from %s (content-length=%d, resume-offset=%d, accept-ranges=%s, etag=%s, mime=%s)",
                responseCode, connection.url, contentLength, downloadedBytes,
                connection.getHeaderField("Accept-Ranges"),
                connection.getHeaderField("ETag") ?: connection.getHeaderField("X-Linked-ETag"),
                connection.contentType
            )

            when (responseCode) {
                HttpURLConnection.HTTP_OK -> {
                    // Server ignored our Range request: restart the byte counter,
                    // truncating in place — the model entry and file are kept.
                    if (downloadedBytes > 0) {
                        Timber.w("Server ignored Range request; restarting download from 0")
                        truncateInPlace(targetFile)
                        downloadedBytes = 0
                    }
                }
                HttpURLConnection.HTTP_PARTIAL -> {
                    // Content-Length is the *remaining* bytes; total = offset + remaining.
                }
                HTTP_RANGE_NOT_SATISFIABLE -> {
                    // Partial is universally stale (server file replaced/shorter):
                    // corrupted resume → truncate in place and restart once, right here.
                    Timber.w("HTTP 416 for %s — partial no longer matches server file; restarting", modelName)
                    connection.disconnect()
                    truncateInPlace(targetFile)
                    return restartFromZero(
                        modelId, modelName, downloadUrl, targetFile, catalogSize,
                        effectiveSha, companionUrl, preflightSize
                    )
                }
                else -> {
                    val msg = "HTTP Error $responseCode"
                    if (responseCode == 401 || responseCode == 403) {
                        val failure = ValidationFailure(
                            reason = FailureReason.GATED_MODEL,
                            url = downloadUrl,
                            detail = "Gated model — server answered HTTP $responseCode. " +
                                "A user access token is required.",
                            suggestedAction = "Sign in with a HuggingFace token, then retry.",
                            repairAction = RepairAction.REQUIRE_USER_ACTION,
                        )
                        return failPermanent(modelId, modelName, failure.formatReport(modelName))
                    }
                    if (isHttpFailurePermanent(responseCode)) {
                        return failPermanent(modelId, modelName, msg)
                    }
                    return failTransient(modelId, modelName, msg)
                }
            }

            // Total size: GET truth first, then preflight, then catalog advisory,
            // else unknown (chunked transfer) → -1 (indeterminate progress).
            val serverTotal = resolveTotalBytes(contentLength, isPartial, downloadedBytes, 0L)
                .takeIf { it > 0 }
            val totalBytes = serverTotal
                ?: preflightSize?.let { if (isPartial) it else it }
                ?: catalogSize.takeIf { it > 0 }
                ?: -1L
            Timber.i(
                "Total %s: server=%s preflight=%s catalog=%d → %d bytes",
                modelName, serverTotal?.toString() ?: "unknown",
                preflightSize?.toString() ?: "unknown", catalogSize, totalBytes
            )

            // Publish the first progress frame immediately so the UI never
            // shows "0 B / 0 B" while the connection is being established.
            publishProgress(modelName, downloadedBytes, totalBytes, 0f, 0L, 0)

            val appendMode = isPartial && downloadedBytes > 0
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
            connection.disconnect()

            Timber.i(
                "Download complete %s: %d bytes in %d ms (server=%s catalog=%d sha=%s)",
                modelName, downloadedBytes, System.currentTimeMillis() - startTime,
                serverTotal?.toString() ?: "unknown", catalogSize, effectiveSha?.take(16) ?: "none"
            )

            // ---- Stage 4: structured verification -----------------------------------
            // Network truth is binding; catalog size is advisory (§8). Hash and
            // container checks are authoritative. Nothing is ever deleted: stale
            // bytes are truncated in place and the model entry keeps the file.
            val networkSize = serverTotal ?: preflightSize
            return verifyAndFinish(
                modelId, modelName, targetFile,
                networkSize = networkSize,
                catalogSize = preflightSize ?: catalogSize,
                effectiveSha = effectiveSha,
                companionUrl = companionUrl,
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

    /**
     * Single self-repair path for HTTP 416: wipes the stale partial and runs
     * one fresh GET pass inline (same verification tail as [doWork]).
     */
    private suspend fun restartFromZero(
        modelId: String,
        modelName: String,
        downloadUrl: String,
        targetFile: File,
        catalogSize: Long,
        effectiveSha: String?,
        companionUrl: String,
        preflightSize: Long?,
    ): Result {
        return try {
            val connection = openConnectionWithRedirects(downloadUrl, 0L)
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                val code = connection.responseCode
                connection.disconnect()
                return if (isHttpFailurePermanent(code)) {
                    failPermanent(modelId, modelName, "HTTP Error $code")
                } else {
                    failTransient(modelId, modelName, "HTTP Error $code")
                }
            }
            val total = connection.contentLengthLong.takeIf { it > 0 }
                ?: preflightSize ?: catalogSize.takeIf { it > 0 } ?: -1L
            publishProgress(modelName, 0L, total, 0f, 0L, 0)
            connection.inputStream.use { input ->
                FileOutputStream(targetFile, false).use { output -> input.copyTo(output) }
            }
            connection.disconnect()
            val networkSize = connection.contentLengthLong.takeIf { it > 0 } ?: preflightSize
            return verifyAndFinish(
                modelId, modelName, targetFile,
                networkSize = networkSize,
                catalogSize = preflightSize ?: catalogSize,
                effectiveSha = effectiveSha,
                companionUrl = companionUrl,
            )
        } catch (e: IOException) {
            failTransient(modelId, modelName, e.message ?: "Network failure")
        }
    }

    /**
     * Stage-4 verification + import, shared by the streaming pass, the 416
     * restart and the already-complete fast path.
     *
     * Policy: model files are never deleted. A stale catalog size is healed by
     * persisting the verified real size ([updateFileSize]); bad bytes are
     * truncated in place and refreshed by WorkManager retry; only truly
     * unrunnable artifacts (wrong container, gated) fail permanently — with the
     * file and the model entry kept for the user to inspect or replace.
     */
    private suspend fun verifyAndFinish(
        modelId: String,
        modelName: String,
        targetFile: File,
        networkSize: Long?,
        catalogSize: Long,
        effectiveSha: String?,
        companionUrl: String,
    ): Result {
        when (val check = LiteRtValidator.validateFile(
            path = targetFile.absolutePath,
            authoritativeSize = networkSize,
            catalogSize = catalogSize,
            expectedSha256 = effectiveSha,
            modelName = modelName,
        )) {
            is FileCheck.Valid -> {
                if (networkSize == null && catalogSize > 0 && targetFile.length() != catalogSize) {
                    Timber.w(
                        "Verify %s: chunked transfer, no server size — catalog advisory " +
                            "says %d but file is %d; accepting on hash/container verdict",
                        modelName, catalogSize, targetFile.length()
                    )
                }
            }
            is FileCheck.Invalid -> {
                val failure = check.failure
                Timber.e("Verify %s failed: %s", modelName, failure.formatReport(modelName))
                return when (failure.repairAction) {
                    // Truncated tail → keep partial, let WorkManager retry the
                    // Range resume with backoff.
                    RepairAction.RESUME_MISSING_BYTES ->
                        failTransient(modelId, modelName, failure.formatReport(modelName))
                    // Hash mismatch on complete bytes → truncate in place and
                    // retry the full download via WorkManager retry.
                    RepairAction.DELETE_AND_FULL_REDOWNLOAD -> {
                        truncateInPlace(targetFile)
                        failTransient(modelId, modelName, failure.formatReport(modelName))
                    }
                    // Corrupted resume that slipped through → truncate, retry.
                    RepairAction.DELETE_PARTIAL_AND_RESTART -> {
                        truncateInPlace(targetFile)
                        failTransient(modelId, modelName, failure.formatReport(modelName))
                    }
                    // Wrong container / gated / storage → permanent + report.
                    // File and model entry are kept; nothing is deleted.
                    else -> failPermanent(modelId, modelName, failure.formatReport(modelName))
                }
            }
        }

        val actualSize = targetFile.length()
        Timber.i("Download verified for %s: size=%d bytes", modelName, actualSize)

        // Auto-import into Database, persisting the verified real file size so
        // a stale catalog size heals itself on the next read.
        val dao = AppDatabase.getInstance(applicationContext).modelDao()
        dao.updateDownloadState(
            id = modelId,
            isDownloaded = true,
            downloadStatus = DownloadStatus.DOWNLOADED.name,
            filePath = targetFile.absolutePath,
            updatedAt = System.currentTimeMillis()
        )
        runCatching {
            dao.updateFileSize(modelId, actualSize, System.currentTimeMillis())
        }

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

        return Result.success(
            workDataOf(
                KEY_TARGET_PATH to targetFile.absolutePath,
                KEY_PROGRESS_PERCENT to 100
            )
        )
    }

    /**
     * Truncates a stale file in place (zero bytes, same path, same model
     * entry). Used instead of deleting: the model is kept and its bytes are
     * refreshed by the next pass.
     */
    private fun truncateInPlace(file: File) {
        runCatching {
            FileOutputStream(file, false).close()
        }.onFailure { e ->
            Timber.e(e, "Truncate failed for ${file.absolutePath} — keeping the file as-is")
        }
    }

    /**
     * Stage-1 preflight: HEAD handshake with manual redirect following so the
     * FIRST response's headers (HuggingFace `X-Linked-ETag` LFS hash on the
     * 302) are captured. Best-effort — any failure returns null (the GET pass
     * recovers) except gated repos, which are reported as [PreflightInfo.gated].
     */
    private fun performPreflight(initialUrl: String, modelName: String): PreflightInfo? {
        // Attempt 1: HEAD following redirects manually.
        try {
            var currentUrl = initialUrl
            var hops = 0
            var firstLinkedETag: String? = null
            var firstStatus = 200
            while (hops <= MAX_REDIRECTS) {
                val connection = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = 15_000
                    readTimeout = 15_000
                    requestMethod = "HEAD"
                    setRequestProperty("User-Agent", "AndroLLM/1.0 (Android)")
                    setRequestProperty("Accept-Encoding", "identity")
                }
                connection.connect()
                val code = connection.responseCode
                if (hops == 0) {
                    firstStatus = code
                    firstLinkedETag = connection.getHeaderField("X-Linked-ETag")
                }
                if (code == 401 || code == 403) {
                    connection.disconnect()
                    return PreflightInfo(url = initialUrl, httpStatus = code, gated = true)
                }
                if (code == HttpURLConnection.HTTP_MOVED_PERM ||
                    code == HttpURLConnection.HTTP_MOVED_TEMP ||
                    code == HttpURLConnection.HTTP_SEE_OTHER ||
                    code == 307 || code == 308
                ) {
                    val location = connection.getHeaderField("Location")
                    connection.disconnect()
                    if (location.isNullOrBlank()) break
                    currentUrl = URL(URL(currentUrl), location).toString()
                    hops++
                    continue
                }
                // Final response.
                val headers = linkedMapOf<String, String>()
                for ((key, value) in connection.headerFields) {
                    if (key != null && value != null && value.isNotEmpty()) headers[key] = value.first()
                }
                val length = connection.contentLengthLong.takeIf { it > 0 }
                val info = PreflightInfo(
                    url = initialUrl,
                    finalUrl = currentUrl,
                    httpStatus = code,
                    contentLength = length,
                    supportsRanges = (connection.getHeaderField("Accept-Ranges") ?: "")
                        .equals("bytes", ignoreCase = true),
                    eTag = connection.getHeaderField("ETag"),
                    lfsSha256 = firstLinkedETag?.trim()?.trim('"')
                        ?.takeIf { it.matches(Regex("[0-9a-fA-F]{64}")) }?.lowercase()
                        ?: connection.getHeaderField("X-Linked-ETag")?.trim()?.trim('"')
                            ?.takeIf { it.matches(Regex("[0-9a-fA-F]{64}")) }?.lowercase(),
                    lastModified = connection.getHeaderField("Last-Modified"),
                    mimeType = connection.contentType?.substringBefore(";")?.trim(),
                    chunked = "chunked".equals(connection.getHeaderField("Transfer-Encoding"), ignoreCase = true),
                    responseHeaders = headers,
                )
                connection.disconnect()
                if (code in 200..299) return info
                if (firstStatus == 401 || firstStatus == 403) {
                    return info.copy(gated = true)
                }
                break
            }
        } catch (e: Exception) {
            Timber.w(e, "Preflight HEAD failed for $modelName — trying Range probe")
        }

        // Attempt 2: 1-byte Range probe (servers that refuse HEAD, incl. some
        // HF CDN edges). Content-Range: bytes 0-0/<total> reveals the size.
        try {
            val connection = (URL(initialUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 15_000
                readTimeout = 15_000
                setRequestProperty("User-Agent", "AndroLLM/1.0 (Android)")
                setRequestProperty("Accept-Encoding", "identity")
                setRequestProperty("Range", "bytes=0-0")
            }
            connection.connect()
            val code = connection.responseCode
            if (code == 401 || code == 403) {
                connection.disconnect()
                return PreflightInfo(url = initialUrl, httpStatus = code, gated = true)
            }
            val total = parseContentRangeTotal(connection.getHeaderField("Content-Range"))
                ?: connection.contentLengthLong.takeIf { it > 0 }
            val headers = linkedMapOf<String, String>()
            for ((key, value) in connection.headerFields) {
                if (key != null && value != null && value.isNotEmpty()) headers[key] = value.first()
            }
            connection.disconnect()
            if (code == HttpURLConnection.HTTP_PARTIAL || code == HttpURLConnection.HTTP_OK) {
                return PreflightInfo(
                    url = initialUrl,
                    httpStatus = code,
                    contentLength = total,
                    supportsRanges = code == HttpURLConnection.HTTP_PARTIAL ||
                        (connection.getHeaderField("Accept-Ranges") ?: "")
                            .equals("bytes", ignoreCase = true),
                    mimeType = connection.contentType?.substringBefore(";")?.trim(),
                    responseHeaders = headers,
                )
            }
        } catch (e: Exception) {
            Timber.w(e, "Preflight Range probe failed for $modelName — GET will recover")
        }
        return null
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

    private fun getForegroundInfo(progress: Int, name: String): ForegroundInfo {
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("Downloading $name")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(100, progress, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
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
            .setContentText(error.take(200))
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun openConnectionWithRedirects(initialUrl: String, downloadedBytes: Long): HttpURLConnection {
        var redirectCount = 0
        var currentUrl = initialUrl

        while (redirectCount < MAX_REDIRECTS) {
            val url = URL(currentUrl)
            val connection = url.openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = true
            connection.connectTimeout = 30_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("User-Agent", "AndroLLM/1.0 (Android)")
            // Identity encoding: gzip/deflate would make Content-Length describe
            // the COMPRESSED body and break every size check downstream.
            connection.setRequestProperty("Accept-Encoding", "identity")

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
                // Resolve relative Location headers (Location may be a path) and
                // preserve signed-URL query strings on absolute redirects.
                currentUrl = URL(url, newUrl).toString()
                Timber.i("Redirect %d → %s", redirectCount + 1, currentUrl)
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
            connection.setRequestProperty("Accept-Encoding", "identity")
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
     * 4xx, gated repo, corrupt artifact, no space). The error reason is published both in
     * the progress data and in the result output so the UI can display it.
     * Model files are never deleted — the entry and its bytes are kept.
     */
    private suspend fun failPermanent(
        modelId: String,
        modelName: String,
        message: String
    ): Result {
        Timber.e("Permanent download failure for $modelName: $message")
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
        showFailureNotification(modelName, message.take(200))
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
 * Parses the total size from a Content-Range header ("bytes 0-0/497664000").
 * Null when absent or unparsable — the caller falls back to other signals.
 */
internal fun parseContentRangeTotal(contentRange: String?): Long? {
    if (contentRange.isNullOrBlank()) return null
    val total = contentRange.substringAfterLast('/', "").trim()
    if (total == "*") return null
    return total.toLongOrNull()?.takeIf { it > 0 }
}

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
