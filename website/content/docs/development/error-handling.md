# Error Handling Guide

Error handling patterns, conventions, and best practices across the AndroLLM codebase.

---

## Result Sealed Class

**File:** [`core/common/src/main/java/io/androllm/core/common/Result.kt`](../../core/common/src/main/java/io/androllm/core/common/Result.kt)

All async operations return `Result<T, E>` instead of throwing exceptions:

```kotlin
sealed class Result<out T, out E : Error> {
    data class Success<T>(val data: T) : Result<T, Nothing>()
    data class Failure<E>(val error: E) : Result<Nothing, E>()
    
    val isSuccess: Boolean get() = this is Success
    val isFailure: Boolean get() = this is Failure
    
    fun getOrNull(): T? = when (this) {
        is Success -> data
        is Failure -> null
    }
    
    fun getOrThrow(): T = when (this) {
        is Success -> data
        is Failure -> throw error
    }
    
    fun map<T2>(transform: (T) -> T2): Result<T2, E> = when (this) {
        is Success -> Success(transform(data))
        is Failure -> Failure(error)
    }
    
    fun flatMap<T2, E2>(transform: (T) -> Result<T2, E2>): Result<T2, E2> = when (this) {
        is Success -> transform(data)
        is Failure -> Failure(error)
    }
}
```

This eliminates try/catch blocks and makes error handling explicit throughout the codebase.

---

## UiState Sealed Interface

**File:** [`core/common/src/main/java/io/androllm/core/common/UiState.kt`](../../core/common/src/main/java/io/androllm/core/common/UiState.kt)

UI state is represented by `UiState<T>`:

```kotlin
sealed interface UiState<out T> {
    object Loading : UiState<Nothing>
    data class Success<T>(val data: T) : UiState<T>
    data class Error(val message: String, val cause: Throwable? = null) : UiState<Nothing>
    object Empty : UiState<Nothing>
}
```

### Compose Usage

```kotlin
@Composable
fun ChatScreen(viewModel: ChatViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    
    when (val s = state) {
        is UiState.Loading -> LoadingIndicator()
        is UiState.Success -> Content(s.data)
        is UiState.Error   -> ErrorMessage(s.message, s.cause)
        is UiState.Empty   -> EmptyState()
    }
}
```

---

## Error Types by Layer

### Engine Layer Errors

| Error | Type | Handling |
|---|---|---|
| GGUF validation failure | `ValidationError` | Show error dialog with file name |
| Model too large for RAM | `RamInsufficientError` | Show recommended model sizes |
| Vulkan initialization failure | `VulkanUnavailableError` | Auto-fallback to CPU; log diagnostic |
| NaN/INF logits | `CorruptionError` | Automatic recovery; increment counter |
| Device lost (Vulkan) | `DeviceLostError` | Context recreation → CPU fallback |
| OOM during load | `OutOfMemoryError` | Show "Insufficient RAM" message |
| Cancelled generation | `CancellationException` | Silent — expected behavior |

### Cloud Layer Errors

| Error | Type | Handling |
|---|---|---|
| Invalid API key | `AuthenticationError` | Prompt re-authentication |
| Rate limited | `RateLimitError` | Show estimated retry time |
| Model not found | `NotFoundError` | Show available models list |
| Provider unreachable | `ConnectionError` | Show offline indicator |
| SSE parse error | `ParsingError` | Show generation failed message |
| Network timeout | `TimeoutError` | Retry with backoff |

### Database Layer Errors

| Error | Type | Handling |
|---|---|---|
| Constraint violation | `DatabaseException` | Log and show generic error |
| Migration failure | `IllegalStateException` | Clear app data (settings offer) |
| Corrupted database | `SQLiteCorruptException` | Suggest clearing app data |

### Voice Layer Errors

| Error | Type | Handling |
|---|---|---|
| Microphone permission denied | `PermissionDeniedError` | Prompt to settings |
| AudioRecord init failure | `AudioInitError` | Show "Microphone unavailable" |
| ONNX model load failure | `ModelLoadError` | Re-download voice models |
| TTS synthesis failure | `TtsError` | Fall back to text-only response |
| Service killed by OS | `ServiceDestroyedError` | Notification shows stopped state |

---

## Error Propagation Pattern

```
Native (C++)                Kotlin Engine             ViewModel              UI
    │                           │                       │                    │
    ├─ exception ─────────────► │                       │                    │
    │                           ├─ catch + wrap ───────►│                    │
    │                           │  Result.Failure       │                    │
    │                           │                       ├─ collect ─────────►│
    │                           │                       │   Error screen     │
```

### Example: Model Loading Error

```kotlin
// LlamaCppEngine
override suspend fun loadModel(model: Model, config: ModelLoadConfig): Result<EngineModelInfo> =
    runCatching {
        val handle = LlamaJniBridge.nativeCreate(buildConfigJson(config))
        LlamaJniBridge.nativeLoadModel(handle, model.filePath, buildLoadJson(config))
        // ...
        Result.success(parseModelInfo(infoJson))
    }.mapFailure { error ->
        when (error) {
            is GGUFValidationException -> ValidationError(error.message)
            is OutOfMemoryError -> RamInsufficientError(error.message)
            is VulkanException -> VulkanUnavailableError(error.message)
            else -> EngineError(error.message)
        }
    }
```

