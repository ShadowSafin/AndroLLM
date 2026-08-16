package io.androllm.core.cloud

import io.androllm.core.cloud.model.CloudChatMessage
import io.androllm.core.cloud.model.CloudChatRequest
import io.androllm.core.cloud.model.CloudException
import io.androllm.core.cloud.model.CloudGenerationConfig
import io.androllm.core.cloud.model.CloudResponseFormat
import io.androllm.core.cloud.model.CloudSettings
import io.androllm.core.cloud.model.CloudStreamEvent
import io.androllm.core.cloud.model.ResolvedCloudModel
import io.androllm.core.cloud.network.LiteLLMClient
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/**
 * Single entry point used by the chat layer for cloud inference.
 *
 * Resolves the active provider + model (including custom-model overrides)
 * from settings and streams the completion through the LiteLLM proxy. Cloud
 * failures surface as [CloudException]s and never touch the local engine —
 * switching back to GGUF inference is a pure UI action.
 */
@Singleton
class CloudGateway @Inject constructor(
    private val client: LiteLLMClient,
    private val manager: ProviderManager
) {

    val settings: Flow<CloudSettings> = manager.settings

    /** Master toggle for cloud chat mode (persisted). */
    suspend fun setCloudModeEnabled(enabled: Boolean) = manager.setCloudModeEnabled(enabled)

    /** True when cloud mode is enabled AND a provider with a usable model exists. */
    suspend fun isConfigured(): Boolean {
        if (!manager.current().enabled) return false
        return resolveChatTarget() != null
    }

    /** Resolves provider/model; null when cloud inference is not possible. */
    suspend fun resolveChatTarget(): Pair<String, String>? {
        val resolved = manager.resolveChatModel() ?: return null
        return resolved.provider.id to resolved.modelId
    }

    /**
     * Best-effort maximum output tokens for the active model ([modelId]
     * overrides the default selection), from /v1/model/info metadata. Null
     * when unknown — requests should then omit `max_tokens` so the provider
     * uses its own maximum instead of an artificial ceiling.
     */
    suspend fun maxOutputTokensFor(modelId: String? = null): Long? =
        manager.maxOutputTokensFor(modelId)

    /**
     * Streams a chat completion with the active provider + default model
     * ([modelId] overrides the selection). Throws [CloudException] when no
     * provider/model is configured or the request fails.
     */
    fun streamChat(
        messages: List<CloudChatMessage>,
        config: CloudGenerationConfig,
        retries: Int? = null,
        modelId: String? = null
    ): Flow<CloudStreamEvent> = flow {
        val settings = manager.current()
        val retryCount = retries ?: settings.retryCount
        val resolved = resolveResolved(modelId)
            ?: throw CloudException("No cloud provider/model configured — open Cloud Providers to set one up")
        if (!resolved.provider.enabled) throw CloudException("Provider '${resolved.provider.name}' is disabled")
        val request = buildRequest(resolved.modelId, messages, config, stream = true)
        emitAll(client.streamChat(resolved.provider, resolved.apiKey, request, retryCount, resolved.overrides))
    }

    /** Runs a non-streaming completion (used by tests / one-shot tools). */
    suspend fun chatOnce(
        messages: List<CloudChatMessage>,
        config: CloudGenerationConfig,
        modelId: String? = null
    ): String {
        val resolved = resolveResolved(modelId)
            ?: throw CloudException("No cloud provider/model configured")
        val response = client.chat(
            resolved.provider,
            resolved.apiKey,
            buildRequest(resolved.modelId, messages, config, stream = false),
            overrides = resolved.overrides
        )
        return response.choices.firstOrNull()?.message?.content.orEmpty()
    }

    /**
     * Text embeddings through the active provider's connection.
     *
     * [modelId] overrides the model used for embeddings (normally the chat
     * default); pass an embedding-capable model id (e.g. "openai/text-embedding-3-small")
     * when calling from the memory subsystem. Throws [CloudException] when no
     * provider is configured.
     */
    suspend fun embed(inputs: List<String>, modelId: String? = null): List<List<Float>> {
        val resolved = resolveResolved()
            ?: throw CloudException("No cloud provider/model configured")
        return client.embeddings(resolved.provider, resolved.apiKey, modelId ?: resolved.modelId, inputs)
            .data.map { it.embedding }
    }

    /** Resolves provider+model for [modelId] (or the persisted default). */
    private suspend fun resolveResolved(modelId: String? = null): ResolvedCloudModel? =
        manager.resolveChatModel(modelId)

    private fun buildRequest(
        model: String,
        messages: List<CloudChatMessage>,
        config: CloudGenerationConfig,
        stream: Boolean
    ): CloudChatRequest = CloudChatRequest(
        model = model,
        messages = messages,
        temperature = config.temperature,
        top_p = config.topP,
        top_k = config.topK,
        max_tokens = config.maxTokens,
        seed = config.seed,
        stop = config.stop,
        stream = stream,
        response_format = when {
            config.jsonSchema != null ->
                CloudResponseFormat(type = "json_schema", json_schema = config.jsonSchema)
            config.responseFormat != null -> CloudResponseFormat(type = config.responseFormat)
            else -> null
        },
        tools = config.tools
    )
}
