package io.androllm.core.models.download

import java.util.Locale

/**
 * Production-grade download validation model.
 *
 * Design principles (Play Store / Ollama / HuggingFace grade):
 * - Never trust a single signal. The network truth (HEAD / Range probe) outranks
 *   the catalog; the catalog outranks nothing at runtime — it is advisory unless
 *   confirmed by headers, hash or container inspection.
 * - Every failure carries expected vs actual, byte difference, root cause and a
 *   suggested action, plus the [RepairAction] the pipeline should take.
 * - Validation never throws: it returns data the UI, logs and retry logic share.
 */

/** How a failed artifact should be repaired. Model files are never deleted. */
enum class RepairAction {
    /** Resume the missing tail with a Range request. Keeps the partial file. */
    RESUME_MISSING_BYTES,
    /** Partial file is unusable (oversized / bad header) — truncate in place and restart. */
    DELETE_PARTIAL_AND_RESTART,
    /** Bytes look complete but the hash disagrees — overwrite in place with a full re-download. */
    DELETE_AND_FULL_REDOWNLOAD,
    /** Container is valid but the wrong engine/format — point at another model. */
    SUGGEST_COMPATIBLE_MODEL,
    /** Server/token problem (gated repo, 401/403) — user action required. */
    REQUIRE_USER_ACTION,
    /** Transient (5xx, timeout, 429) — retry with backoff, keep partial file. */
    RETRY_WITH_BACKOFF,
    /** Nothing to repair. */
    NONE,
}

/** Machine-readable reason for a validation failure. */
enum class FailureReason {
    DOWNLOAD_INTERRUPTED,
    TRUNCATED_TRANSFER,
    SIZE_MISMATCH,
    HASH_MISMATCH,
    INVALID_CONTAINER,
    UNSUPPORTED_FORMAT,
    CORRUPTED_RESUME,
    GATED_MODEL,
    NETWORK_ERROR,
    INSUFFICIENT_STORAGE,
    INVALID_URL,
    UNKNOWN,
}

/**
 * A single validation failure with everything the UI, logs and auto-repair need.
 * [formatReport] renders the ❌ block required by the error-reporting spec.
 */
data class ValidationFailure(
    val reason: FailureReason,
    val expectedBytes: Long? = null,
    val actualBytes: Long? = null,
    val expectedSha256: String? = null,
    val actualSha256: String? = null,
    val url: String? = null,
    val detail: String = "",
    val suggestedAction: String = "",
    val repairAction: RepairAction = RepairAction.NONE,
) {
    val byteDifference: Long?
        get() = if (expectedBytes != null && actualBytes != null) {
            expectedBytes - actualBytes
        } else {
            null
        }

    fun formatReport(modelName: String): String {
        val sb = StringBuilder()
        sb.appendLine("❌ Validation Failed: $modelName")
        if (expectedBytes != null) sb.appendLine("Expected:   ${expectedBytes.formatBytes()} ($expectedBytes bytes)")
        if (actualBytes != null) sb.appendLine("Downloaded: ${actualBytes.formatBytes()} ($actualBytes bytes)")
        byteDifference?.let {
            val missing = it >= 0
            val abs = if (it == Long.MIN_VALUE) Long.MAX_VALUE else kotlin.math.abs(it)
            sb.appendLine(
                "Difference: ${String.format(Locale.US, "%,d", abs)} bytes " +
                    if (missing) "missing" else "extra"
            )
        }
        if (expectedSha256 != null) sb.appendLine("Expected SHA256: ${expectedSha256.take(16)}…")
        if (actualSha256 != null) sb.appendLine("Actual SHA256:   ${actualSha256.take(16)}…")
        sb.appendLine("Reason: $detail")
        if (suggestedAction.isNotBlank()) sb.append("Suggested Action: $suggestedAction")
        return sb.toString().trimEnd()
    }
}

/**
 * Result of the pre-download HEAD / Range-probe handshake.
 *
 * All fields are the *network truth* — what the server actually promises.
 * [authoritativeSize] is the size the finished file must have, or null when the
 * server streams without a length (chunked transfer): then only hash/container
 * checks can verify the artifact.
 */
data class PreflightInfo(
    val url: String,
    val finalUrl: String = url,
    val httpStatus: Int = 200,
    val contentLength: Long? = null,
    val supportsRanges: Boolean = false,
    val eTag: String? = null,
    /** LFS SHA-256 recovered from HuggingFace's X-Linked-ETag header. */
    val lfsSha256: String? = null,
    val lastModified: String? = null,
    val mimeType: String? = null,
    val gated: Boolean = false,
    val chunked: Boolean = false,
    /** Raw response headers for debugging (req §13). */
    val responseHeaders: Map<String, String> = emptyMap(),
) {
    /** Bytes the finished file must contain; null = unknown (chunked). */
    val authoritativeSize: Long? get() = contentLength?.takeIf { it > 0 }

    /** SHA-256 recovered without downloading: explicit ETag wins, else LFS tag. */
    fun recoveredSha256(): String? {
        val etag = eTag?.trim()?.trim('"') ?: return lfsSha256
        return if (etag.matches(Regex("[0-9a-fA-F]{64}"))) etag.lowercase(Locale.US) else lfsSha256
    }
}

/** Outcome of verifying one finished (or partial) file. */
sealed interface FileCheck {
    data object Valid : FileCheck
    data class Invalid(val failure: ValidationFailure) : FileCheck
}

/** Formats byte counts with thousand separators: 497,664,000 bytes. */
fun Long.formatBytes(): String = String.format(Locale.US, "%,d bytes", this)