---

## Logging Errors

All errors are logged via Timber with appropriate priority:

```kotlin
// In ViewModel
when (val result = engine.loadModel(model, config)) {
    is Result.Success -> Timber.d("Model loaded: ${result.data.name}")
    is Result.Failure -> Timber.e(result.error, "Failed to load model: ${model.name}")
}

// In native bridge
Timber.tag("Engine").e(cause, "Native engine error: $message")
Timber.tag("Vulkan").w("Vulkan warning: $reason")
```

Log tags used:
- `Engine` — inference engine errors
- `Vulkan` — GPU-related warnings and diagnostics
- `Voice` — voice pipeline errors
- `Memory` — memory system errors
- `Cloud` — provider communication errors
- `Database` — Room errors
- `Network` — HTTP errors

---

## User-Facing Error Messages

Errors shown to users should be:
1. **Actionable**: Tell the user what to do
2. **Honest**: Don't blame the user for system issues
3. **Concise**: One sentence maximum
4. **Technical details hidden**: Logs contain stack traces; UI does not

| Technical Error | User Message |
|---|---|
| `VK_ERROR_DEVICE_LOST` | "GPU error — switching to CPU mode. This may be slower." |
| `GGUF validation failed: invalid magic` | "This file doesn't appear to be a valid GGUF model. Please download a proper GGUF file." |
| `RAM insufficient: need 8GB, have 6GB` | "This model requires more memory than available. Try a smaller model." |
| `401 Unauthorized` | "API key is invalid. Please check your settings." |
| `429 Too Many Requests` | "Rate limited. Please wait a moment and try again." |
| `Network timeout` | "Connection timed out. Check your internet and try again." |
| `Permission denied: RECORD_AUDIO` | "Microphone permission is required for voice features. Enable it in Settings." |

---

## Recovery Strategies

### Automatic Recoveries

| Situation | Recovery |
|---|---|
| NaN/INF logits | Recreate context → If fails, CPU fallback |
| Vulkan device lost | Recreate context → If fails, CPU fallback |
| Network transient failure | Retry with exponential backoff (3 attempts) |
| Database constraint violation | Log and skip (non-fatal) |
| Voice model not found | Auto-re-download from HuggingFace |
| Embedding provider unavailable | Fall back to keyword-only retrieval |

### Manual Recoveries (User Action Required)

| Situation | User Action |
|---|---|
| API key invalid | Re-enter key in Cloud Providers settings |
| Model corrupted | Re-download from catalog |
| Database corrupted | Clear app data (Settings → Storage) |
| Permission denied | Grant permission in Android Settings |
| Storage full | Free space, then retry |
| Device过热 | Close app, let device cool, then retry |

---

## Testing Error Cases

### Unit Tests for Error Paths

```kotlin
@Test
fun `loadModel returns Failure when GGUF is invalid`() = runTest {
    coEvery { mockGgufValidator.validate(any()) } returns 
        GgufValidationResult(isValid = false, error = "Bad magic")
    
    val result = engine.loadModel(testModel, testConfig)
    
    assertTrue(result.isFailure)
    assertThat((result as Result.Failure).error).isInstanceOf<ValidationError>::class
}

@Test
fun `streamChat retries on 429`() = runTest {
    var callCount = 0
    coEvery { mockApi.streamChat(any(), any(), any()) } answers {
        callCount++
        if (callCount < 3) throw HttpException(Response codes 429)
        else FakeResponseBody(/* success */)
    }
    
    val events = cloudGateway.streamChat(...).toList()
    
    assertThat(callCount).isEqualTo(3)
    assertTrue(events.last() is CloudStreamEvent.Done)
}
```

### Instrumented Tests

```kotlin
@AndroidTest
class EngineStressInstrumentedTest {
    @Test
    fun `rapid reload unloads previous model`() {
        // Load model A, immediately load model B
        // Verify model A is unloaded, model B loads successfully
    }
}
```

---

## Crash Prevention

### Never Crash the Main Thread

All JNI calls are wrapped:
```kotlin
runCatching {
    LlamaJniBridge.nativeGenerateChat(handle, msgsJson, addAssistant, cfgJson, callback)
}.onFailure { error ->
    Timber.e(error, "Native generation failed")
    emit(Result.failure(EngineError(error.message)))
}
```

### Database Operations

All DAO operations are `suspend` functions running on Room's background thread pool. No main-thread database access.

### Coroutine Scopes

- ViewModels use `viewModelScope` — cancelled when ViewModel is cleared
- Services use `serviceScope` — cancelled on `onDestroy()`
- Background workers use `coroutineScope` with proper supervisor handling

---

## Error Dashboard

The Developer screen includes an error dashboard showing:
- Total errors by category
- Last error timestamp and message
- Corruption recovery count
- Vulkan device lost count
- Retry statistics

Access: Settings → Developer Options → Logs & Diagnostics
