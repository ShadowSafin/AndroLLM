package io.androllm.engine.embedding

import io.androllm.core.common.Result
import io.androllm.core.common.map
import io.androllm.core.common.runCatching
import io.androllm.engine.jni.LlamaJniBridge
import io.androllm.engine.jni.NativeLibraryException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/**
 * Dedicated llama.cpp-backed sentence embedding engine used by the on-device
 * memory system.
 *
 * The embedding model (a small GGUF: MiniLM / BGE-small / nomic-embed-text /
 * Qwen3-Embedding) runs on its OWN native engine handle, fully independent of
 * the chat engine's handle, model lifecycle and KV cache. All blocking native
 * calls run on [Dispatchers.Default]; embedders must never run on the main
 * thread.
 */
@Singleton
class LlamaEmbeddingEngine @Inject constructor() {

    private val json = Json { ignoreUnknownKeys = true }

    private var engineHandle: Long = 0L

    /** Set after a successful [loadModel]. */
    @Volatile
    var dimension: Int = 0
        private set

    /** Absolute path of the loaded embedding model ("" when none). */
    @Volatile
    var loadedModelPath: String = ""
        private set

    private val mutex = Mutex()

    private val isNativeAvailable: Boolean by lazy {
        try {
            LlamaJniBridge.ensureLoaded()
            true
        } catch (e: NativeLibraryException) {
            false
        }
    }

    /**
     * Creates the native engine handle if needed. Idempotent.
     */
    private suspend fun ensureHandle() = withContext(Dispatchers.Default) {
        if (!isNativeAvailable) throw IllegalStateException("Native engine unavailable")
        if (engineHandle == 0L) {
            engineHandle = LlamaJniBridge.nativeCreate("{}")
            if (engineHandle == 0L) throw IllegalStateException("Failed to create embedding engine")
        }
    }

    /**
     * True when an embedding model is loaded and ready.
     */
    fun isLoaded(): Boolean = engineHandle != 0L && LlamaJniBridge.nativeEmbeddingLoaded(engineHandle)

    /**
     * Loads the GGUF embedding model at [modelPath].
     * @return the embedding dimension on success.
     */
    suspend fun loadModel(
        modelPath: String,
        contextLength: Int = 512,
        batchSize: Int = 512,
        threads: Int = 4
    ): Result<Int> = io.androllm.core.common.runCatching {
        mutex.withLock {
            if (isLoaded() && loadedModelPath == modelPath) {
                dimension
            } else {
                ensureHandle()
                LlamaJniBridge.nativeLoadEmbeddingModel(
                    engineHandle,
                    modelPath,
                    """{"contextLength":$contextLength,"batchSize":$batchSize,"threads":$threads}"""
                )
                loadedModelPath = modelPath
                dimension = LlamaJniBridge.nativeEmbeddingDim(engineHandle)
                if (dimension <= 0) throw IllegalStateException("Embedding model reported no embedding dimension")
                android.util.Log.i("LlamaEmbeddingEngine", "Embedding model loaded: dim=$dimension")
                dimension
            }
        }
    }

    /**
     * Encodes [texts] into sentence embeddings (one [FloatArray] per text).
     * Returns an empty list when [texts] is empty. All texts share the model's
     * embedding dimension; results are L2-normalized by the native layer.
     */
    suspend fun embed(texts: List<String>): Result<List<FloatArray>> = io.androllm.core.common.runCatching {
        if (texts.isEmpty()) {
            emptyList()
        } else {
            mutex.withLock {
                if (!isLoaded()) throw IllegalStateException("Embedding model not loaded")
                val raw = withContext(Dispatchers.Default) {
                    LlamaJniBridge.nativeEmbed(engineHandle, texts.toTypedArray())
                }
                val parsed = json.parseToJsonElement(raw) as? JsonArray
                    ?: throw IllegalStateException("Unexpected embedding response")
                parsed.map { entry ->
                    val arr = entry as? JsonArray
                        ?: throw IllegalStateException("Unexpected embedding element")
                    FloatArray(arr.size) { i -> (arr[i] as? JsonPrimitive)?.doubleOrNull?.toFloat() ?: 0f }
                }
            }
        }
    }

    /**
     * Encodes a single text.
     */
    suspend fun embed(text: String): Result<FloatArray> = embed(listOf(text)).map { it.first() }

    /**
     * Unloads the embedding model, freeing its native memory.
     */
    suspend fun unloadModel(): Result<Unit> = io.androllm.core.common.runCatching {
        mutex.withLock {
            if (engineHandle != 0L) {
                withContext(Dispatchers.Default) {
                    LlamaJniBridge.nativeUnloadEmbeddingModel(engineHandle)
                }
            }
            dimension = 0
            loadedModelPath = ""
        }
    }

    /**
     * Releases the native engine handle. The engine cannot be reused afterwards
     * without a fresh [loadModel].
     */
    fun release() {
        if (engineHandle != 0L) {
            try {
                LlamaJniBridge.nativeRelease(engineHandle)
            } catch (_: Exception) {
            }
            engineHandle = 0L
        }
        dimension = 0
        loadedModelPath = ""
    }
}
