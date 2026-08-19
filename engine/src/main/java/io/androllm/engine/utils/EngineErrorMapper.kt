package io.androllm.engine.utils

import io.androllm.engine.compat.ModelCompatibilityException
import java.io.FileNotFoundException

/**
 * Structured, human-readable description of a model load failure.
 *
 * Produced by [EngineErrorMapper] from the raw exception a load threw, so the
 * engine can publish an [io.androllm.engine.api.EngineState.Failed] that tells
 * the user WHAT went wrong, WHERE (stage), HOW TO FIX IT (suggestion) and
 * whether pressing Retry can help (retryable) — instead of an opaque
 * "INVALID_ARGUMENT: Unsupported file format" that looks like a bug.
 */
data class LoadFailure(
    val message: String,
    val stage: String = "load",
    val suggestion: String? = null,
    val retryable: Boolean = true
)

/**
 * Maps raw load exceptions to [LoadFailure].
 *
 * Order matters: the most specific, actionable cases are matched first. The
 * mapped [LoadFailure.stage] values the UI understands:
 *
 *  - `validate` — a pre-load artifact check failed (wrong container format,
 *    truncated/corrupted file, size/checksum mismatch). Re-downloading fixes
 *    the file; retry is pointless until then.
 *  - `initialize` — the native LiteRT runtime rejected the artifact or a
 *    backend could not initialize (e.g. "Unsupported file format"). Usually a
 *    wrong file or an unsupported device; re-download or a backend switch may
 *    help.
 *  - `compatibility` — the model family/tokenizer contract cannot be
 *    resolved or satisfied. Fixing requires the sidecar files or a different
 *    artifact, not a plain retry.
 *  - `load` — anything else.
 */
object EngineErrorMapper {

    /** LiteRT native rejections that mean "this file is not loadable". */
    private val NATIVE_FILE_REJECTION_MARKERS = listOf(
        "unsupported file format",
        "not a valid litertlm",
        "not a valid tflite",
        "not a valid model",
        "invalid file",
        "failed to read model",
        "cannot open model",
        "model file is corrupt",
        "corrupted model",
        "malformed"
    )

    private val OUT_OF_MEMORY_MARKERS = listOf(
        "out of memory",
        "oom",
        "not enough memory",
        "failed to allocate",
        "insufficient memory"
    )

    private fun String.containsAny(markers: List<String>): Boolean =
        markers.any { contains(it) }

    /**
     * Maps [e] to a [LoadFailure]. Never throws; unknown exceptions degrade
     * to a generic stage "load" failure with the raw message.
     */
    fun map(e: Throwable, modelName: String = ""): LoadFailure {
        val message = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
        val lower = message.lowercase()
        val modelLabel = modelName.takeIf { it.isNotBlank() }?.let { "'$it' " }.orEmpty()

        return when {
            // Already human-readable, actionable contract violations (missing
            // tokenizer sidecars, unresolvable family). The message carries the
            // fix; a plain retry cannot succeed while the artifact is unchanged.
            e is ModelCompatibilityException -> LoadFailure(
                message = message,
                stage = "compatibility",
                retryable = false
            )

            // The file the catalog/downloader promised is not on disk.
            e is FileNotFoundException || lower.contains("not found") -> LoadFailure(
                message = message,
                stage = "validate",
                suggestion = "The model file is missing. Re-download ${modelLabel}from the catalog.",
                retryable = false
            )

            // A non-LiteRT file (GGUF leftover, renamed/partial download)
            // rejected before any native work.
            lower.contains("not a litert model") || lower.contains("not a lite rt") -> LoadFailure(
                message = message,
                stage = "validate",
                suggestion = "This is not a LiteRT model file (it is not a .litertlm container). " +
                    "Re-download ${modelLabel}from the catalog.",
                retryable = true
            )

            // A .tflite artifact (speech/embedding) handed to the chat engine.
            lower.contains("tflite") || lower.contains("flatbuffer") -> LoadFailure(
                message = message,
                stage = "validate",
                suggestion = "This is a .tflite artifact (an embedding or speech model) that cannot run as a chat model. " +
                    "Install a .litertlm chat model from the catalog.",
                retryable = false
            )

            // Size/checksum guards from the pre-load validation.
            lower.contains("size mismatch") || lower.contains("checksum mismatch") ||
                lower.contains("truncated") || lower.contains("partial download") -> LoadFailure(
                message = message,
                stage = "validate",
                suggestion = "The model file is corrupted or incomplete. Re-download ${modelLabel}from the catalog.",
                retryable = true
            )

            // The native runtime refused the artifact or a backend could not
            // initialize ("INVALID_ARGUMENT: Unsupported file format").
            lower.contains("invalid_argument") || lower.contains("unsupported") ||
                lower.containsAny(NATIVE_FILE_REJECTION_MARKERS) -> LoadFailure(
                message = message,
                stage = "initialize",
                suggestion = "The LiteRT runtime cannot load this file. It may be a wrong/corrupted artifact, or an " +
                    "unsupported model for this device. Re-download ${modelLabel}from the catalog, or try another model.",
                retryable = true
            )

            // Memory exhaustion during native init (a huge model on a small
            // device). A different backend or a smaller model can succeed.
            lower.containsAny(OUT_OF_MEMORY_MARKERS) || e is OutOfMemoryError -> LoadFailure(
                message = "The device ran out of memory while loading ${modelLabel}: $message",
                stage = "initialize",
                suggestion = "This model needs more RAM than the device has free. Close other apps, " +
                    "try the CPU backend, or install a smaller model.",
                retryable = true
            )

            // Our own check() pre-validation failures — the messages are
            // already written for humans ("Model file is empty: ...",
            // "Model file not found: ...").
            e is IllegalStateException -> LoadFailure(
                message = message,
                stage = "validate",
                retryable = true
            )

            else -> LoadFailure(
                message = message,
                stage = "load"
            )
        }
    }
}
