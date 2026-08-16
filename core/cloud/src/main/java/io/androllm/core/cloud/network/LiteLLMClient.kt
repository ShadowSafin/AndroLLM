package io.androllm.core.cloud.network

import io.androllm.core.cloud.model.CloudChatRequest
import io.androllm.core.cloud.model.CloudChatResponse
import io.androllm.core.cloud.model.CloudEmbeddingRequest
import io.androllm.core.cloud.model.CloudEmbeddingResponse
import io.androllm.core.cloud.model.CloudException
import io.androllm.core.cloud.model.CloudHealth
import io.androllm.core.cloud.model.CloudModelInfo
import io.androllm.core.cloud.model.CloudModelOverrides
import io.androllm.core.cloud.model.ModelMetadata
import io.androllm.core.cloud.model.CloudProvider
import io.androllm.core.cloud.model.CloudQuota
import io.androllm.core.cloud.model.CloudStreamEvent
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.OkHttpClient

/**
 * Thin client over a LiteLLM proxy's OpenAI-compatible REST surface, built
 * on Retrofit + OkHttp (HTTP/2, connection pooling, retries, compression).
 *
 * The proxy translates every request to the underlying provider (OpenAI,
 * Anthropic, Gemini, Groq, OpenRouter, Ollama, ...), so this client needs no
 * per-provider code — model ids like `anthropic/claude-3-5-sonnet` or
 * `openrouter/anthropic/claude-sonnet` route server-side.
 *
 * Secrets handling: the caller passes the already-decrypted API key and this
 * client never logs request/response content. [CloudModelOverrides] lets a
 * custom model route to its own LiteLLM server/key/headers.
 */
