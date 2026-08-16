package io.androllm.engine.backend

import com.google.ai.edge.litertlm.Backend
import io.androllm.core.models.Model
import io.androllm.engine.models.BackendType

/**
 * Backend abstraction over LiteRT-LM's native `Backend` types.
 *
 * The chat engine, tool system, memory system, embeddings, voice pipeline and
 * streaming layer never care which backend is executing inference — they all
 * call through [io.androllm.engine.api.InferenceEngine]. Only this hierarchy
 * knows how to build the native LiteRT-LM backend for a given device.
 *
 * Future LiteRT delegates (e.g. a new vendor NPU) are added by implementing a
 * new [InferenceBackend] subclass — no engine or UI changes required.
 */
sealed class InferenceBackend(
    /** The coarse backend type reported to the rest of the app. */
    val type: BackendType,
    /** Short display label ("NPU", "GPU", "CPU"). */
    val displayName: String,
    /** Accelerator vendor ("Qualcomm", "ARM", ...) or null when unknown. */
    val vendor: String?,
    /** Accelerator block ("Hexagon HTP", "Adreno", "XNNPACK") or null. */
    val accelerator: String?,
    /** Runtime delegate label ("LiteRT Delegate", "LiteRT GPU", "XNNPACK"). */
    val delegate: String
) {
    /**
     * Builds the native LiteRT-LM [Backend] for this implementation.
     *
     * @param threads        CPU thread count (CPU backend only).
     * @param npuLibraryDir  Directory containing the vendor NPU dispatch
     *                       libraries (NPU backends only; may be null).
     */
    abstract fun toLiteRtBackend(threads: Int, npuLibraryDir: String?): Backend

    /** CPU (XNNPACK). Always available, always the final fallback. */
    object CpuBackend : InferenceBackend(
        type = BackendType.CPU,
        displayName = "CPU",
        vendor = null,
        accelerator = "XNNPACK",
        delegate = "XNNPACK"
    ) {
        override fun toLiteRtBackend(threads: Int, npuLibraryDir: String?): Backend =
            Backend.CPU(threadCount = threads)
    }

    /** LiteRT GPU delegate (OpenCL-based on Android). */
    object GpuBackend : InferenceBackend(
        type = BackendType.GPU,
        displayName = "GPU",
        vendor = null,
        accelerator = null,
        delegate = "LiteRT GPU"
    ) {
        override fun toLiteRtBackend(threads: Int, npuLibraryDir: String?): Backend =
            Backend.GPU()
    }

    /**
     * LiteRT NPU delegate. [vendor] carries the detected NPU vendor so the UI
     * can show "Qualcomm · Hexagon HTP" without hardcoding vendors in the
     * engine. Google Tensor uses the dedicated LiteRT-LM backend type.
     */
    data class NpuBackend(val npuVendor: NpuVendor) : InferenceBackend(
        type = BackendType.NPU,
        displayName = "NPU",
        vendor = npuVendor.displayName.ifBlank { null },
        accelerator = npuVendor.acceleratorName.ifBlank { null },
        delegate = "LiteRT Delegate"
    ) {
        override fun toLiteRtBackend(threads: Int, npuLibraryDir: String?): Backend =
            if (NpuVendor.isGoogleTensor(npuVendor)) {
                Backend.GOOGLE_TENSOR()
            } else {
                // LiteRT-LM's NPU backend requires the directory holding the
                // vendor dispatch libraries; empty when none was located (the
                // initialization attempt then fails and the chain falls back).
                Backend.NPU(nativeLibraryDir = npuLibraryDir ?: "")
            }
    }
}
