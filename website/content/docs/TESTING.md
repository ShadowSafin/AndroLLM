# Testing Guide

Comprehensive guide to the testing strategy, frameworks, and practices in AndroLLM.

---

## Testing Pyramid

```
        /\
       /  \      Instrumented Tests (Espresso + Compose UI)
      /----\     ~4 test classes
     /      \
    /========\    Unit Tests (JUnit 4 + mockk + Turbine + MockWebServer)
   / ~75 classes \  500+ tests across all modules
  /______________\
```

---

## Test Frameworks

| Framework | Version | Purpose |
|---|---|---|
| JUnit 4 | 4.13.2 | Test organization, assertions |
| mockk | 1.13.13 | Kotlin-first mocking (replaces Mockito) |
| Turbine | 1.0.0 | Flow assertion / testing async streams |
| kotlinx-coroutines-test | 1.8.0 | `UnconfinedTestDispatcher`, `runTest` |
| Espresso | 3.5.1 | UI interaction testing |
| Compose UI Test | 1.6.0 | Composable component testing |
| Robolectric | 4.11.1 | Android framework stubbing (declared, minimally used) |
| mockwebserver | (OkHttp) | HTTP response stubbing for network tests |
| truth | 1.1.5 | Fluent assertions |

---

## Unit Tests

### Running Unit Tests

```bash
# All unit tests
./gradlew test

# Specific module
./gradlew :feature:chat:test

# Specific test class
./gradlew :engine:test --tests "*.ContainerMetadataReaderTest"
```

### Test Conventions

#### ViewModel Tests

All ViewModel tests follow this pattern:

```kotlin
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {
    private lateinit var viewModel: ChatViewModel

    @Before
    fun setUp() {
        // Switch coroutine dispatcher to test thread
        Dispatchers.setMain(UnconfinedTestDispatcher())
        // Initialize fakes and inject into ViewModel
        viewModel = ChatViewModel(fakeEngineRepo, fakeMemoryMgr, ...)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain() // Restore main dispatcher
    }

    @Test
    fun `send message triggers generation`() = runTest {
        // Arrange
        val message = "Hello"
        // Act
        viewModel.sendMessage(message)
        // Assert using Turbine for Flow testing
        viewModel.state.test {
            val item = awaitItem()
            assertTrue(item is UiState.Success)
        }
    }
}
```

#### Repository Tests

```kotlin
class MemoryRepositoryTest {
    private lateinit var repository: MemoryRepository
    private lateinit var mockDao: MemoryDao
    private lateinit var mockEmbeddingProvider: EmbeddingProvider

    @Before
    fun setUp() {
        mockDao = mockk()
        mockEmbeddingProvider = mockk()
        repository = MemoryRepository(mockDao, mockEmbeddingProvider, ...)
    }

    @Test
    fun `retrieve returns empty list when no memories match`() = runTest {
        coEvery { mockDao.searchMemories(any(), any()) } returns emptyList()

        val result = repository.retrieve("query", emptyMap(), topK = 5)

        assertTrue(result is Result.Success)
        assertThat(result.data!!.memories).isEmpty()
    }
}
```

#### Cloud Tests

Cloud module tests use fake implementations for secrets and network:

```kotlin
class FakeKeyCipher : KeyCipher {
    override fun encrypt(plaintext: String) = "enc($plaintext)"
    override fun decrypt(ciphertext: String) = ciphertext.removePrefix("enc(").removeSuffix(")")
}

class FakeCloudSettingsRepository : CloudSettingsStore {
    private val _settings = MutableStateFlow(CloudSettings.default())
    override val settings: Flow<CloudSettings> get() = _settings
    suspend fun update(transform: (CloudSettings) -> CloudSettings) {
        _settings.value = transform(_settings.value)
    }
}
```

#### Cloud Pipeline Tests (gateway, cache, usage)

Gateway-level behavior (provider fallback, validation rejection, cache
reuse, tool-call counting) is tested end-to-end with **MockWebServer** —
see `CloudGatewayPipelineTest`. Patterns that matter:

```kotlin
// Deterministic single-attempt behavior: CloudSettings.retryCount defaults
// to 3, which would retry inside the client against an empty MockWebServer.
// Gateway pipeline tests pass retries = 0 explicitly.
gateway.streamChat(request, retries = 0)

// Primary returns 500/429 before any SSE event → gateway must replay the
// same request on the fallback provider; both attempts are usage-recorded.
```

Dashboard ViewModel tests (`CloudUsageDashboardViewModelTest`) combine:

