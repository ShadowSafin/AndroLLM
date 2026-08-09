package io.androllm.core.cloud.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * A configured LiteLLM proxy connection.
 *
 * API keys are never stored in plain text: [apiKeyEncrypted] holds the
 * Keystore-encrypted blob (base64 IV + AES/GCM ciphertext) produced by
 * [io.androllm.core.cloud.security.KeyCipher]. The same applies to keys
 * inside [customModels].
 */
@Serializable
data class CloudProvider(
    val id: String = "",
    val name: String = "",
    val baseUrl: String = "",
    val apiKeyEncrypted: String = "",
    /** Custom auth header name; defaults to "Authorization" (Bearer). */
    val apiKeyHeader: String = "Authorization",
    /** Optional extra headers forwarded on every request. */
    val extraHeaders: Map<String, String> = emptyMap(),
    val description: String = "",
    val tags: List<String> = emptyList(),
    /** User-defined models with optional per-model LiteLLM server/key/headers. */
    val customModels: List<CloudCustomModel> = emptyList(),
    /** Cached model ids discovered from /v1/models. */
    val modelIds: List<String> = emptyList(),
    /** Best-effort context-window metadata from /v1/model/info (model id → tokens). */
    val modelContextWindows: Map<String, Long> = emptyMap(),
    val enabled: Boolean = true,
    val isDefault: Boolean = false,
    val lastCheckedAt: Long = 0,
    val latencyMs: Long = 0,
    val lastError: String = "",
    val quota: CloudQuota? = null
)

/**
 * A user-defined model routed through a LiteLLM proxy. Every field except
 * [modelId] is optional; when [apiBaseUrl]/[apiKeyEncrypted]/[extraHeaders]
 * are set they override the owning provider's values for this model only.
 */
@Serializable
data class CloudCustomModel(
    val id: String = "",
    /** Display name shown in the UI ("Model Name"). */
    val modelName: String = "",
    /** LiteLLM model identifier sent in requests (e.g. "openai/gpt-4o"). */
    val modelId: String = "",
    /** Optional alternate LiteLLM server for this model (self-hosted setups). */
    val apiBaseUrl: String? = null,
    /** Keystore-encrypted optional API key for this model. */
    val apiKeyEncrypted: String = "",
    /** Custom auth header name; defaults to "Authorization" (Bearer). */
    val apiKeyHeader: String = "Authorization",
    /** Optional extra headers forwarded for this model only. */
    val extraHeaders: Map<String, String> = emptyMap(),
    val description: String = "",
    val tags: List<String> = emptyList()
)

/** Last observed rate-limit / quota state for a provider. */
@Serializable
data class CloudQuota(
    val remainingRequests: Long? = null,
    val remainingTokens: Long? = null,
    val retryAfterSec: Long = 0,
    val lastStatus: Int = 0
)

/**
 * User-tunable cloud provider behavior: health-check cadence, automatic
 * reconnects, transport timeouts and default sampling parameters used when
 * the caller does not supply its own.
 */
@Serializable
data class ProviderSettings(
    /** How often background health checks run (minutes; 0 disables them). */
    val healthCheckIntervalMinutes: Int = 15,
    /** Re-check providers automatically after a failed request. */
    val autoReconnect: Boolean = true,
    val connectTimeoutMs: Long = 10_000,
    val streamTimeoutMs: Long = 120_000,
    val defaultTemperature: Double = 0.8,
    val defaultTopP: Double = 0.95,
    val defaultTopK: Int? = null,
    // High but provider-safe ceiling; most providers cap output around 8k.
    val defaultMaxTokens: Int = 8192,
    val defaultSeed: Long? = null,
    val defaultStop: List<String> = emptyList()
)

/** Global cloud settings persisted in DataStore. */
@Serializable
data class CloudSettings(
    /** Master toggle for cloud chat mode (Local/Cloud switch in chat). */
    val enabled: Boolean = false,
    val defaultProviderId: String = "",
    val defaultModelId: String = "",
    val favoriteModelIds: Set<String> = emptySet(),
    val providers: List<CloudProvider> = emptyList(),
    /** Automatic retries for connect failures, HTTP 429 and 5xx responses. */
    val retryCount: Int = 3,
    val providerSettings: ProviderSettings = ProviderSettings()
)

/** Parameters mapped onto the OpenAI-compatible chat request body. */
data class CloudGenerationConfig(
    val temperature: Double = 0.8,
    val topP: Double = 0.95,
    val topK: Int? = null,
    // High but provider-safe ceiling; most providers cap output around 8k.
    val maxTokens: Int = 8192,
    val seed: Long? = null,
    val stop: List<String> = emptyList(),
    /** "json_object" for structured output (LiteLLM translates per provider). */
    val responseFormat: String? = null,
    /** JSON-Schema definition for `response_format.type = "json_schema"`. */
    val jsonSchema: JsonElement? = null,
    val tools: List<CloudTool> = emptyList()
)

