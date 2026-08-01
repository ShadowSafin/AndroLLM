package io.androllm.engine.utils

/**
 * Estimates memory (bytes) needed to run a GGUF model on device.
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

    /**
     * Estimates the resident size of the weights, derived from the GGUF file size.
     * The file size already reflects the quantization, so a small overhead is added
     * for runtime structures.
     */
    fun estimateWeightsMemory(fileSizeBytes: Long): Long =
        fileSizeBytes * 105 / 100

    /**
     * Estimates KV-cache memory for a context window (f16 KV entries).
     * Rough average for 1B-4B class models with GQA.
     */
    fun estimateContextMemory(contextLength: Int, kvBytesPerToken: Long = 96L * 1024L): Long =
        contextLength.toLong() * kvBytesPerToken

    /**
     * Total RAM footprint estimate for a model + context.
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
     * True when the estimated footprint fits in the app-visible heap.
     */
    fun fitsInHeap(fileSizeBytes: Long, contextLength: Int): Boolean {
        val maxHeap = Runtime.getRuntime().maxMemory()
        return estimateTotalMemory(fileSizeBytes, contextLength) * 1.25f < maxHeap
    }
}
