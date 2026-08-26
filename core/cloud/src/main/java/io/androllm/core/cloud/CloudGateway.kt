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
import io.androllm.core.cloud.pipeline.CloudPipelineLogger
import io.androllm.core.cloud.pipeline.CloudRequestPlanner
import io.androllm.core.cloud.pipeline.CloudResultObserver
import io.androllm.core.cloud.pipeline.CloudTurnResult
import io.androllm.core.cloud.pipeline.PlannedCloudRequest
import io.androllm.core.cloud.usage.CloudErrorKind
import io.androllm.core.cloud.usage.CloudRequestKind
import io.androllm.core.cloud.usage.CloudUsageMeter
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Single entry point used by the chat layer for cloud inference.
 *
 * Resolves the active provider + model (including custom-model overrides)
 * from settings and streams the completion through the LiteLLM proxy. Cloud
 * failures surface as [CloudException]s and never touch the local engine —
 * switching back to GGUF inference is a pure UI action.
 *
 * Every request runs through the cloud pipeline:
 *
 * ```
 * User request → Prompt assembly → Cache lookup → Tool planning →
 * Provider selection (+ fallback chain) → Cloud request →
 * Tool result handling (caller) → Final answer → Usage logging
 * ```
 *
 * - [CloudRequestPlanner] validates the request, fingerprints the stable
 *   prompt prefix and consults the prompt cache (provider-aware cache hints
 *   are attached so repeated system prompts / tool schemas cost less).
 * - [CloudResultObserver] folds the event stream into metrics (latency,
 *   first-token time, usage, tool-call count, normalized error category).
 * - [CloudUsageMeter] records every request for the usage dashboard —
 *   recording never throws, so usage accounting cannot break inference.
 * - Provider fallback: when the primary provider fails BEFORE emitting any
 *   event (rate limit, timeout, 5xx, transport), the request is retried on
 *   the other enabled providers.
 */