/** A single tool/function definition passed through to the proxy. */
@Serializable
data class CloudTool(
    val type: String = "function",
    val function: CloudToolFunction
)

@Serializable
data class CloudToolFunction(
    val name: String,
    val description: String = "",
    val parameters: Map<String, JsonElement> = emptyMap()
)

/**
 * A tool call produced by the model (assistant message) or accumulated from
 * streaming deltas. `arguments` is the JSON-encoded parameter string.
 */
@Serializable
data class CloudToolCall(
    val index: Int = 0,
    val id: String? = null,
    val type: String = "function",
    val function: CloudToolCallFunction? = null
)

@Serializable
data class CloudToolCallFunction(
    val name: String? = null,
    val arguments: String? = null
)

/**
 * A multimodal content block (OpenAI-compatible `content` array element).
 * Not serializable on its own — [CloudChatMessage] (de)serializes it via
 * [CloudChatMessageSerializer].
 */
sealed class CloudContentPart {
    /** `{"type":"text","text":"..."}` */
    data class Text(val text: String) : CloudContentPart()

    /** `{"type":"image_url","image_url":{"url":"..."}}` (vision models). */
    data class Image(val url: String) : CloudContentPart()
}

/**
 * A single chat message in OpenAI format. Supports plain text (`content`),
 * multimodal content arrays ([contentParts], vision/audio), tool calls and
 * tool responses. Serialized by [CloudChatMessageSerializer] which emits the
 * exact OpenAI wire shape for whichever fields are set.
 */
@Serializable(with = CloudChatMessageSerializer::class)
data class CloudChatMessage(
    val role: String,
    val content: String? = null,
    /** Multimodal content blocks; takes precedence over [content] when set. */
    val contentParts: List<CloudContentPart>? = null,
    /** Set for `role = "tool"` messages replying to a tool call. */
    val toolCallId: String? = null,
    /** Set for `role = "assistant"` messages that invoked tools. */
    val toolCalls: List<CloudToolCall>? = null,
    /** Optional author name for `role = "function"`/`"tool"` messages. */
    val name: String? = null
) {
    companion object {
        /** Builds a vision message: text plus an image (URL or data URI). */
        fun withImage(text: String?, imageUrl: String): CloudChatMessage {
            val parts = buildList {
                if (!text.isNullOrBlank()) add(CloudContentPart.Text(text))
                add(CloudContentPart.Image(imageUrl))
            }
            return CloudChatMessage(role = "user", content = null, contentParts = parts)
        }
    }
}

/** OpenAI-compatible chat completion request body. */
@Serializable
data class CloudChatRequest(
    val model: String,
    val messages: List<CloudChatMessage>,
    val temperature: Double = 0.8,
    val top_p: Double = 0.95,
    val top_k: Int? = null,
    val max_tokens: Int = 8192,
    val seed: Long? = null,
    val stop: List<String> = emptyList(),
    val stream: Boolean = false,
    val response_format: CloudResponseFormat? = null,
    val tools: List<CloudTool> = emptyList()
)

/** `response_format`: `{"type":"json_object"}` or `{"type":"json_schema","json_schema":{...}}`. */
@Serializable
data class CloudResponseFormat(
    val type: String,
    val json_schema: JsonElement? = null
)

/** Non-streaming chat completion response. */
@Serializable
data class CloudChatResponse(
    val id: String = "",
    val model: String = "",
    val choices: List<CloudChatChoice> = emptyList(),
    val usage: CloudUsage? = null
)

@Serializable
data class CloudChatChoice(
    val index: Int = 0,
    val message: CloudChatMessage? = null,
    val finish_reason: String? = null
)

/** Streaming chunk (chat.completion.chunk). */
@Serializable
data class CloudChatChunk(
    val id: String = "",
    val model: String = "",
    val choices: List<CloudChatChunkChoice> = emptyList(),
    val usage: CloudUsage? = null
)

@Serializable
data class CloudChatChunkChoice(
    val index: Int = 0,
    val delta: CloudChatDelta? = null,
    val finish_reason: String? = null
)

@Serializable
data class CloudChatDelta(
    val role: String? = null,
    val content: String? = null,
    /** Reasoning-model thinking deltas (DeepSeek-R1, Gemini thinking, ...). */
    val reasoning_content: String? = null,
    val tool_calls: List<CloudToolCall>? = null
)

@Serializable
data class CloudUsage(
    val prompt_tokens: Long = 0,
    val completion_tokens: Long = 0,
    val total_tokens: Long = 0
)

/** /v1/models response. */
@Serializable
data class CloudModelList(
    val object_type: String? = null,
    val data: List<CloudModelInfo> = emptyList()
)