```kotlin
@get:Rule val instantExecutor = InstantTaskExecutorRule()
@get:Rule val tempFolder = TemporaryFolder()

@Before fun setUp() {
    Dispatchers.setMain(StandardTestDispatcher())  // virtual time for viewModelScope
    ...
}

// Create the ViewModel FIRST and advanceUntilIdle() so init() loads the
// (empty) store, THEN record usage — matching production order. Recording
// before init races the meter's debounced persistence and is flaky.
val vm = createViewModel(); advanceUntilIdle()
meter.record(meter.buildRecord(...)); advanceUntilIdle()
```

Two environment gotchas encoded in these tests:

- **Never mock Android objects with deep relaxed mocks on a path that runs
  real androidx code.** `FileProvider.getUriForFile` walks
  `PackageManager` → `XmlResourceParser` metadata; a relaxed
  `XmlResourceParser` returns `0` from `next()` forever and the parse loop
  never terminates (observed as a 1+ GB heap spiral). Stub the
  `PackageManager` to fail fast instead.
- **Windows + JDK 21 + Gradle 9 worker shutdown deadlock**: after all tests
  pass, the worker JVM can hang in Gradle's `MessageHub.stop()` (socket
  select never wakes) while the daemon waits for it to exit.
  `feature:cloud` tests arm `TestWorkerShutdownWatchdog` (one daemon thread
  per worker JVM that force-exits long after the run completes) so the
  build can finish. Remove it if Gradle fixes the shutdown deadlock.

### Test Coverage by Module

| Module | Test Classes | Key Areas Tested |
|---|---|---|
| `core:common` | 1 | `Result` sealed class behavior |
| `core:cloud` | 16 | Provider manager, health monitor, streaming parser, codec, message serializer, usage meter + pricing + metrics, prompt cache + cache hints, request validator, request planner, result observer, fallback tool parser, gateway pipeline (fallback chain, cache reuse, validation) against MockWebServer |
| `core:database` | 1 | Entity mapping correctness |
| `core:datastore` | 1 | Preference key type safety |
| `core:memory` | 4 | Vector math, vector index, extraction parser, routing intelligence |
| `core:models` | 6 | Catalog parsing, validation, search, recommendations, quant classification |
| `core:navigation` | 1 | Route constant consistency |
| `core:network` | 2 | DTO serialization, HuggingFace API response parsing |
| `core:telemetry` | 1 | Telemetry history storage |
| `core:tools` | 17 | Tool hardening/validation, confirmation manager, loop guard, run coordinator, planner, prompt builder, registry, router, variable store, contact resolver, calculator, web-search parser, app search, cloud tool router + conditional execution |
| `core:utils` | 1 | Storage utility functions |
| `engine` | 20 | Engine repository + stress, compat layer (container metadata reader, chat template renderer, family registry/compatibility, stop-sequence tracker, output decoder), tool-call scanning, memory estimation, resource guard, coherence checker, thread manager, tokenizer, config serialization |
| `feature:chat` | 5 | ViewModel state management, stabilization, conversation export, history trimmer, link utils |
| `feature:cloud` | 1 | Usage dashboard ViewModel (snapshot exposure, filters, cache stats, clear/export) |
| `feature:home` | 1 | Home ViewModel |
| `feature:models` | 3 | Models ViewModel, download manager, compatibility analyzer |
| `feature:onboarding` | 1 | Onboarding ViewModel |
| `feature:profile` | 1 | Profile ViewModel |
| `feature:prompts` | 1 | Prompt library ViewModel |
| `feature:settings` | 1 | Settings ViewModel |
| `feature:splash` | 1 | Splash screen timing |
| **Total** | **~75** | |

---

## Instrumented Tests

### Running Instrumented Tests

```bash
# All instrumented tests (requires connected device or emulator)
./gradlew connectedAndroidTest

# Specific module
./gradlew :feature:chat:connectedAndroidTest

# Specific test class on a specific device
./gradlew :engine:connectedAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.example.MyTest
```

### Test Classes

| Test Class | Location | What It Tests |
|---|---|---|
| `ExampleInstrumentedTest` | `app/src/androidTest/` | Basic app launch |
| `EngineStressInstrumentedTest` | `engine/src/androidTest/` | LiteRT-LM engine lifecycle under stress (requires a model on the device) |
| `ChatScreenUiTest` | `feature/chat/src/androidTest/` | Compose UI: message bubbles, input, scrolling |
| `MigrationTest` | `core/memory/src/androidTest/` | Room database migration correctness |

### Engine Stress Test (Real Model Required)

`EngineStressInstrumentedTest` runs the real LiteRT-LM engine against a
`.litertlm` model file on the device. Provide the model path via the
instrumentation argument; the test **skips** when no model is provided:

```bash
# Push a model to the device first, e.g.:
adb push gemma3-270m-it-q8.litertlm /sdcard/Download/

# Pass the path via instrumentation argument
./gradlew :engine:connectedAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.modelPath=/sdcard/Download/gemma3-270m-it-q8.litertlm

# Or via adb directly
adb shell am instrument -w \
  -e modelPath /sdcard/Download/gemma3-270m-it-q8.litertlm \
  io.androllm.engine.test/androidx.test.runner.AndroidJUnitRunner
```

