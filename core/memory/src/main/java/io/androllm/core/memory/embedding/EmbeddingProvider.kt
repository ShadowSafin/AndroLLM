package io.androllm.core.memory.embedding

import io.androllm.core.common.Result
import io.androllm.core.common.map
import io.androllm.core.common.onError
import io.androllm.core.common.onSuccess
import io.androllm.core.memory.MemorySettingsStore
import io.androllm.core.memory.util.MemoryLogger
import io.androllm.engine.embedding.LiteRtEmbeddingEngine
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Abstraction over sentence-embedding generation. The production
 * implementation runs the EmbeddingGemma 300M `.tflite` model through the raw
 * LiteRT interpreter — fully on-device, no network.
 */
interface EmbeddingProvider {

    /** Embedding dimension of the loaded model (0 = none loaded). */
    val dimension: Int

    fun isModelLoaded(): Boolean

    /** Loads the configured model (no-op when already loaded). */
    suspend fun ensureLoaded(): Result<Unit>

    suspend fun embed(texts: List<String>): Result<List<FloatArray>>

    suspend fun embed(text: String): Result<FloatArray>

    /** Unloads the model from memory. */
    suspend fun unload(): Result<Unit>
}

/**
 * LiteRT-backed [EmbeddingProvider]. Uses the dedicated embedding engine in
 * the engine module; the model path comes from [MemorySettings].
 */
@Singleton
class LiteRtEmbeddingProvider @Inject constructor(
    private val engine: LiteRtEmbeddingEngine,
    private val settingsStore: MemorySettingsStore,
    private val logger: MemoryLogger
) : EmbeddingProvider {

    override val dimension: Int get() = engine.dimension

    override fun isModelLoaded(): Boolean = engine.isLoaded()

    override suspend fun ensureLoaded(): Result<Unit> {
        val settings = currentSettings() ?: return Result.error("Memory settings unavailable")
        val path = settings.embeddingModelPath
        if (path.isBlank()) {
            return Result.error("No embedding model configured")
        }
        if (engine.isLoaded() && engine.loadedModelPath == path) return Result.Success(Unit)

        val result = engine.loadModel(
            modelPath = path,
            contextLength = settings.embeddingContextLength,
            batchSize = settings.embeddingBatchSize
        )
        result
            .onSuccess { logger.info("Embedding model loaded (dim=$it): $path") }
            .onError { logger.warn("Embedding model load failed: ${it.message}") }
        return result.map { Unit }
    }

    override suspend fun embed(texts: List<String>): Result<List<FloatArray>> {
        if (texts.isEmpty()) return Result.Success(emptyList())
        val loaded = ensureLoaded()
        if (loaded is Result.Error) return Result.error(loaded.exception)

        val settings = currentSettings()
        val prefix = settings?.passagePrefix.orEmpty()
        val prefixed = if (prefix.isBlank()) texts else texts.map { "$prefix $it" }
        return engine.embed(prefixed)
    }

    override suspend fun embed(text: String): Result<FloatArray> =
        embed(listOf(text)).map { it.firstOrNull() ?: FloatArray(0) }

    override suspend fun unload(): Result<Unit> {
        if (engine.isLoaded()) logger.info("Embedding model unloaded")
        return engine.unloadModel()
    }

    private suspend fun currentSettings() = try {
        settingsStore.current()
    } catch (_: Exception) {
        null
    }
}