@Singleton
class CloudGateway @Inject constructor(
    private val client: LiteLLMClient,
    private val manager: ProviderManager,
    private val usageMeter: CloudUsageMeter,
    private val requestPlanner: CloudRequestPlanner,
    private val resultObserver: CloudResultObserver
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

    /** Live usage meter — exposed for the usage dashboard. */
    fun usageMeter(): CloudUsageMeter = usageMeter

    /** Prompt cache diagnostics source — exposed for the usage dashboard. */
    fun requestPlanner(): CloudRequestPlanner = requestPlanner

    /**
     * Streams a chat completion with the active provider + default model
     * ([modelId] overrides the selection). Throws [CloudException] when no
     * provider/model is configured or the request fails.
     *
     * [sessionId] correlates the requests of one conversation for the usage
     * dashboard (active sessions, per-conversation cache invalidation).
     */
    fun streamChat(
        messages: List<CloudChatMessage>,
        config: CloudGenerationConfig,
        retries: Int? = null,
        modelId: String? = null,
        sessionId: String? = null
    ): Flow<CloudStreamEvent> = flow {
        val settings = manager.current()
        val retryCount = retries ?: settings.retryCount
        val resolved = resolveResolved(modelId)
            ?: throw CloudException("No cloud provider/model configured — open Cloud Providers to set one up")
        if (!resolved.provider.enabled) throw CloudException("Provider '${resolved.provider.name}' is disabled")

        usageMeter.init()
        val baseRequest = buildRequest(resolved.modelId, messages, config, stream = true)
        val planned = requestPlanner.plan(baseRequest, resolved.provider.id, sessionId)
        if (!planned.validation.valid) {
            val reason = planned.validation.errors.joinToString("; ")
            CloudPipelineLogger.failure("request rejected by validator: $reason")
            recordFailure(planned, resolved, reason, CloudErrorKind.MALFORMED, sessionId)
            throw CloudException("Cloud request rejected: $reason")
        }

        CloudPipelineLogger.request(
            "start stream provider='${resolved.provider.name}' model=${resolved.modelId} " +
                "tools=${planned.toolCount} cache=${if (planned.cacheLookup?.hit == true) "HIT" else "MISS"}"
        )

        val candidates = buildFallbackChain(resolved)
        var outcome: CloudTurnResult? = null
        var serving: ResolvedCloudModel = resolved
        var usedFallback = false
        var fallbackHops = 0

        for ((index, candidate) in candidates.withIndex()) {
            serving = candidate
            val candidateRequest = if (index == 0) {
                planned.request
            } else {
                planned.request.copy(model = candidate.modelId)
            }
            val result = resultObserver.observe(
                client.streamChat(
                    candidate.provider, candidate.apiKey, candidateRequest, retryCount, candidate.overrides
                )
            ) { event -> emit(event) }
            outcome = result
            if (result.success) break
            val canFallback = index < candidates.lastIndex &&
                !result.receivedAnyEvent &&
                resultObserver.isFallbackEligible(result)
            if (!canFallback) break
            // Record the failed attempt so the dashboard sees per-provider
            // failures, then fall back to the next provider.
            recordTurn(planned, candidate, result, retryCount = 0, usedFallback = false, sessionId, streamed = true)
            fallbackHops++
            usedFallback = true
            CloudPipelineLogger.retry(
                "provider '${candidate.provider.name}' failed (${result.errorKind.name}) " +
                    "before first token — falling back to next provider"
            )
        }

        val final = outcome ?: throw CloudException("No cloud provider available")
        recordTurn(planned, serving, final, retryCount = fallbackHops, usedFallback, sessionId, streamed = true)
        CloudPipelineLogger.request(
            "finish stream provider='${serving.provider.name}' model=${serving.modelId} " +
                "ok=${final.success} latency=${final.latencyMs}ms firstToken=${final.firstTokenMs ?: "-"}ms " +
                "tokens=${final.usage?.totalTokens ?: 0} tools=${final.toolCalls.size}"
        )
        if (!final.success) {
            throw CloudException(
                final.errorMessage.ifBlank { "Cloud request failed (${final.errorKind.name})" }
            )
        }
    }

    /** Runs a non-streaming completion (used by tests / one-shot tools). */
    suspend fun chatOnce(
        messages: List<CloudChatMessage>,
        config: CloudGenerationConfig,
        modelId: String? = null,
        sessionId: String? = null
    ): String {
        val resolved = resolveResolved(modelId)
            ?: throw CloudException("No cloud provider/model configured")
        usageMeter.init()
        val baseRequest = buildRequest(resolved.modelId, messages, config, stream = false)
        val planned = requestPlanner.plan(baseRequest, resolved.provider.id, sessionId)
        if (!planned.validation.valid) {
            val reason = planned.validation.errors.joinToString("; ")
            recordFailure(planned, resolved, reason, CloudErrorKind.MALFORMED, sessionId)
            throw CloudException("Cloud request rejected: $reason")
        }

        val candidates = buildFallbackChain(resolved)
        var lastError: CloudException? = null
        for ((index, candidate) in candidates.withIndex()) {
            val candidateRequest = if (index == 0) planned.request else planned.request.copy(model = candidate.modelId)
            val startedAt = System.currentTimeMillis()
            try {
                val response = client.chat(
                    candidate.provider,
                    candidate.apiKey,
                    candidateRequest,
                    overrides = candidate.overrides
                )
                val latency = System.currentTimeMillis() - startedAt
                val usage = response.usage
                val toolCalls = response.choices.firstOrNull()?.message?.toolCalls?.size ?: 0
                usageMeter.record(
                    usageMeter.buildRecord(
                        providerId = candidate.provider.id,
                        providerName = candidate.provider.name,
                        modelId = candidate.modelId,
                        kind = CloudRequestKind.CHAT,
                        inputTokens = usage?.prompt_tokens ?: 0,
                        outputTokens = usage?.completion_tokens ?: 0,
                        cachedTokens = usage?.cachedTokens ?: 0,
                        latencyMs = latency,
                        success = true,
                        retryCount = index,
                        usedFallbackProvider = index > 0,
                        cacheHit = planned.cacheLookup?.hit == true,
                        cacheSavedTokens = planned.cacheLookup?.takeIf { it.hit }?.savedTokensEstimate?.toLong() ?: 0,
                        toolCallsCount = toolCalls,
                        finishReason = response.choices.firstOrNull()?.finish_reason.orEmpty(),
                        sessionId = sessionId.orEmpty(),
                        streamed = false
                    )
                )
                return response.choices.firstOrNull()?.message?.content.orEmpty()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: CloudException) {
                lastError = e
                val kind = resultObserver.classify(e)
                val canFallback = index < candidates.lastIndex &&
                    kind in setOf(
                        CloudErrorKind.TIMEOUT, CloudErrorKind.RATE_LIMIT,
                        CloudErrorKind.HTTP_ERROR, CloudErrorKind.TRANSPORT
                    )
                usageMeter.record(
                    usageMeter.buildRecord(
                        providerId = candidate.provider.id,
                        providerName = candidate.provider.name,
                        modelId = candidate.modelId,
                        kind = CloudRequestKind.CHAT,
                        latencyMs = System.currentTimeMillis() - startedAt,
                        success = false,
                        errorKind = kind,
                        errorMessage = e.message.orEmpty(),
                        usedFallbackProvider = index > 0,
                        cacheHit = planned.cacheLookup?.hit == true,
                        sessionId = sessionId.orEmpty(),
                        streamed = false
                    )
                )
                if (!canFallback) throw e
                CloudPipelineLogger.retry("chatOnce: '${candidate.provider.name}' failed (${kind.name}) — falling back")
            }
        }
        throw lastError ?: CloudException("No cloud provider available")
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
        usageMeter.init()
        val startedAt = System.currentTimeMillis()
        try {
            val response = client.embeddings(resolved.provider, resolved.apiKey, modelId ?: resolved.modelId, inputs)
            usageMeter.record(
                usageMeter.buildRecord(
                    providerId = resolved.provider.id,
                    providerName = resolved.provider.name,
                    modelId = modelId ?: resolved.modelId,
                    kind = CloudRequestKind.EMBEDDING,
                    inputTokens = response.usage?.prompt_tokens ?: 0,
                    latencyMs = System.currentTimeMillis() - startedAt,
                    success = true,
                    streamed = false
                )
            )
            return response.data.map { it.embedding }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            usageMeter.record(
                usageMeter.buildRecord(
                    providerId = resolved.provider.id,
                    providerName = resolved.provider.name,
                    modelId = modelId ?: resolved.modelId,
                    kind = CloudRequestKind.EMBEDDING,
                    latencyMs = System.currentTimeMillis() - startedAt,
                    success = false,
                    errorKind = resultObserver.classify(e),
                    errorMessage = e.message.orEmpty(),
                    streamed = false
                )
            )
            throw e
        }
    }

    /** Resolves provider+model for [modelId] (or the persisted default). */
    private suspend fun resolveResolved(modelId: String? = null): ResolvedCloudModel? =
        manager.resolveChatModel(modelId)

    /**
     * Provider fallback chain: the resolved primary first, then every other
     * ENABLED provider (preferring the same model id when available, else the
     * provider's own default model). Fallbacks only ever run before the first
     * event is emitted, so callers never see two providers' output mixed.
     */
    private suspend fun buildFallbackChain(primary: ResolvedCloudModel): List<ResolvedCloudModel> {
        val chain = mutableListOf(primary)
        runCatching {
            val settings = manager.current()
            for (provider in settings.providers) {
                if (!provider.enabled || provider.id == primary.provider.id) continue
                val candidate = runCatching {
                    manager.resolveOnProvider(provider.id, primary.modelId)
                }.getOrNull() ?: continue
                chain += candidate
            }
        }.onFailure { e ->
            CloudPipelineLogger.failure("fallback chain build failed — using primary only", e)
        }
        if (chain.size > 1) {
            CloudPipelineLogger.provider(
                "fallback chain: " + chain.joinToString(" → ") { "'${it.provider.name}' (${it.modelId})" }
            )
        }
        return chain
    }

    /** Records a completed (or failed) streamed turn in the usage meter. */
    private fun recordTurn(
        planned: PlannedCloudRequest,
        serving: ResolvedCloudModel,
        result: CloudTurnResult,
        retryCount: Int,
        usedFallback: Boolean,
        sessionId: String?,
        streamed: Boolean
    ) {
        usageMeter.record(
            usageMeter.buildRecord(
                providerId = serving.provider.id,
                providerName = serving.provider.name,
                modelId = serving.modelId,
                kind = CloudRequestKind.CHAT,
                inputTokens = result.usage?.promptTokens ?: 0,
                outputTokens = result.usage?.completionTokens ?: 0,
                latencyMs = result.latencyMs,
                firstTokenMs = result.firstTokenMs,
                success = result.success,
                errorKind = result.errorKind,
                errorMessage = result.errorMessage,
                retryCount = retryCount,
                usedFallbackProvider = usedFallback,
                cacheHit = planned.cacheLookup?.hit == true,
                cacheSavedTokens = planned.cacheLookup?.takeIf { it.hit }?.savedTokensEstimate?.toLong() ?: 0,
                toolCallsCount = result.toolCalls.size,
                finishReason = result.finishReason.orEmpty(),
                sessionId = sessionId.orEmpty(),
                streamed = streamed
            )
        )
    }

    /** Records a request that failed before any provider round-trip. */
    private fun recordFailure(
        planned: PlannedCloudRequest,
        resolved: ResolvedCloudModel,
        message: String,
        kind: CloudErrorKind,
        sessionId: String?
    ) {
        usageMeter.record(
            usageMeter.buildRecord(
                providerId = resolved.provider.id,
                providerName = resolved.provider.name,
                modelId = resolved.modelId,
                kind = CloudRequestKind.CHAT,
                success = false,
                errorKind = kind,
                errorMessage = message,
                cacheHit = planned.cacheLookup?.hit == true,
                sessionId = sessionId.orEmpty(),
                streamed = false
            )
        )
    }

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