The test verifies load → generate → unload cycles, streaming correctness, and
cancel behavior on real hardware.

### Compose UI Test Example

```kotlin
@AndroidTest
class ChatScreenUiTest {
    @get:Rule
    val composeRule = ComposeRule()

    @Test
    fun `message bubble displays content correctly`() {
        composeRule.setContent {
            AndroLLMTheme {
                MessageCard(
                    message = Message(
                        id = "1",
                        role = MessageRole.USER,
                        content = "Hello world"
                    ),
                    onRetry = {}
                )
            }
        }
        composeRule.onNodeWithText("Hello world").assertIsDisplayed()
    }
}
```

---

## Testing the Engine

The engine is a pure Kotlin/Java module, so the entire compat layer is testable
in JVM unit tests without a device. Only the actual LiteRT-LM runtime requires
an instrumented test (above).

Engine-level logic is tested through the Kotlin `EngineRepository` layer:

```kotlin
// Engine tests use a FakeEngine that implements InferenceEngine
private inner class FakeEngine : InferenceEngine {
    override suspend fun loadModel(model: Model, config: ModelLoadConfig): Result<EngineModelInfo> =
        Result.success(EngineModelInfo(/* fake data */))

    override fun generateChatStream(...): Flow<Result<StreamChunk>> = flow {
        emit(Result.success(StreamChunk("Hello", false)))
        emit(Result.success(StreamChunk(" world", true)))
    }
}
```

### Stress Testing the Engine

```kotlin
class DefaultEngineRepositoryStressTest {
    @Test
    fun `concurrent generation calls are serialized by mutex`() = runTest {
        // Spawn 10 concurrent generate calls
        // Verify only one is in-flight at a time (Mutex serialization)
        // Verify all 10 complete successfully
    }
}
```

---

## Testing Guidelines

### What to Test

✅ **Always test:**
- Public API contracts (interfaces and their implementations)
- ViewModel state transitions
- Repository operations with mocked dependencies
- Serialization/deserialization of network payloads
- Catalog parsing and validation logic
- Vector math operations (cosine similarity)
- Navigation route construction
- Engine compat layer: container metadata parsing, chat template rendering, stop-sequence tracking, output decoding, tool-call scanning

❌ **Do not test:**
- Android framework internals (let Android test them)
- Third-party library behavior (Timber, Hilt, Room, LiteRT-LM runtime itself)
- Trivial getters/setters
- Composable rendering of static content (use screenshot tests instead)

### Test Naming Convention

Use backtick-enclosed sentences describing the behavior:

```kotlin
@Test
fun `reload model unloads previous model first`() = runTest { ... }

@Test
fun `provider with invalid API key returns error on health check`() = runTest { ... }

@Test
fun `cosine similarity of identical vectors is 1.0`() { ... }
```

### Mock Patterns

```kotlin
// Mockk co-routine mocks for suspend functions
coEvery { mockRepository.save(any()) } returns Result.success(Unit)
coVerify { mockRepository.save(argThat { it.content == "expected" }) }

// Mockk regular function mocks
every { mockProvider.isConfigured() } returns false

// Argument matchers
coCaptor<List<Memory>> capture { }
coEvery { mockDao.insert(capture(capture)) } returns 1L
assertThat(capture.firstValue[0].content).isEqualTo("extracted fact")
```

---

## Continuous Testing

While no CI/CD pipeline is configured, you can set up local pre-commit hooks:

```bash
# .git/hooks/pre-commit
#!/bin/sh
./gradlew spotlessCheck detekt test --parallel
```

Make it executable:
```bash
chmod +x .git/hooks/pre-commit
```

---

## Test Data

### Sample Catalog Data

Mock catalog JSON is stored in test resources:
- `core/models/src/test/resources/catalog_sample.json`
- Contains models across families: gemma, qwen, deepseek, llama, mistral

### Sample Test Messages

```json
[
  {"role": "user", "content": "What is the capital of France?"},
  {"role": "assistant", "content": "The capital of France is Paris."},
  {"role": "user", "content": "What is its population?"}
]
```

### Fake Models

| Model ID | Name | Parameters | Format | Quantization |
|---|---|---|---|---|
| `test-qwen3-0.6b` | Qwen3 0.6B Test | 0.6B | LITERTLM | MIXED (int4) |
| `test-gemma3-1b` | Gemma 3 1B Test | 1B | LITERTLM | Q4 |
| `test-qwen2.5-1.5b` | Qwen2.5 1.5B Test | 1.5B | LITERTLM | Q8 |
