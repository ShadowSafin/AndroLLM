package io.androllm.core.memory.embedding

import io.androllm.core.cloud.CloudGateway
import io.androllm.core.common.Result
import io.androllm.core.common.map
import io.androllm.core.memory.MemorySettingsStore
import io.androllm.core.memory.util.MemoryLogger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Routed [EmbeddingProvider]. Embeddings are an OPTIONAL indexing
 * optimization, never a prerequisite for memory:
 *
 *  - Cloud embedding model configured + a cloud provider active →
 *    [CloudEmbeddingProvider] (via the active LiteLLM provider).
 *  - Otherwise / on cloud failure → local GGUF model ([LlamaEmbeddingProvider])
 *    when one is configured.
 *  - No embedding source available at all → embedding calls fail cleanly and
 *    the repository falls back to keyword/recent retrieval. Memory still works.
 *
 * The rest of the app depends only on [EmbeddingProvider] — it never sees a
 * provider or a model choice.
 */
@Singleton
class RoutingEmbeddingProvider @Inject constructor(
    private val cloudGateway: CloudGateway,
    private val cloud: CloudEmbeddingProvider,
    private val local: LlamaEmbeddingProvider,
    private val settingsStore: MemorySettingsStore,
    private val logger: MemoryLogger
) : EmbeddingProvider {

    override val dimension: Int
        get() = local.dimension

    override fun isModelLoaded(): Boolean = local.isModelLoaded()

    override suspend fun ensureLoaded(): Result<Unit> {
        val settings = settingsStore.current()
        // Cloud route needs no local model in memory: loading is a no-op, so
        // enabling memory in cloud mode never drags a llama embedding model
        // into RAM.
        if (settings.cloudEmbeddingModel.isNotBlank() && cloudGateway.isConfigured()) {
            logger.info("Embedding route: cloud (${settings.cloudEmbeddingModel}); no local model load")
            return Result.Success(Unit)
        }
        return local.ensureLoaded()
    }

    override suspend fun embed(texts: List<String>): Result<List<FloatArray>> {
        if (texts.isEmpty()) return Result.Success(emptyList())
        val settings = settingsStore.current()
        if (settings.cloudEmbeddingModel.isNotBlank() && cloudGateway.isConfigured()) {
            val cloudVectors = cloud.embed(texts)
            if (cloudVectors is Result.Success) return cloudVectors
            logger.warn("Cloud embedding failed (${(cloudVectors as Result.Error).exception.message}); " +
                "falling back to local")
        }
        return local.embed(texts)
    }

    override suspend fun embed(text: String): Result<FloatArray> =
        embed(listOf(text)).map { it.firstOrNull() ?: FloatArray(0) }

    override suspend fun unload(): Result<Unit> = local.unload()
}