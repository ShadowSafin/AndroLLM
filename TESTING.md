# Testing Guide

Comprehensive guide to the testing strategy, frameworks, and practices in AndroLLM.

---

## Testing Pyramid

```
        /\
       /  \      Instrumented Tests (Espresso + Compose UI)
      /----\     ~4 test classes
     /      \
    /========\    Unit Tests (JUnit 4 + mockk + Turbine)
   /  51 tests \  ~47 test classes
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
./gradlew :engine:test --tests "*.GgufValidatorTest"
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

### Test Coverage by Module

| Module | Test Count | Key Areas Tested |
|---|---|---|
| `core:common` | 1 | `Result` sealed class behavior |
| `core:cloud` | 5 | Provider manager, health monitor, streaming parser, codec |
| `core:database` | 1 | Entity mapping correctness |
| `core:datastore` | 1 | Preference key type safety |
| `core:memory` | 4 | Vector math, vector index, extraction parser, routing intelligence |
| `core:models` | 6 | Catalog parsing, validation, search, recommendations, quant classification |
| `core:navigation` | 1 | Route constant consistency |
| `core:network` | 2 | DTO serialization, HuggingFace API response parsing |
| `core:telemetry` | 1 | Telemetry history storage |
| `core:utils` | 1 | Storage utility functions |
| `engine` | 5 | Engine repository, GGUF validation, memory estimation, config serialization |
| `feature:chat` | 3 | ViewModel state management, stabilization, conversation export |
| `feature:home` | 1 | Home ViewModel |
| `feature:models` | 3 | Models ViewModel, download manager, compatibility analyzer |
| `feature:onboarding` | 1 | Onboarding ViewModel |
| `feature:profile` | 1 | Profile ViewModel |
| `feature:prompts` | 1 | Prompt library ViewModel |
| `feature:settings` | 1 | Settings ViewModel |
| `feature:splash` | 1 | Splash screen timing |
| **Total** | **51** | |

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
| `EngineStressInstrumentedTest` | `engine/src/androidTest/` | Engine lifecycle under stress |
| `ChatScreenUiTest` | `feature/chat/src/androidTest/` | Compose UI: message bubbles, input, scrolling |
| `MigrationTest` | `core/memory/src/androidTest/` | Room database migration correctness |

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

## Testing the Native Engine

The native engine is tested indirectly through the Kotlin `EngineRepository` layer:

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
@AndroidTest
class DefaultEngineRepositoryStressTest {
    @Test
    fun `concurrent generation calls are serialized by mutex`() {
        // Spawn 10 concurrent generate calls
        // Verify only one is in-flight at a time
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

❌ **Do not test:**
- Android framework internals (let Android test them)
- Third-party library behavior (Timber, Hilt, Room)
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
| `test-gemma-2b` | Gemma 2B Test | 2B | GGUF | Q4_K_M |
| `test-qwen-7b` | Qwen 7B Test | 7B | GGUF | Q5_K_M |
| `test-llama-3b` | Llama 3B Test | 3B | GGUF | Q4_K_S |