@Serializable
data class CloudModelInfo(
    val id: String,
    @SerialName("object") val object_type: String = "model",
    val created: Long = 0,
    val owned_by: String = ""
)

/** /v1/model/info response — richer per-model metadata from the proxy. */
@Serializable
data class CloudModelInfoList(
    val data: List<CloudModelInfoEntry> = emptyList()
)

@Serializable
data class CloudModelInfoEntry(
    @SerialName("model_name") val modelName: String = "",
    @SerialName("model_info") val info: CloudModelInfoDetail? = null
)

@Serializable
data class CloudModelInfoDetail(
    val id: String? = null,
    @SerialName("context_window") val contextWindow: Long? = null,
    @SerialName("max_input_tokens") val maxInputTokens: Long? = null,
    @SerialName("max_output_tokens") val maxOutputTokens: Long? = null,
    val mode: String? = null
) {
    /** Best estimate of the usable context window in tokens. */
    val effectiveContextWindow: Long? get() = maxInputTokens ?: contextWindow
}

/** /v1/embeddings request. */
@Serializable
data class CloudEmbeddingRequest(
    val model: String,
    val input: List<String>
)

/** /v1/embeddings response. */
@Serializable
data class CloudEmbeddingResponse(
    @SerialName("object") val object_type: String = "list",
    val data: List<CloudEmbeddingItem> = emptyList(),
    val model: String = "",
    val usage: CloudUsage? = null
)

@Serializable
data class CloudEmbeddingItem(
    @SerialName("object") val object_type: String = "embedding",
    val index: Int = 0,
    val embedding: List<Float> = emptyList()
)

/** /health/liveliness + /health/readiness probe results. */
data class CloudHealth(
    val reachable: Boolean,
    val alive: Boolean,
    val ready: Boolean,
    val latencyMs: Long,
    /** False when the proxy does not expose the LiteLLM health probes. */
    val supportsHealthEndpoints: Boolean = true,
    val detail: String = ""
)

/** Events emitted while consuming a streaming chat completion. */
sealed interface CloudStreamEvent {
    /** A content delta from `choices[0].delta.content`. */
    data class Delta(val text: String) : CloudStreamEvent

    /** A reasoning/thinking delta (reasoning models). */
    data class Reasoning(val text: String) : CloudStreamEvent

    /** A streaming tool-call fragment (index + optional id/name/arguments). */
    data class ToolCallDelta(
        val index: Int,
        val id: String?,
        val name: String?,
        val arguments: String
    ) : CloudStreamEvent

    /** Token usage reported on the final chunk. */
    data class Usage(
        val promptTokens: Long,
        val completionTokens: Long,
        val totalTokens: Long
    ) : CloudStreamEvent

    /** The `data: [DONE]` terminal marker. */
    data object Done : CloudStreamEvent
}

/** A model (discovered or custom) merged with its owning provider + UI state. */
data class CloudModelProvider(
    /** LiteLLM model identifier sent in requests. */
    val id: String,
    /** Owning [CloudProvider.id]. */
    val providerId: String,
    val providerName: String,
    /** Friendly display name (custom model name, else the model id). */
    val displayName: String = id,
    val isCustom: Boolean = false,
    val description: String = "",
    val tags: List<String> = emptyList(),
    val contextWindow: Long? = null,
    val isFavorite: Boolean = false,
    val isDefault: Boolean = false,
    val enabled: Boolean = true
) {
    val label: String get() = displayName.ifBlank { id }
}

/**
 * A fully resolved chat target: provider + model plus any model-scoped
 * overrides (alternate LiteLLM server, key, headers). Produced by
 * [io.androllm.core.cloud.ProviderManager.resolveChatModel].
 */
data class ResolvedCloudModel(
    val provider: CloudProvider,
    val modelId: String,
    val displayName: String,
    val isCustom: Boolean,
    /** Plaintext API key to use (provider-level or model-scoped). */
    val apiKey: String,
    val overrides: CloudModelOverrides
)

/** Transport overrides applied when calling a specific custom model. */
data class CloudModelOverrides(
    /** Alternate LiteLLM server base URL (self-hosted per-model routing). */
    val apiBaseUrl: String? = null,
    /** Model-scoped API key (plaintext at call time; never persisted here). */
    val apiKey: String? = null,
    val apiKeyHeader: String? = null,
    val extraHeaders: Map<String, String> = emptyMap()
)

/** Result of a provider connection test. */
data class ConnectionTestResult(
    val providerId: String,
    val ok: Boolean,
    val latencyMs: Long,
    val alive: Boolean,
    val ready: Boolean,
    val modelCount: Int = 0,
    val error: String = ""
)

/** Typed failure raised by the cloud layer. */
class CloudException(
    message: String,
    cause: Throwable? = null,
    /** HTTP status when the failure came from a response (null for transport errors). */
    val statusCode: Int? = null
) : Exception(message, cause)