class LiteLLMClient(
    private val api: LiteLLMApi = CloudHttpClientFactory.createApi(),
    private val retryPolicy: RetryPolicy = RetryPolicy()
) {

    /** Test-friendly constructor: build the client over a custom OkHttp stack. */
    constructor(client: OkHttpClient) : this(CloudHttpClientFactory.createApi(client), RetryPolicy())

    /** Test-friendly constructor with a custom retry policy. */
    constructor(client: OkHttpClient, retryPolicy: RetryPolicy) :
        this(CloudHttpClientFactory.createApi(client), retryPolicy)

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Streams a chat completion over SSE. Emits [CloudStreamEvent] elements
     * (deltas, reasoning, tool calls, usage) and completes after `[DONE]`.
     * Connect-stage failures retry with exponential backoff (up to [retries]);
     * failures after the first event are surfaced as [CloudException] so
     * partial output is preserved by the caller.
     *
     * The blocking socket reads run on [Dispatchers.IO] (`flowOn`), so when
     * collected from the main dispatcher individual deltas still reach the
     * collector without stalling the UI thread — otherwise every token would
     * queue up and the whole answer would appear only at the end.
     */
    fun streamChat(
        provider: CloudProvider,
        apiKey: String?,
        request: CloudChatRequest,
        retries: Int = 3,
        overrides: CloudModelOverrides? = null
    ): Flow<CloudStreamEvent> = flow {
        val body = request.copy(stream = true)
        val url = chatUrl(provider, overrides)
        val headers = streamHeaders(provider, apiKey, overrides)
        var attempt = 0
        var receivedFirstEvent = false

        while (true) {
            try {
                attempt++
                val response = api.streamChat(url, headers, body)
                if (!response.isSuccessful) {
                    val errorBody = response.errorBody()?.string()
                    throw CloudException(
                        statusMessage(provider, response.code(), errorBody),
                        statusCode = response.code()
                    )
                }
                val bodySource = response.body()?.source()
                    ?: throw CloudException("Empty stream body")
                StreamingParser.consumeLines(
                    lineProvider = { bodySource.readUtf8Line() }
                ) { payload ->
                    when (val parsed = StreamingParser.parsePayload(payload)) {
                        is StreamingParser.Parsed.Content -> {
                            receivedFirstEvent = true
                            emit(CloudStreamEvent.Delta(parsed.text))
                            StreamingParser.Parsed.Ignored
                        }
                        is StreamingParser.Parsed.Reasoning -> {
                            receivedFirstEvent = true
                            emit(CloudStreamEvent.Reasoning(parsed.text))
                            StreamingParser.Parsed.Ignored
                        }
                        is StreamingParser.Parsed.ToolCall -> {
                            receivedFirstEvent = true
                            emit(
                                CloudStreamEvent.ToolCallDelta(
                                    index = parsed.index,
                                    id = parsed.id,
                                    name = parsed.name,
                                    arguments = parsed.arguments
                                )
                            )
                            StreamingParser.Parsed.Ignored
                        }
                        is StreamingParser.Parsed.UsageInfo -> {
                            emit(
                                CloudStreamEvent.Usage(
                                    promptTokens = parsed.usage.prompt_tokens,
                                    completionTokens = parsed.usage.completion_tokens,
                                    totalTokens = parsed.usage.total_tokens
                                )
                            )
                            StreamingParser.Parsed.Ignored
                        }
                        is StreamingParser.Parsed.Finish -> {
                            // Surface the provider's terminal signal — `stop`
                            // ends the turn normally, `length` means the output
                            // hit the token ceiling and the caller must request
                            // a continuation instead of accepting a truncated
                            // answer.
                            parsed.usage?.let {
                                emit(
                                    CloudStreamEvent.Usage(
                                        promptTokens = it.prompt_tokens,
                                        completionTokens = it.completion_tokens,
                                        totalTokens = it.total_tokens
                                    )
                                )
                            }
                            emit(CloudStreamEvent.Finish(parsed.reason))
                            StreamingParser.Parsed.Ignored
                        }
                        StreamingParser.Parsed.Done -> {
                            emit(CloudStreamEvent.Done)
                            StreamingParser.Parsed.Done
                        }
                        StreamingParser.Parsed.Ignored -> StreamingParser.Parsed.Ignored
                    }
                }
                return@flow
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val retryable = !receivedFirstEvent &&
                    attempt <= retries &&
                    isRetryable(e)
                if (!retryable) {
                    // Mid-stream failures (after the first event) must surface
                    // as CloudException per the documented contract.
                    throw if (e is CloudException) e else CloudException("Stream failed: ${e.message}", e)
                }
                delay(retryPolicy.delayMsForAttempt(attempt))
            }
        }
    }.flowOn(Dispatchers.IO)

    /** Non-streaming chat completion. */
    suspend fun chat(
        provider: CloudProvider,
        apiKey: String?,
        request: CloudChatRequest,
        retries: Int = 3,
        overrides: CloudModelOverrides? = null
    ): CloudChatResponse = withRetries(retries, "chat") {
        val response = api.chat(chatUrl(provider, overrides), buildHeaders(provider, apiKey, overrides), request)
        if (!response.isSuccessful) {
            throw CloudException(
                statusMessage(provider, response.code(), response.errorBody()?.string()),
                statusCode = response.code()
            )
        }
        response.body() ?: throw CloudException("Empty chat response")
    }

    /** Text embeddings through the proxy. */
    suspend fun embeddings(
        provider: CloudProvider,
        apiKey: String?,
        model: String,
        inputs: List<String>,
        retries: Int = 3,
        overrides: CloudModelOverrides? = null
    ): CloudEmbeddingResponse = withRetries(retries, "embeddings") {
        val response = api.embeddings(
            v1Url(provider, overrides, "embeddings"),
            buildHeaders(provider, apiKey, overrides),
            CloudEmbeddingRequest(model = model, input = inputs)
        )
        if (!response.isSuccessful) {
            throw CloudException(
                statusMessage(provider, response.code(), response.errorBody()?.string()),
                statusCode = response.code()
            )
        }
        response.body() ?: throw CloudException("Empty embeddings response")
    }

    /** Model discovery: /v1/models. */
    suspend fun listModels(
        provider: CloudProvider,
        apiKey: String?,
        retries: Int = 3
    ): List<CloudModelInfo> = listModelsWithQuota(provider, apiKey, retries).first

    /**
     * Model discovery + rate-limit headers captured from the response, so the
     * provider manager can surface quota/rate-limit state in the UI.
     */
    suspend fun listModelsWithQuota(
        provider: CloudProvider,
        apiKey: String?,
        retries: Int = 3
    ): Pair<List<CloudModelInfo>, CloudQuota> = withRetries(retries, "models") {
        val response = api.listModels(v1Url(provider, null, "models"), buildHeaders(provider, apiKey, null))
        if (!response.isSuccessful) {
            throw CloudException(
                statusMessage(provider, response.code(), response.errorBody()?.string()),
                statusCode = response.code()
            )
        }
        val models = response.body()?.data.orEmpty()
        models to quotaFrom(response.headers())
    }

    /**
     * Rich model metadata from `/v1/model/info`: maps each model id to its
     * effective context window and its maximum output tokens (when the proxy
     * reports them). The max-output map lets callers size `max_tokens` per
     * provider instead of an artificial ceiling.
     */
    suspend fun listModelMetadata(
        provider: CloudProvider,
        apiKey: String?,
        retries: Int = 3
    ): ModelMetadata = withRetries(retries, "model info") {
        val response = api.modelInfo(v1Url(provider, null, "model/info"), buildHeaders(provider, apiKey, null))
        if (!response.isSuccessful) {
            throw CloudException(
                statusMessage(provider, response.code(), response.errorBody()?.string()),
                statusCode = response.code()
            )
        }
        val entries = response.body()?.data.orEmpty()
        ModelMetadata(
            contextWindows = entries.mapNotNull { entry ->
                entry.info?.effectiveContextWindow?.let { entry.modelName to it }
            }.toMap(),
            maxOutputTokens = entries.mapNotNull { entry ->
                entry.info?.maxOutputTokens?.let { entry.modelName to it }
            }.toMap()
        )
    }

    /**
     * Liveliness + readiness probes (these are NOT under /v1).
     *
     * OpenAI-compatible routers that lack LiteLLM's health-probe endpoints
     * answer 404 — that is a *reachable server*, not a dead one, so such
     * proxies report `supportsHealthEndpoints = false` and callers should
     * fall back to `/v1/models` as the connectivity proof.
     */
    suspend fun health(
        provider: CloudProvider,
        apiKey: String?,
        retries: Int = 1
    ): CloudHealth {
        val started = System.nanoTime()
        val liveliness = probe(provider, apiKey, "health/liveliness", retries)
        val readiness = probe(provider, apiKey, "health/readiness", retries)
        val latencyMs = (System.nanoTime() - started) / 1_000_000
        val reachable = liveliness != null || readiness != null
        val alive = liveliness?.let { it in 200..299 } == true
        val ready = readiness?.let { it in 200..299 } == true
        val supportsHealthEndpoints = liveliness?.let { it != 404 && it != 405 } == true ||
            readiness?.let { it != 404 && it != 405 } == true
        return CloudHealth(
            reachable = reachable,
            alive = alive,
            ready = ready,
            latencyMs = latencyMs,
            supportsHealthEndpoints = supportsHealthEndpoints
        )
    }

    /** Captures rate-limit headers from a Retrofit response, when present. */
    private fun quotaFrom(headers: Headers): CloudQuota = CloudQuota(
        remainingRequests = headers["x-ratelimit-remaining-requests"]?.toLongOrNull(),
        remainingTokens = headers["x-ratelimit-remaining-tokens"]?.toLongOrNull(),
        retryAfterSec = headers["retry-after"]?.toLongOrNull() ?: 0,
        lastStatus = 200
    )

    /**
     * Probes a health path. Returns the HTTP status code when the server
     * answered (any status — 404 included), or null when the transport
     * failed (unreachable host, refused connection, timeout).
     */
    private suspend fun probe(
        provider: CloudProvider,
        apiKey: String?,
        path: String,
        retries: Int
    ): Int? {
        var attempt = 0
        while (true) {
            try {
                val response = api.probe(healthUrl(provider, path), buildHeaders(provider, apiKey, null))
                return response.code()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (++attempt > retries) return null
                delay(retryPolicy.delayMsForAttempt(attempt))
            }
        }
    }

    /**
     * Retryable when the transport failed (connect reset/timeout) or the
     * proxy answered 429/408/5xx — those recover with backoff.
     */
    private fun isRetryable(e: Exception): Boolean = when {
        e is IOException -> true
        e is CloudException ->
            e.statusCode == 408 ||
                e.statusCode == 429 ||
                (e.statusCode?.let { it in 500..599 } == true)
        else -> false
    }

    private suspend fun <T> withRetries(
        retries: Int,
        operation: String,
        block: suspend () -> T
    ): T {
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val retryable = attempt < retries && isRetryable(e)
                if (!retryable) {
                    throw if (e is CloudException) e else CloudException("$operation failed: ${e.message}", e)
                }
                attempt++
                delay(retryPolicy.delayMsForAttempt(attempt))
            }
        }
    }

    private fun statusMessage(provider: CloudProvider, status: Int, body: String?): String {
        val detail = body
            ?.let { runCatching { json.decodeFromString<kotlinx.serialization.json.JsonObject>(it)["error"]?.toString() }.getOrNull() }
            ?.take(160)
            ?.let { " — $it" }
            ?: ""
        return when (status) {
            401, 403 -> "Authentication failed (check the API key)"
            404 -> "Not found — is this a LiteLLM proxy? (${provider.baseUrl})$detail"
            429 -> "Rate limit exceeded — try again later$detail"
            in 500..599 -> "LiteLLM server error ($status)$detail"
            else -> "HTTP $status$detail"
        }
    }

    private fun chatUrl(provider: CloudProvider, overrides: CloudModelOverrides?) = v1Url(provider, overrides, "chat/completions")

    private fun v1Url(provider: CloudProvider, overrides: CloudModelOverrides?, path: String): String {
        val base = (overrides?.apiBaseUrl ?: provider.baseUrl).trim().trimEnd('/')
        val prefix = if (base.endsWith("/v1")) "" else "/v1"
        return "$base$prefix/$path"
    }

    private fun healthUrl(provider: CloudProvider, path: String): String {
        val base = provider.baseUrl.trim().trimEnd('/')
        return "$base/$path"
    }

    private fun buildHeaders(
        provider: CloudProvider,
        apiKey: String?,
        overrides: CloudModelOverrides?
    ): Map<String, String> {
        val headers = HashMap<String, String>()
        headers.putAll(provider.extraHeaders)
        overrides?.extraHeaders?.forEach { (key, value) -> headers[key] = value }
        val key = overrides?.apiKey ?: apiKey
        val headerName = overrides?.apiKeyHeader ?: provider.apiKeyHeader
        if (!key.isNullOrBlank()) {
            headers[headerName] = if (headerName.equals("Authorization", ignoreCase = true)) "Bearer $key" else key
        }
        return headers
    }

    /**
     * Headers for the SSE streaming call. On top of the auth headers this
     * asks for an event stream, forbids caching, and disables proxy-side
     * response buffering (nginx/HAProxy flush SSE events as they arrive when
     * `X-Accel-Buffering: no` is present, instead of delivering the whole
     * body in one burst).
     */
    private fun streamHeaders(
        provider: CloudProvider,
        apiKey: String?,
        overrides: CloudModelOverrides?
    ): Map<String, String> {
        val headers = buildHeaders(provider, apiKey, overrides).toMutableMap()
        if ("Accept" !in headers) headers["Accept"] = "text/event-stream"
        if ("Cache-Control" !in headers) headers["Cache-Control"] = "no-cache"
        if ("X-Accel-Buffering" !in headers) headers["X-Accel-Buffering"] = "no"
        return headers
    }

    /**
     * Dispatches OkHttp's shared executor shut-down. Only called at
     * application shutdown; the singleton must not be used afterwards.
     */
    fun close() {
        CloudHttpClientFactory.okHttpClient().dispatcher.executorService.shutdown()
    }
}
