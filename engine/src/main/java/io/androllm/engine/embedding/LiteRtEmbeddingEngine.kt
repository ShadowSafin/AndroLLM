package io.androllm.engine.embedding

import io.androllm.core.common.Result
import io.androllm.core.common.map
import io.androllm.core.common.runCatching
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter

/**
 * LiteRT-backed sentence embedding engine used by the on-device memory system.
 *
 * Replaces the (removed) llama.cpp GGUF embedding path. Embeddings now run
 * through the raw LiteRT runtime (`com.google.ai.edge.litert:litert`, classic
 * `org.tensorflow.lite.Interpreter` API) driving the EmbeddingGemma 300M model
 * (`embeddinggemma-300M_seq512_mixed-precision.tflite`):
 *
 *   text → SentencePieceTokenizer (Gemma 3 unigram, 262k vocab) → token ids
 *       → pad/truncate to 512 → LiteRT interpreter → 768-dim embedding
 *
 * The model is a `.tflite` file; the tokenizer is the sibling `tokenizer.model`
 * file that the model catalog downloads next to it. All blocking inference runs
 * on [Dispatchers.Default]; embedding must never run on the main thread.
 *
 * See `documentation/LOCAL_LLM_ARCHITECTURE.md` for why the raw interpreter is
 * used here instead of LiteRT-LM's (unreleased) `EmbeddingEngine`.
 */
@Singleton
class LiteRtEmbeddingEngine @Inject constructor() {

    /** Embedding dimension reported by the loaded model (0 = none). */
    @Volatile
    var dimension: Int = 0
        private set

    /** Absolute path of the loaded `.tflite` model ("" when none). */
    @Volatile
    var loadedModelPath: String = ""
        private set

    /** Absolute path of the loaded tokenizer ("" when none). */
    @Volatile
    var loadedTokenizerPath: String = ""
        private set

    private val mutex = Mutex()

    private var interpreter: Interpreter? = null
    private var tokenizer: SentencePieceTokenizer? = null

    /** Maximum input sequence length of the EmbeddingGemma model. */
    private val maxSeqLength = 512

    fun isLoaded(): Boolean = interpreter != null && tokenizer != null

    /**
     * Loads the `.tflite` embedding model at [modelPath] and its sibling
     * `tokenizer.model`. No-op when the same model is already loaded.
     *
     * @return the embedding dimension on success.
     */
    suspend fun loadModel(
        modelPath: String,
        tokenizerPath: String? = null,
        contextLength: Int = 512,
        batchSize: Int = 512,
        threads: Int = 4
    ): Result<Int> = io.androllm.core.common.runCatching {
        mutex.withLock {
            if (isLoaded() && loadedModelPath == modelPath) {
                dimension
            } else {
                val modelFile = File(modelPath)
                if (!modelFile.exists()) throw IllegalStateException("Embedding model not found: $modelPath")

                val resolvedTokenizer = tokenizerPath
                    ?: modelFile.parentFile?.resolve("tokenizer.model")?.absolutePath
                    ?: ""
                if (resolvedTokenizer.isBlank() || !File(resolvedTokenizer).exists()) {
                    throw IllegalStateException(
                        "Embedding tokenizer not found next to model: $resolvedTokenizer"
                    )
                }

                withContext(Dispatchers.Default) {
                    closeQuietly()
                    interpreter = Interpreter(modelFile)
                    tokenizer = SentencePieceTokenizer(File(resolvedTokenizer))
                }

                val outputShape = interpreter?.getOutputTensor(0)?.shape() ?: IntArray(0)
                dimension = outputShape.lastOrNull() ?: 0
                if (dimension <= 0) throw IllegalStateException("Embedding model reported no embedding dimension")
                loadedModelPath = modelFile.absolutePath
                loadedTokenizerPath = resolvedTokenizer
                android.util.Log.i("LiteRtEmbeddingEngine", "Embedding model loaded: dim=$dimension")
                dimension
            }
        }
    }

    /**
     * Encodes [texts] into sentence embeddings (one [FloatArray] per text).
     * Returns an empty list when [texts] is empty. Outputs are L2-normalized
     * (EmbeddingGemma's output tensor is already normalized; we re-normalize
     * defensively).
     */
    suspend fun embed(texts: List<String>): Result<List<FloatArray>> = io.androllm.core.common.runCatching {
        if (texts.isEmpty()) {
            emptyList()
        } else {
            mutex.withLock {
                val interp = interpreter ?: throw IllegalStateException("Embedding model not loaded")
                val tok = tokenizer ?: throw IllegalStateException("Embedding tokenizer not loaded")
                val dim = dimension

                withContext(Dispatchers.Default) {
                    texts.map { text ->
                        val ids = tok.encodeWithBos(text)
                        val input = IntArray(maxSeqLength)
                        for (i in 0 until minOf(ids.size, maxSeqLength)) input[i] = ids[i]

                        val inputArray = arrayOf(input)
                        val outputArray = arrayOf(FloatArray(dim))
                        interp.run(inputArray, outputArray)

                        val raw = outputArray[0]
                        val norm = norm(raw)
                        if (norm > 0f) {
                            FloatArray(raw.size) { i -> raw[i] / norm }
                        } else {
                            raw
                        }
                    }
                }
            }
        }
    }

    /** Encodes a single text. */
    suspend fun embed(text: String): Result<FloatArray> = embed(listOf(text)).map { it.first() }

    /** Unloads the embedding model, freeing its native memory. */
    suspend fun unloadModel(): Result<Unit> = io.androllm.core.common.runCatching {
        mutex.withLock {
            withContext(Dispatchers.Default) { closeQuietly() }
            dimension = 0
            loadedModelPath = ""
            loadedTokenizerPath = ""
        }
    }

    private fun closeQuietly() {
        try {
            interpreter?.close()
        } catch (_: Exception) {
        }
        interpreter = null
        tokenizer = null
    }

    private fun norm(v: FloatArray): Float {
        var sum = 0.0
        for (x in v) sum += x.toDouble() * x.toDouble()
        return kotlin.math.sqrt(sum).toFloat()
    }
}
