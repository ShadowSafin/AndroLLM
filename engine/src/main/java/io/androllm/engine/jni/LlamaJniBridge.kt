package io.androllm.engine.jni

/**
 * Thrown when the native library cannot be loaded.
 */
class NativeLibraryException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Kotlin bridge to the native llama.cpp engine (`libandrollm_llama.so`).
 *
 * All native calls are blocking; the caller must never invoke them on the
 * main thread.
 */
object LlamaJniBridge {

    /**
     * Receives streamed tokens from native code.
     */
    fun interface TokenCallback {
        /**
         * Called once per decoded token. [delta] is the new text,
         * [finished] is true on the last token.
         */
        fun onToken(delta: String, finished: Boolean)
    }

    private const val LIBRARY_NAME = "androllm_llama"

    private var libraryLoaded = false

    @Synchronized
    fun ensureLoaded() {
        if (libraryLoaded) return
        try {
            System.loadLibrary(LIBRARY_NAME)
            libraryLoaded = true
        } catch (e: Throwable) {
            throw NativeLibraryException(
                "Failed to load native library $LIBRARY_NAME",
                e
            )
        }
    }

    /**
     * Creates the native engine and returns its handle.
     * @return engine handle (0 on failure, throws on fatal errors)
     */
    external fun nativeCreate(configJson: String): Long

    /**
     * Loads a GGUF model into the engine.
     * @throws java.lang.RuntimeException when the model cannot be loaded
     */
    external fun nativeLoadModel(engineHandle: Long, modelPath: String, loadConfigJson: String)

    /**
     * True when a model is currently loaded in the engine.
     */
    external fun nativeIsLoaded(engineHandle: Long): Boolean

    /**
     * Returns JSON metadata of the loaded model, or "null".
     */
    external fun nativeModelInfo(engineHandle: Long): String

    /**
     * Renders [messageHistoryJson] (a JSON array of {"role", "content"}
     * objects) with the loaded model's GGUF chat template.
     * Returns the rendered prompt, or an empty string when the model has no
     * supported chat template.
     */
    external fun nativeApplyChatTemplate(
        engineHandle: Long,
        messageHistoryJson: String,
        addAssistant: Boolean
    ): String

    /**
     * Extended variant of [nativeApplyChatTemplate] with explicit control over
     * the Jinja `enable_thinking` flag. Models like Qwen2.5/Qwen3 emit
     *  tokens when this is true. Default is false for non-thinking models.
     */
    external fun nativeApplyChatTemplateEx(
        engineHandle: Long,
        messageHistoryJson: String,
        addAssistant: Boolean,
        enableThinking: Boolean
    ): String

    /**
     * Runs generation synchronously. [callback] is invoked per token on the
     * calling thread. Returns a JSON string with performance stats.
     */
    external fun nativeGenerate(
        engineHandle: Long,
        prompt: String,
        genConfigJson: String,
        callback: TokenCallback
    ): String

    /**
     * Runs a multi-turn chat generation synchronously. [messageHistoryJson] is
     * the FULL message history (a JSON array of {"role", "content"} objects);
     * the native engine diffs it against its accumulated conversation and
     * decodes only the new messages' template diff at the continuing KV
     * position (official llama.cpp multi-turn pattern). [callback] is invoked
     * per token on the calling thread. Returns a JSON string with performance
     * stats.
     */
    external fun nativeGenerateChat(
        engineHandle: Long,
        messageHistoryJson: String,
        addAssistant: Boolean,
        genConfigJson: String,
        callback: TokenCallback
    ): String

    /**
     * Requests cancellation of an in-flight generation.
     */
    external fun nativeCancel(engineHandle: Long)

    /**
     * Unloads the model, keeping the engine alive.
     */
    external fun nativeUnload(engineHandle: Long)

    /**
     * Destroys the engine and frees all native resources.
     */
    external fun nativeRelease(engineHandle: Long)

    /**
     * Resets chat state between conversations: clears accumulated messages,
     * chat position, and the KV cache.
     */
    external fun nativeResetChat(engineHandle: Long)

    /**
     * Runs [iterations] benchmark passes against the loaded model.
     * Returns a JSON string with benchmark results.
     */
    external fun nativeBenchmark(
        engineHandle: Long,
        iterations: Int,
        callback: TokenCallback
    ): String

    /**
     * Peak native memory used by the engine, in bytes.
     */
    external fun nativeMemoryPeak(engineHandle: Long): Long

    /**
     * Returns true when the Vulkan GPU backend is available on this device.
     */
    external fun nativeVulkanAvailable(): Boolean

    /**
     * Runs a warm-up inference to pre-compile GPU shaders and initialize
     * command pools. Returns a JSON string with warm-up statistics.
     */
    external fun nativeWarmUp(engineHandle: Long): String

    /**
     * Returns a JSON string with current memory usage statistics:
     * modelSizeBytes, contextSizeBytes, gpuLayersOffloaded, backend, peakMemoryBytes.
     */
    external fun nativeGetMemoryStats(engineHandle: Long): String

    /**
     * Returns a JSON string with full diagnostics of the loaded model and the
     * last generation (chat template, tokenizer framing, prompt/generated
     * token IDs, first-token latency). Consumed by the hidden debug panel.
     */
    external fun nativeGetDebugInfo(engineHandle: Long): String

    // ── Embedding model (memory system) ──
    // The embedding model is loaded into its own engine handle (typically a
    // separate nativeCreate() result), keeping it fully independent of the
    // chat model's lifecycle and KV cache.

    /**
     * Loads a GGUF embedding model (MiniLM / BGE / nomic / Qwen3-Embedding)
     * into the engine for sentence embedding generation.
     * [cfgJson] supports contextLength, batchSize and threads.
     * @throws java.lang.RuntimeException when the model cannot be loaded
     */
    external fun nativeLoadEmbeddingModel(
        engineHandle: Long,
        modelPath: String,
        cfgJson: String
    )

    /**
     * True when an embedding model is currently loaded in the engine.
     */
    external fun nativeEmbeddingLoaded(engineHandle: Long): Boolean

    /**
     * Embedding dimension of the loaded embedding model (0 when none).
     */
    external fun nativeEmbeddingDim(engineHandle: Long): Int

    /**
     * Encodes [texts] into sentence embeddings. Returns a JSON string
     * encoding an array of float arrays (e.g. "[[0.1,0.2,...],...]").
     * Blocking: never call on the main thread.
     */
    external fun nativeEmbed(engineHandle: Long, texts: Array<String>): String

    /**
     * Unloads the embedding model and frees its native memory.
     */
    external fun nativeUnloadEmbeddingModel(engineHandle: Long)
}
