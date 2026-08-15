# Networking Guide

HTTP client architecture, networking layers, and API integration in AndroLLM.

---

## Two HTTP Stacks

AndroLLM uses two separate HTTP client stacks for different purposes:

| Stack | Library | Purpose | Timeout |
|---|---|---|---|
| **Cloud/LiteLLM** | Retrofit + OkHttp | AI provider API calls, SSE streaming | Connect: 10s, Read: 120s, Write: 120s |
| **General HTTP** | Ktor Client | HuggingFace API, model downloads, catalog refresh | Connect: 10s, Request: 30s, Socket: 30s |

---

## Stack 1: Cloud/LiteLLM (Retrofit + OkHttp)

### Client Factory

**File:** [`core/cloud/src/main/java/io/androllm/core/cloud/network/CloudHttpClientFactory.kt`](../../core/cloud/src/main/java/io/androllm/core/cloud/network/CloudHttpClientFactory.kt)

```kotlin
object CloudHttpClientFactory {
    fun createClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)    // Long for SSE streams
            .writeTimeout(120, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            .connectionPool(ConnectionPool(maxIdleConnections = 5, keepAliveDuration = 30, TimeUnit.MINUTES))
            .addInterceptor(LoggingInterceptor())  // Debug logging
            .build()
    }
}
```

### Retrofit API Interface

**File:** [`core/cloud/src/main/java/io/androllm/core/cloud/network/LiteLLMApi.kt`](../../core/cloud/src/main/java/io/androllm/core/cloud/network/LiteLLMApi.kt)

```kotlin
interface LiteLLMApi {
    @POST
    @Streaming
    suspend fun streamChat(
        @Url url: String,
        @HeaderMap headers: Map<String, String>,
        @Body body: JsonElement
    ): Response<ResponseBody>

    @POST
    suspend fun chat(
        @Url url: String,
        @HeaderMap headers: Map<String, String>,
        @Body body: JsonElement
    ): Response<JsonElement>

    @POST
    suspend fun embeddings(
        @Url url: String,
        @HeaderMap headers: Map<String, String>,
        @Body body: JsonElement
    ): Response<JsonElement>

    @GET
    suspend fun listModels(
        @Url url: String,
        @HeaderMap headers: Map<String, String>
    ): Response<JsonElement>

    @GET
    suspend fun modelInfo(
        @Url url: String,
        @HeaderMap headers: Map<String, String>
    ): Response<JsonElement>

    @GET
    suspend fun probe(
        @Url url: String,
        @HeaderMap headers: Map<String, String>
    ): Response<JsonElement>
}
```

### LiteLLMClient Wrapper

**File:** [`core/cloud/src/main/java/io/androllm/core/cloud/network/LiteLLMClient.kt`](../../core/cloud/src/main/java/io/androllm/core/cloud/network/LiteLLMClient.kt)

Handles request building, header injection, retry policy, and SSE parsing:

```kotlin
class LiteLLMClient {
    private val retrofit = Retrofit.Builder()
        .baseUrl("http://litellm.invalid/")  // Placeholder; real URLs per-request
        .client(CloudHttpClientFactory.createClient())
        .addConverterFactory(json.asConverterFactory("application/json"))
        .build()
    
    private val api = retrofit.create(LiteLLMApi::class.java)

    suspend fun streamChat(
        baseUrl: String, apiKey: String, model: String,
        messages: List<ChatMessage>, config: GenerationConfig, retries: Int
    ): Flow<CloudStreamEvent> {
        // Build OpenAI-compatible request body
        // Add Authorization header
        // Add X-Accel-Buffering: no header
        // Call api.streamChat()
        // Parse SSE via StreamingParser
        // Apply RetryPolicy on failure
    }
}
```

### SSE Streaming Parser

**File:** [`core/cloud/src/main/java/io/androllm/core/cloud/network/StreamingParser.kt`](../../core/cloud/src/main/java/io/androllm/core/cloud/network/StreamingParser.kt)

