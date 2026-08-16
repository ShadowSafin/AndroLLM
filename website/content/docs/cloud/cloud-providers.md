# Cloud Providers Guide

Architecture and usage guide for cloud AI providers in AndroLLM.

---

## Overview

AndroLLM supports connecting to cloud AI providers through a **LiteLLM-compatible proxy**. Any provider that implements the OpenAI/chat/completions API format is supported — including Google Gemini, Anthropic Claude, OpenAI GPT, xAI Grok, Meta Llama, Mistral, and self-hosted LiteLLM instances.

Cloud features are **optional and opt-in**. The app works fully offline without any cloud configuration.

---

Cloud models also support **Chat Attachments** (conversation-scoped file
uploads — PDF, Office, text, images/OCR). Attachments are a cloud-only
capability; local models never expose them. See
[Chat Attachments](../features/chat-attachments.md).
## Provider Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                        Cloud Gateway                             │
│               (singleton facade — chat facade)                   │
├─────────────────────────────────────────────────────────────────┤
│                     ProviderManager                              │
│   ┌──────────┐  ┌───────────┐  ┌─────────────┐  ┌──────────┐  │
│   │ CRUD     │  │ Secrets   │  │ Health      │  │Discovery │  │
│   │ ops      │  │ (KeyCipher│  │ Monitor     │  │ (/v1/    │  │
│   │          │  │  AES-256) │  │             │  │ models)  │  │
│   └──────────┘  └───────────┘  └─────────────┘  └──────────┘  │
├─────────────────────────────────────────────────────────────────┤
│                      LiteLLMClient                              │
│   ┌──────────────────────────────────────────────────────┐     │
│   │  Retrofit + OkHttp                                    │     │
│   │  - streamChat (SSE)                                   │     │
│   │  - chat (non-streaming)                               │     │
│   │  - embeddings                                         │     │
│   │  - listModels, modelInfo, probe                       │     │
│   │                                                       │     │
│   │  RetryPolicy: exponential backoff on 408/429/5xx     │     │
│   │  StreamingParser: pure-JVM SSE parser                │     │
│   └──────────────────────────────────────────────────────┘     │
└─────────────────────────────────────────────────────────────────┘
```

---

## CloudSettings Data Model

```kotlin
data class CloudSettings(
    val enabled: Boolean,              // Master cloud mode toggle
    val defaultProviderId: String,     // Currently active provider
    val defaultModelId: String,        // Currently active model
    val favoriteModelIds: Set<String>, // User-favorited models
    val providers: List<CloudProvider>,
    val retryCount: Int = 3,           // Max retry attempts
    val providerSettings: ProviderSettings
)
```

---

## CloudProvider Data Model

```kotlin
data class CloudProvider(
    val id: String,                      // Unique identifier
    val name: String,                    // Display name
    val baseUrl: String,                 // e.g., "https://api.example.com"
    val apiKeyEncrypted: String,         // Keystore-encrypted API key
    val apiKeyHeader: String = "Authorization",  // Header name
    val extraHeaders: Map<String, String> = emptyMap(),
    val customModels: List<CloudCustomModel> = emptyList(),
    val modelIds: List<String> = emptyList(),  // Discovered from /v1/models
    val modelContextWindows: Map<String, Long> = emptyMap(),
    val enabled: Boolean = true,
    val lastCheckedAt: Long? = null,
    val latencyMs: Long? = null,
    val lastError: String? = null,
    val quota: ProviderQuota? = null
)
```

---

## Key Cipher (API Key Encryption)

All API keys are encrypted before storage using [`KeyCipher`](../../core/cloud/src/main/java/io/androllm/core/cloud/security/KeyCipher.kt):

```kotlin
class AndroidKeyCipher @Inject constructor(context: Context) : KeyCipher {
    private val keyAlias = "androllm_cloud_api_keys"

