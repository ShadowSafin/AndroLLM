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
| Container validation failure | `ValidationError` | Show error dialog with file name (`LiteRtValidator` rejects non-LiteRT files) |
| Model too large for RAM | `RamInsufficientError` | Show recommended model sizes (`ModelResourceGuard` refuses load) |
| GPU delegate init failure | `GpuUnavailableError` | Auto-fallback to CPU; log diagnostic |
| Corrupted generation output | `CorruptionError` | Coherence probe + automatic recovery; increment counter |
| GPU delegate crash mid-generation | `GpuCrashError` | Re-arm model → CPU fallback |
| Context overflow ("Input token ids are too long") | `ContextOverflowError` | Auto-trim oldest turns and reseed conversation |
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
LiteRT-LM runtime (Kotlin/Java)     Kotlin Engine             ViewModel              UI
    │                           │                       │                    │
    ├─ EngineException ────────► │                       │                    │
    │                           ├─ catch + wrap ───────►│                    │
    │                           │  Result.Failure       │                    │
    │                           │                       ├─ collect ─────────►│
    │                           │                       │   Error screen     │
```

### Example: Model Loading Error

```kotlin
// LiteRtLmEngine
override suspend fun loadModel(model: Model, config: ModelLoadConfig): Result<EngineModelInfo> =
    runCatching {
        val container = ContainerMetadataReader.read(model.filePath)
        val family = ModelFamilyRegistry.resolve(container)
        engine = createEngine(container, family, config)
        // ...
        Result.success(buildModelInfo())
    }.mapFailure { error ->
        when (error) {
            is ModelCompatibilityException -> ValidationError(error.message)
            is ModelResourceGuard.ResourceRefused -> RamInsufficientError(error.message)
            is GpuDelegateException -> GpuUnavailableError(error.message)
            is EngineException -> EngineError(error.message)
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

// In the engine's RuntimeLogger (logcat tag: AndroLLM-Engine)
RuntimeLogger.tag("AndroLLM-Engine").w("Engine warning: $message")
```

Log tags used:
- `AndroLLM-Engine` — inference engine errors (via `RuntimeLogger`)
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
| `GPU delegate failure (OpenCL)` | "GPU error — switching to CPU mode. This may be slower." |
| `LiteRtValidator: invalid container header` | "This file doesn't appear to be a valid LiteRT model (.litertlm). Please download a proper model file." |
| `RAM insufficient: need 8GB, have 6GB` | "This model requires more memory than available. Try a smaller model." |
| `Context overflow: input token ids are too long` | "This conversation got too long — trimming older messages and continuing." |
| `401 Unauthorized` | "API key is invalid. Please check your settings." |
| `429 Too Many Requests` | "Rate limited. Please wait a moment and try again." |
| `Network timeout` | "Connection timed out. Check your internet and try again." |
| `Permission denied: RECORD_AUDIO` | "Microphone permission is required for voice features. Enable it in Settings." |

---

## Recovery Strategies

### Automatic Recoveries

| Situation | Recovery |
|---|---|
| Corrupted generation output | Coherence probe → re-arm model → If fails, CPU fallback |
| GPU delegate crash | Re-arm model → If fails, CPU fallback |
| Context overflow | Auto-trim oldest turns and reseed conversation |
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
| Device overheating | Close app, let device cool, then retry |

---

## Testing Error Cases

### Unit Tests for Error Paths

```kotlin
@Test
fun `loadModel returns Failure when container is invalid`() = runTest {
    val file = createTempFile("bad", ".litertlm").apply { writeBytes(byteArrayOf(1, 2, 3)) }

    val result = engine.loadModel(testModel.copy(filePath = file.absolutePath), testConfig)

    assertTrue(result.isFailure)
    assertThat((result as Result.Failure).error).isInstanceOf<ValidationError>()
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

All engine calls are wrapped (the engine's Kotlin API throws `EngineException` subtypes, never crashes):
```kotlin
runCatching {
    engine.generateChatStream(messages, addAssistant, config)
}.onFailure { error ->
    RuntimeLogger.tag("AndroLLM-Engine").e(error, "Generation failed")
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
- GPU fallback count
- Retry statistics

Access: Settings → Developer Options → Logs & Diagnostics
