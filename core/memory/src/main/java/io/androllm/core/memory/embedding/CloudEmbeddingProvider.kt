package io.androllm.core.memory.embedding

import io.androllm.core.cloud.CloudGateway
import io.androllm.core.common.Result
import io.androllm.core.memory.MemorySettingsStore
import io.androllm.core.memory.util.MemoryLogger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cloud-backed embedding source: vectors are produced by the ACTIVE LiteLLM
 * provider using the configured cloud embedding model id
 * (e.g. "openai/text-embedding-3-small"). Best-effort — a failure is a
 * [Result.Error] that the router turns into a local fallback, so memory
 * indexing never breaks because a cloud embedding call failed.
 */
@Singleton
class CloudEmbeddingProvider @Inject constructor(
    private val cloudGateway: CloudGateway,
    private val settingsStore: MemorySettingsStore,
    private val logger: MemoryLogger
) {

    /** True when a cloud embedding model id is configured. */
    suspend fun isConfigured(): Boolean =
        settingsStore.current().cloudEmbeddingModel.isNotBlank()

    suspend fun embed(texts: List<String>): Result<List<FloatArray>> =
        io.androllm.core.common.runCatching {
            if (texts.isEmpty()) return Result.Success(emptyList())
            val modelId = settingsStore.current().cloudEmbeddingModel
            val vectors = cloudGateway.embed(texts, modelId = modelId.takeIf { it.isNotBlank() })
            logger.info("Cloud embedding: ${texts.size} text(s) via $modelId")
            vectors.map { it.toFloatArray() }
        }
}