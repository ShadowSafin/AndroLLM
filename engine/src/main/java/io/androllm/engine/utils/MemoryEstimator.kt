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
    const val COMPUTE_SCRATCH_FRACTION = 0.15f

    /** Aggressive low-memory scratch (smaller prefill batches, compact buffers). */
    const val COMPUTE_SCRATCH_FRACTION_AGGRESSIVE = 0.08f

    /** Balanced scratch (reduced batch). */
    const val COMPUTE_SCRATCH_FRACTION_BALANCED = 0.11f

    // ── Overhead constants (native allocations beyond weights/KV/scratch) ──
    /** Tokenizer + vocab structures (SentencePiece model, chat template). */
    const val TOKENIZER_OVERHEAD_BYTES = 48L * 1024 * 1024

    /** Minimal tokenizer overhead when reusing / lazy loading. */
    const val TOKENIZER_OVERHEAD_COMPACT_BYTES = 24L * 1024 * 1024

    /** Delegate overhead (OpenCL/Vulkan/NPU runtime, command pools, etc). */
    const val DELEGATE_OVERHEAD_CPU = 24L * 1024 * 1024
    const val DELEGATE_OVERHEAD_GPU = 192L * 1024 * 1024
    const val DELEGATE_OVERHEAD_NPU = 128L * 1024 * 1024
    const val DELEGATE_OVERHEAD_GPU_COMPACT = 96L * 1024 * 1024

    /** Temporary activation buffers (varies with batch / context). */
    const val TEMP_BUFFER_MIN = 16L * 1024 * 1024
    const val TEMP_BUFFER_MAX = 64L * 1024 * 1024

    /** Large-model threshold — 7B/8B class treated as high-memory but still tried aggressively. */
    const val LARGE_MODEL_FILE_THRESHOLD = 3_800L * 1024 * 1024 // ~3.8GB
    const val XLARGE_MODEL_FILE_THRESHOLD = 4_500L * 1024 * 1024 // ~4.5GB (Qwen3 8B Q4)

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

    fun estimateComputeScratchBytesAggressive(weightsBytes: Long): Long =
        (weightsBytes * COMPUTE_SCRATCH_FRACTION_AGGRESSIVE).toLong()

    fun estimateComputeScratchForFraction(weightsBytes: Long, fraction: Float): Long =
        (weightsBytes * fraction).toLong()

    // ── Detailed overhead helpers (no double-counting) ────────────────────
    fun estimateTokenizerOverhead(compact: Boolean = false): Long =
        if (compact) TOKENIZER_OVERHEAD_COMPACT_BYTES else TOKENIZER_OVERHEAD_BYTES

    fun estimateBackendOverhead(backend: io.androllm.engine.models.BackendType, compact: Boolean = false): Long =
        when (backend) {
            io.androllm.engine.models.BackendType.GPU,
            io.androllm.engine.models.BackendType.VULKAN,
            io.androllm.engine.models.BackendType.LLAMA_CPP_VULKAN -> if (compact) DELEGATE_OVERHEAD_GPU_COMPACT else DELEGATE_OVERHEAD_GPU
            io.androllm.engine.models.BackendType.NPU -> DELEGATE_OVERHEAD_NPU
            else -> DELEGATE_OVERHEAD_CPU
        }

    fun estimateBackendOverheadByName(backendName: String, compact: Boolean = false): Long =
        when (backendName.lowercase()) {
            "gpu", "vulkan" -> if (compact) DELEGATE_OVERHEAD_GPU_COMPACT else DELEGATE_OVERHEAD_GPU
            "npu" -> DELEGATE_OVERHEAD_NPU
            else -> DELEGATE_OVERHEAD_CPU
        }

    fun estimateTempBufferBytes(weightsBytes: Long, batchSize: Int): Long {
        // Smaller batches dramatically reduce temp activation memory; scale linearly then clamp.
        val batchFraction = (batchSize.coerceIn(128, 2048).toFloat() / 2048f)
        val scaled = (weightsBytes * 0.04f * batchFraction).toLong()
        return scaled.coerceIn(TEMP_BUFFER_MIN, TEMP_BUFFER_MAX)
    }

    fun isLargeModel(fileSizeBytes: Long): Boolean = fileSizeBytes >= LARGE_MODEL_FILE_THRESHOLD

    fun isXLargeModel(fileSizeBytes: Long): Boolean = fileSizeBytes >= XLARGE_MODEL_FILE_THRESHOLD

    fun modelCategory(fileSizeBytes: Long): String = when {
        fileSizeBytes >= XLARGE_MODEL_FILE_THRESHOLD -> "8B"
        fileSizeBytes >= LARGE_MODEL_FILE_THRESHOLD -> "7B"
        fileSizeBytes >= 1_800L * 1024 * 1024 -> "3B"
        fileSizeBytes >= 900L * 1024 * 1024 -> "1.5B"
        else -> "small"
    }

    /**
     * Detailed native RAM footprint with all overhead components broken out.
     * Avoids double-counting: weights already include +5% runtime structures;
     * tokenizer/delegate/temp are ADDED once, not layered on top of each other.
     */
    fun estimateDetailedFootprint(
        fileSizeBytes: Long,
        contextLength: Int,
        blockCount: Int = 0,
        headCountKv: Int = 0,
        keyLength: Int = 0,
        backend: io.androllm.engine.models.BackendType = io.androllm.engine.models.BackendType.CPU,
        scratchFraction: Float = COMPUTE_SCRATCH_FRACTION,
        includeTokenizer: Boolean = true,
        compactTokenizer: Boolean = false,
        includeBackend: Boolean = true,
        compactBackend: Boolean = false,
        batchSize: Int = 512,
        includeTempBuffers: Boolean = false
    ): Long {
        val weights = estimateWeightsMemory(fileSizeBytes)
        val kv = estimateKvCacheBytes(contextLength, blockCount, headCountKv, keyLength, fileSizeBytes)
        val scratch = estimateComputeScratchForFraction(weights, scratchFraction)
        var total = weights + kv + scratch
        if (includeTokenizer) total += estimateTokenizerOverhead(compactTokenizer)
        if (includeBackend) total += estimateBackendOverhead(backend, compactBackend)
        if (includeTempBuffers) total += estimateTempBufferBytes(weights, batchSize).coerceAtMost(32L * 1024 * 1024)
        return total
    }

    fun breakdown(
        fileSizeBytes: Long,
        contextLength: Int,
        blockCount: Int = 0,
        headCountKv: Int = 0,
        keyLength: Int = 0,
        backend: io.androllm.engine.models.BackendType = io.androllm.engine.models.BackendType.CPU,
        scratchFraction: Float = COMPUTE_SCRATCH_FRACTION,
        batchSize: Int = 512
    ): FootprintBreakdown {
        val weights = estimateWeightsMemory(fileSizeBytes)
        val kv = estimateKvCacheBytes(contextLength, blockCount, headCountKv, keyLength, fileSizeBytes)
        val scratch = estimateComputeScratchForFraction(weights, scratchFraction)
        val tokenizer = estimateTokenizerOverhead(false)
        val delegate = estimateBackendOverhead(backend, false)
        val temp = estimateTempBufferBytes(weights, batchSize).coerceAtMost(32L * 1024 * 1024)
        return FootprintBreakdown(weights, kv, scratch, tokenizer, delegate, temp)
    }

    data class FootprintBreakdown(
        val weightsBytes: Long,
        val kvBytes: Long,
        val scratchBytes: Long,
        val tokenizerBytes: Long,
        val delegateBytes: Long,
        val tempBufferBytes: Long
    ) {
        val totalBytes: Long get() = weightsBytes + kvBytes + scratchBytes + tokenizerBytes + delegateBytes + tempBufferBytes
        val totalCompactBytes: Long get() = weightsBytes + kvBytes + (scratchBytes / 2) + TOKENIZER_OVERHEAD_COMPACT_BYTES + DELEGATE_OVERHEAD_CPU
        fun summaryMb(): String = "weights=${weightsBytes/1_048_576}MB kv=${kvBytes/1_048_576}MB scratch=${scratchBytes/1_048_576}MB tok=${tokenizerBytes/1_048_576}MB delegate=${delegateBytes/1_048_576}MB"
    }

    /**
     * Aggressive low-memory footprint: minimal overhead, reduced scratch and
     * compact delegate/tokenizer — reflects what the runtime actually uses
     * after applying memory-saving settings (smaller batch, buffer reuse, lazy alloc).
     */
    fun estimateAggressiveFootprint(
        fileSizeBytes: Long,
        contextLength: Int,
        blockCount: Int = 0,
        headCountKv: Int = 0,
        keyLength: Int = 0,
        backend: io.androllm.engine.models.BackendType = io.androllm.engine.models.BackendType.CPU
    ): Long = estimateDetailedFootprint(
        fileSizeBytes = fileSizeBytes,
        contextLength = contextLength,
        blockCount = blockCount,
        headCountKv = headCountKv,
        keyLength = keyLength,
        backend = backend,
        scratchFraction = COMPUTE_SCRATCH_FRACTION_AGGRESSIVE,
        includeTokenizer = true,
        compactTokenizer = true,
        includeBackend = true,
        compactBackend = (backend == io.androllm.engine.models.BackendType.CPU || backend == io.androllm.engine.models.BackendType.NPU),
        batchSize = 512,
        includeTempBuffers = false
    )

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