Pure JVM implementation that consumes SSE lines:

```kotlin
object StreamingParser {
    data class Parsed(val type: Type, val data: String) {
        enum class Type { DATA, EVENT, ID, RETRY, COMMENT, DONE, ERROR }
    }

    suspend fun consumeLines(
        lineProvider: suspend () -> String?,
        onPayload: (String) -> Unit
    ): Boolean  // true = stream complete, false = error

    fun parsePayload(payload: String): Parsed
    // Handles: multi-line data:, comments (--), event/id/retry fields
}
```

### Retry Policy

**File:** [`core/cloud/src/main/java/io/androllm/core/cloud/network/RetryPolicy.kt`](../../core/cloud/src/main/java/io/androllm/core/cloud/network/RetryPolicy.kt)

```kotlin
object RetryPolicy {
    // Retries on: IOException, 408 Request Timeout, 429 Too Many Requests, 5xx
    // Exponential backoff: 1s, 2s, 4s (max retryCount=3)
    // Mid-stream failures (after first event) surface immediately as CloudException
    // Preflight failures (before any event) are retried
}
```

---

## Stack 2: General HTTP (Ktor)

### Client Factory

**File:** [`core/network/src/main/java/io/androllm/core/network/HttpClientFactory.kt`](../../core/network/src/main/java/io/androllm/core/network/HttpClientFactory.kt)

```kotlin
object HttpClientFactory {
    fun createClient(): HttpClient {
        return HttpClient(Android) {
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                })
            }
            install(HttpTimeout) {
                connectTimeoutMillis = 10_000
                requestTimeoutMillis = 30_000
                socketTimeoutMillis = 30_000
            }
            install(Logging) {
                level = LogLevel.INFO
                logger = Logger.DEFAULT
            }
            expectSuccess = true
        }
    }
}
```

### HuggingFace API

**File:** [`core/network/src/main/java/io/androllm/core/network/api/HuggingFaceApi.kt`](../../core/network/src/main/java/io/androllm/core/network/api/HuggingFaceApi.kt)

```kotlin
interface HuggingFaceApi {
    @GET("api/models")
    suspend fun searchModels(
        @Query("search") query: String,
        @Query("limit") limit: Int = 20,
        @Query("sort") sort: String = "downloads",
        @Query("author") author: String = "litert-community"
    ): List<ModelDto>

    @GET("api/models/{modelId}")
    suspend fun getModelDetails(@Path("modelId") modelId: String): ModelDetailsDto

    @GET("api/models/{modelId}/refs/main")
    suspend fun getModelRefs(@Path("modelId") modelId: String): ModelRefsDto
}
```

Model downloads target **`.litertlm` files in `litert-community`
repositories** — the catalog's `downloadUrl` points directly at the container
files on HuggingFace (with ModelScope as a mirror), not at GGUF assets.

### Download Manager

**File:** [`core/network/src/main/java/io/androllm/core/network/DownloadManager.kt`](../../core/network/src/main/java/io/androllm/core/network/DownloadManager.kt)

Handles background file downloads with progress tracking:

```kotlin
class DownloadManager @Inject constructor(
    private val httpClient: HttpClient,
    private val scope: CoroutineScope
) {
    fun download(
        url: String,
        destination: File,
        onProgress: (Long, Long) -> Unit  // downloaded, total
    ): Flow<Result<DownloadResult>>
}
```

Features:
- Range request support for resume
- Progress callbacks via Kotlin Flow
- Concurrency limit (max 3 parallel downloads)
- Timeout handling

---

## Network Module (DI)

**File:** [`core/network/src/main/java/io/androllm/core/network/NetworkModule.kt`](../../core/network/src/main/java/io/androllm/core/network/NetworkModule.kt)

```kotlin
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    @Provides @Singleton
    fun provideHttpClient(): HttpClient = HttpClientFactory.createClient()
}
```

