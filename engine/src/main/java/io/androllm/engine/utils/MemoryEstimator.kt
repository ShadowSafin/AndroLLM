package io.androllm.engine.utils

/**
 * Estimates memory (bytes) needed to run a GGUF model on device.
 *
 * llama.cpp allocates NATIVE memory (mmap + heap), not Java heap, so the
 * footprint here is compared against system RAM by [ModelResourceGuard] —
 * never against `Runtime.maxMemory()`. The [fitsInHeap] helper exists only
 * for legacy callers and must not be used as the production gate.
 */
object MemoryEstimator {

    /**
     * Approximate bytes per parameter for common quantizations:
     * Q4_K_M ~4.85, Q5_K_M ~5.5, Q6_K ~6.6, Q8_0 ~8.5, FP16 ~16.
     */
    val QUANT_BYTES_PER_PARAM: Map<String, Float> = mapOf(
        "q4_k_m" to 4.85f,
        "q5_k_m" to 5.5f,
        "q6_k" to 6.6f,
        "q8_0" to 8.5f,
        "f16" to 16f
    )

    private const val DEFAULT_BYTES_PER_PARAM = 5.0f

    /** f16 KV entries: 2 bytes per element. */
    private const val KV_TYPE_SIZE = 2L

    /** 2 = K + V caches. */
    private const val KV_GROUPS = 2L

    /**
     * Compute-buffer (graph) scratch: llama.cpp additionally allocates a
     * working set for the compute graph — approximately 15% of the weights
     * for typical 1B-8B models.
     */
    private const val COMPUTE_SCRATCH_FRACTION = 0.15

    /**
     * Estimates the resident size of the weights, derived from the GGUF file size.
     * The file size already reflects the quantization, so a small overhead is added
     * for runtime structures.
     */
    fun estimateWeightsMemory(fileSizeBytes: Long): Long =
        fileSizeBytes * 105 / 100

    /**
     * Fallback KV bytes per token when the GGUF header does not expose the
     * transformer geometry (block count / heads / head dim). Size-aware: KV
     * scales with the model, and a flat per-token constant grossly
     * over-estimates small models at long contexts (96 KB × 32k = 3 GB for a
     * 0.5B model — a false refusal). Derive from the file size (~Q4-ish
     * bytes/param) and clamp to a sane band.
     */
    fun fallbackKvBytesPerToken(fileSizeBytes: Long): Long =
        (fileSizeBytes / FALLBACK_DIVISOR).coerceIn(FALLBACK_KV_MIN, FALLBACK_KV_MAX)

    /**
     * Estimates KV-cache memory for a context window (f16 KV entries) using
     * the size-aware fallback; prefer [estimateKvCacheBytes] when the header
     * geometry is available.
     */
    fun estimateContextMemory(
        contextLength: Int,
        fileSizeBytes: Long = 0L,
        kvBytesPerToken: Long = 0L
    ): Long {
        val perToken = when {
            kvBytesPerToken > 0L -> kvBytesPerToken
            fileSizeBytes > 0L -> fallbackKvBytesPerToken(fileSizeBytes)
            else -> FALLBACK_KV_MID
        }
        return contextLength.toLong() * perToken
    }

    /**
     * Layer-accurate KV cache: `2 (K+V) * n_layer * n_ctx * n_head_kv * head_dim * 2`.
     * Falls back to the size-aware [fallbackKvBytesPerToken] when any geometry
     * field is unknown.
     */
    fun estimateKvCacheBytes(
        contextLength: Int,
        blockCount: Int,
        headCountKv: Int,
        keyLength: Int,
        fileSizeBytes: Long = 0L
    ): Long {
        if (blockCount <= 0 || headCountKv <= 0 || keyLength <= 0) {
            return estimateContextMemory(contextLength, fileSizeBytes = fileSizeBytes)
        }
        return KV_GROUPS * blockCount.toLong() * contextLength * headCountKv * keyLength * KV_TYPE_SIZE
    }

    /**
     * Compute-graph scratch buffer (native allocation, freed after each decode).
     */
    fun estimateComputeScratchBytes(weightsBytes: Long): Long =
        (weightsBytes * COMPUTE_SCRATCH_FRACTION).toLong()

    /**
     * Total native RAM footprint: weights (+5% runtime structures) + KV cache
     * + compute scratch. Use the layer-accurate KV estimate when the header
     * geometry is known, the size-aware fallback otherwise.
     */
    fun estimateTotalFootprint(
        fileSizeBytes: Long,
        contextLength: Int,
        blockCount: Int = 0,
        headCountKv: Int = 0,
        keyLength: Int = 0
    ): Long {
        val weights = estimateWeightsMemory(fileSizeBytes)
        val kv = estimateKvCacheBytes(contextLength, blockCount, headCountKv, keyLength, fileSizeBytes)
        return weights + kv + estimateComputeScratchBytes(weights)
    }

    /** ~5 bytes/param (Q4-class); a 600 MB file ⇒ ~120M params. */
    private const val FALLBACK_DIVISOR = 50_000L
    private const val FALLBACK_KV_MIN = 8L * 1024L
    private const val FALLBACK_KV_MID = 48L * 1024L
    private const val FALLBACK_KV_MAX = 128L * 1024L

    /**
     * Total RAM footprint estimate for a model + context (legacy heuristic).
     */
    fun estimateTotalMemory(
        fileSizeBytes: Long,
        contextLength: Int,
        quantization: String = ""
    ): Long {
        val weights = estimateWeightsMemory(fileSizeBytes)
        val kv = estimateContextMemory(contextLength)
        return weights + kv
    }

    /**
     * True when the estimated footprint fits in the app-visible Java heap.
     *
     * NOTE: NOT the production gate — llama.cpp memory is native, not Java
     * heap. Kept for legacy callers/tests; use [ModelResourceGuard] instead.
     */
    fun fitsInHeap(fileSizeBytes: Long, contextLength: Int): Boolean {
        val maxHeap = Runtime.getRuntime().maxMemory()
        return estimateTotalMemory(fileSizeBytes, contextLength) * 1.25f < maxHeap
    }
}