    override fun encrypt(plaintext: String): String {
        // 1. Generate random 12-byte IV
        // 2. AES-256/GCM encrypt with Keystore-backed key
        // 3. Prepend IV to ciphertext
        // 4. Base64 encode result
        // Raw key NEVER leaves Android Keystore
    }

    override fun decrypt(ciphertext: String): String {
        // 1. Base64 decode
        // 2. Split IV (first 12 bytes) from ciphertext
        // 3. AES-256/GCM decrypt using Keystore key
        // Empty input → empty output (identity behavior)
    }

    override fun delete() {
        // Remove the Keystore key alias
    }
}
```

**Security properties:**
- Keys are encrypted with AES-256 in GCM mode
- The encryption key is stored in the Android Keystore (hardware-backed on supported devices)
- Plaintext keys exist only in memory during API requests
- Key deletion removes the Keystore entry entirely

---

## Provider Management

### Adding a Provider

Via UI: Settings → Cloud Providers → Add Provider

Required fields:
- **Name**: Display name (e.g., "My LiteLLM", "OpenAI")
- **Base URL**: Full URL to the LiteLLM proxy (e.g., `https://litellm.example.com`)
- **API Key**: Your API key (encrypted before storage)

Optional fields:
- **API Key Header**: Defaults to `Authorization`; some providers use `X-API-Key`
- **Extra Headers**: Additional headers (e.g., `anthropic-version`, custom auth)
- **Custom Models**: Override model IDs for specific models

### Discovering Models

Once a provider is added, AndroLLM can discover available models:

1. **Automatic**: On provider creation/update, calls `/v1/models` endpoint
2. **Manual**: Settings → Cloud Providers → select provider → Refresh models

Discovered data includes:
- Model IDs (used for API calls)
- Context window lengths (from `/v1/model/info` if available)

### Health Monitoring

`ProviderHealthMonitor` periodically probes each provider:

```kotlin
suspend fun testConnection(provider: CloudProvider): ProviderHealthResult {
    // 1. Try GET {baseUrl}/health/liveliness
    // 2. Fallback to GET {baseUrl}/v1/models
    // Record: latencyMs, lastError, isConnected
}
```

Results are shown in the Cloud Providers screen with green/yellow/red status indicators.

---

## Streaming Chat

### Flow

```kotlin
// CloudGateway
fun streamChat(
    messages: List<ChatMessage>,
    config: GenerationConfig,
    retries: Int = 3,
    modelId: String? = null
): Flow<CloudStreamEvent>
```

### SSE Parsing

The `StreamingParser` object handles raw SSE streams from the provider:

```kotlin
object StreamingParser {
    suspend fun consumeLines(
        lineProvider: suspend () -> String?,
        onPayload: (String) -> Unit
    ): Boolean  // true = done, false = error/cancel

    fun parsePayload(payload: String): Parsed
    // Handles: multi-line data:, comments, event/id/retry fields
}
```

### CloudStreamEvent Sealed Interface

```kotlin
sealed interface CloudStreamEvent {
    data class Delta(val text: String) : CloudStreamEvent        // Content token
    data class Reasoning(val text: String) : CloudStreamEvent   // Thinking/reasoning content
    data class ToolCallDelta(
        val index: Int, val id: String, val name: String,
        val arguments: String
    ) : CloudStreamEvent  // Function call fragment
    data class Usage(
        val promptTokens: Int, val completionTokens: Int,
        val totalTokens: Int
    ) : CloudStreamEvent  // Token usage
    object Done : CloudStreamEvent                               // Stream complete
}
```

### Retry Policy

```kotlin
object RetryPolicy {
    // Retries on: IOException, 408 Request Timeout, 429 Too Many Requests, 5xx
    // Exponential backoff: 1s, 2s, 4s (up to retryCount=3)
    // Mid-stream failures (after first event) surface immediately as CloudException
}
```

---

## Chat Message Serialization