**File:** [`core/cloud/src/main/java/io/androllm/core/cloud/di/CloudModule.kt`](../../core/cloud/src/main/java/io/androllm/core/cloud/di/CloudModule.kt)

```kotlin
@Module
@InstallIn(SingletonComponent::class)
object CloudModule {
    @Provides @Singleton
    fun provideLiteLLMClient(): LiteLLMClient = LiteLLMClient()

    @Provides @Singleton
    fun provideKeyCipher(context: Context): KeyCipher = AndroidKeyCipher(context)

    @Provides @Singleton
    fun provideCloudSettingsRepository(store: CloudSettingsStore): CloudSettingsStore = store
}
```

---

## Model Downloads

### Download Flow

```
User selects model in Catalog
         │
         ▼
ModelsViewModel.downloadModel(catalogModel)
         │
         ├── Create ModelEntity (status = DOWNLOADING)
         │
         ├── DownloadManager.download(url, destination)
         │       │
         │       ├── Flow<Progress> updates UI
         │       │
         │       └── On completion:
         │               ├── LiteRtValidator.validate(file) + ModelInspector metadata read
         │               ├── Calculate SHA-256
         │               ├── Update ModelEntity (status = DOWNLOADED)
         │               └── Notify UI
         │
         └── Error handling:
                 ├── Network error → status = ERROR
                 ├── Validation failure → delete partial file
                 └── Insufficient storage → show error
```

### Worker-Based Downloads

For long-running downloads, `ModelDownloadWorker` (WorkManager) handles:
- Background execution (survives process death)
- Network connectivity requirements
- Retry on failure
- Notification progress

---

## Connectivity Checking

**File:** [`core/utils/src/main/java/io/androllm/core/utils/ConnectivityUtils.kt`](../../core/utils/src/main/java/io/androllm/core/utils/ConnectivityUtils.kt)

```kotlin
object ConnectivityUtils {
    fun isConnected(context: Context): Boolean
    fun is metered(context: Context): Boolean
    fun observeConnectivity(): Flow<Boolean>
}
```

Used by:
- Download manager (skip downloads on metered/unconnected networks)
- Cloud gateway (fail fast if no connection)
- Voice assistant (warn if cloud fallback needs internet)

---

## Security

### HTTPS Enforcement

Both HTTP clients enforce HTTPS:
- OkHttp: `HttpsURLConnection` default, certificate validation
- Ktor: `expectSuccess = true`, TLS 1.2+ required

### No Cleartext

AndroidManifest enforces:
```xml
android:usesCleartextTraffic="false"
```

### Headers

Cloud requests include:
- `Authorization: Bearer <encrypted_key>` (decrypted at request time)
- `X-Accel-Buffering: no` (disables proxy buffering for real-time SSE)
- Provider-specific headers (e.g., `anthropic-version`)

---

## Error Handling

| Error Type | Handling |
|---|---|
| Connection timeout | Retry with exponential backoff (cloud) / immediate error (downloads) |
| DNS resolution failure | Show "Network unavailable" to user |
| SSL handshake failure | Show "Connection security error" |
| 401 Unauthorized | Clear cached credentials, prompt re-authentication |
| 403 Forbidden | Show permission error |
| 404 Not Found | Show model not found error |
| 408 Request Timeout | Retry (cloud only) |
| 429 Too Many Requests | Show rate limit message, retry after delay |
| 500+ Server Error | Retry with backoff, then show service unavailable |
| Network unavailable | Queue operation, retry on reconnect |

---

## Planned Networking Features

| Feature | Status | Notes |
|---|---|---|
| HTTP/3 support | 🔮 Future | QUIC-based transport |
| Download resumption UI | 🚧 Planned | Pause/resume in downloads tab |
| Bandwidth throttling | 🔮 Future | Limit download speed on metered connections |
| Proxy support | 🔮 Future | SOCKS/HTTP proxy configuration |