Cloud providers expect messages in their own format. The `CloudChatMessageSerializer` converts AndroLLM's internal `ChatPromptMessage` to provider-specific formats:

```kotlin
// Internal format
data class ChatPromptMessage(
    val role: MessageRole,    // USER, ASSISTANT, SYSTEM
    val content: String
)

// Serialized for cloud (OpenAI-compatible)
[
  {"role": "system", "content": "You are a helpful assistant."},
  {"role": "user", "content": "Hello"},
  {"role": "assistant", "content": "Hi there!"}
]
```

Memory context is injected as a `system` message prefix when retrieval finds relevant memories.

---

## Custom Providers

### Self-Hosted LiteLLM

Set up your own LiteLLM proxy:
```bash
pip install litellm[proxy]
litellm --model ollama/llama3 / openai/gpt-4 / anthropic/claude-3-opus
```

Then configure in AndroLLM:
- Base URL: `http://your-server:4000`
- API Key: Your LiteLLM proxy key

### Direct Provider APIs

Some providers expose OpenAI-compatible endpoints directly:

| Provider | Base URL | Notes |
|---|---|---|
| OpenAI | `https://api.openai.com` | Standard |
| Anthropic (via LiteLLM) | `https://litellm.pro安东尼anthropic.com` | Use LiteLLM proxy |
| Google Gemini (via LiteLLM) | Same as above | Use LiteLLM proxy |
| xAI Grok | `https://api.x.ai` | OpenAI-compatible |
| Mistral | `https://api.mistral.ai` | OpenAI-compatible |

---

## Switching Providers

Users can switch between providers at any time:

1. Settings → Cloud Providers → select active provider
2. Or: Chat screen → model selector → choose cloud model
3. The `CloudGateway` resolves the active provider+model pair for each request

The selection is persisted in `CloudSettings.defaultProviderId`.

---

## Error Handling

| Error | Cause | Handling |
|---|---|---|
| `401 Unauthorized` | Invalid/expired API key | Show error; do not retry |
| `403 Forbidden` | Insufficient permissions | Show error; do not retry |
| `404 Not Found` | Invalid model ID | Show error; suggest checking model list |
| `408 Request Timeout` | Provider slow to respond | Retry with backoff |
| `429 Too Many Requests` | Rate limit hit | Retry with backoff; show rate limit info |
| `500+ Server Error` | Provider outage | Retry with backoff; fail after max retries |
| `IOException` | Network failure | Retry with backoff |
| SSE parse error | Malformed response | Surface as `CloudException`; do not retry |

---

## Provider Health Monitor

Runs periodic health checks on configured providers:

```kotlin
@Singleton
class ProviderHealthMonitor @Inject constructor(
    private val manager: ProviderManager
) {
    // Checks every 5 minutes when cloud mode is enabled
    // Updates lastCheckedAt, latencyMs, lastError on each provider
    // Triggers alerts for providers that fail consecutive checks
}
```

Health status is displayed in the Cloud Providers screen. Failed providers are automatically deprioritized in provider selection.

---

## Security Considerations

1. **API keys are never logged** — Timber tags avoid leaking `apiKeyEncrypted` values
2. **HTTPS enforced** — OkHttp client rejects cleartext connections
3. **Key rotation** — Deleting a provider removes its encrypted key from Keystore
4. **No shared keys** — Each provider has its own独立 API key
5. **Guest mode safe** — Cloud features are disabled when not authenticated

---

## Planned Cloud Features

| Feature | Status | Notes |
|---|---|---|
| Provider-specific settings presets | 🚧 Planned | One-click setup for common providers |
| Usage tracking and cost estimation | 🚧 Planned | Per-provider token usage dashboard |
| Multi-provider fallback chaining | 🚧 Planned | Auto-failover between providers |
| Cloud memory embeddings | ✅ Implemented | Via `CloudEmbeddingProvider` |
| Provider-specific model prompts | 🔮 Future | Tuned system prompts per provider |
