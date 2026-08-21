# Contributing to AndroLLM

Thank you for your interest in contributing! This document covers everything you need to know to make high-quality contributions.

---

## Table of Contents

- [Getting Started](#getting-started)
- [Development Environment](#development-environment)
- [Architecture Principles](#architecture-principles)
- [Code Style](#code-style)
- [Testing](#testing)
- [Pull Requests](#pull-requests)
- [Documentation](#documentation)
- [Reporting Bugs](#reporting-bugs)
- [Feature Requests](#feature-requests)

---

## Getting Started

1. **Fork** the repository on GitHub
2. **Clone** your fork locally:
   ```bash
   git clone https://github.com/<your-username>/androllm.git
   cd androllm
   ```
3. **Create a branch** for your work:
   ```bash
   git checkout -b feat/add-new-provider
   # or
   git checkout -b fix/gpu-delegate-crash
   # or
   git checkout -b docs/update-voice-docs
   ```
4. **Install prerequisites** (see [BUILDING.md](documentation/BUILDING.md))
5. **Build and run** the debug APK on a device or emulator

---

## Development Environment

### Required Tools

| Tool | Minimum Version | Notes |
|---|---|---|
| Android Studio | Hedgehog (2023.1.1) | Latest stable preferred |
| JDK | 17 | Managed by Gradle toolchain (foojay-resolver) |
| Android SDK | API 36 | `compileSdk 36` |

No NDK, CMake, or Vulkan SDK are required — the inference engine consumes
LiteRT-LM / LiteRT AARs from Google Maven (the only native build in the repo is
the `:whisper` module).

### Recommended Setup

```bash
# Set environment variables (add to ~/.bashrc or ~/.zshrc)
export ANDROID_HOME=$HOME/Android/Sdk
```

### IDE Configuration

- Install the **Kotlin** and **Compose** plugins in Android Studio
- Enable **Analyze → Run Inspection by Name → Kotlin style**
- Enable **Editor → Inspections → Compose** for composable lint checks
- Configure **Spotless** to run on save: Android Studio → Settings → Tools → Actions on Save

---

## Architecture Principles

AndroLLM follows **Clean Architecture** with a clear separation between layers:

```
Presentation Layer    ← Feature modules (UI, ViewModel, Compose)
Domain Layer          ← Core modules (models, interfaces, use cases)
Data Layer            ← Core modules (repositories, database, network)
Engine Layer          ← Engine module (LiteRT-LM runtime integration)
```

### Rules

1. **Inner modules never depend on outer modules.** `core:common` cannot import from `feature:chat`.
2. **Feature modules are independent.** They depend on `core:*` modules but never on each other.
3. **All DI goes through Hilt.** Never use `object` singletons for services; use `@Singleton @Inject`.
4. **UI state flows outward.** Use `StateFlow`/`Flow` from ViewModels to composables; never mutate UI state directly.
5. **Prefer immutable data.** Use `data class` with `val` properties; avoid mutable state in domain objects.
6. **Engine calls stay on background dispatchers.** The engine is a pure Kotlin/Java module wrapping the LiteRT-LM runtime; never block the main thread with engine or model I/O.

---

## Code Style

### Kotlin Conventions

AndroLLM follows the [official Kotlin coding conventions](https://kotlinlang.org/docs/coding-conventions.html) plus these project-specific rules:

#### Naming
- Classes: `PascalCase` — `LiteRtLmEngine`, `ChatViewModel`
- Functions/variables: `camelCase` — `generateChatStream()`, `engineHandle`
- Constants: `UPPER_SNAKE_CASE` — `MAX_CONTEXT_LENGTH`, `DEFAULT_TEMPERATURE`
- Private fields: prefix with underscore — `_messages`, `_isGenerating`

#### Coroutines
```kotlin
// ✅ Correct: suspend functions for IO operations
suspend fun loadModel(model: Model): Result<EngineModelInfo>

// ✅ Correct: Flow for reactive UI state
val state: StateFlow<UiState<List<Message>>> get() = _state

// ✅ Correct: launch in viewModelScope for ViewModel-side work
viewModelScope.launch {
    repository.saveMessage(message)
}

// ✅ Correct: launchIn for collecting flows in Compose
state.collectAsStateWithLifecycle()

// ❌ Avoid: GlobalScope
GlobalScope.launch { ... }  // NEVER

// ❌ Avoid: blocking calls on main thread
fun heavyComputation() { ... }  // Mark as suspend if it does IO
```

#### Result Handling
```kotlin
// ✅ Use the project's Result sealed class
override suspend fun loadModel(...): Result<EngineModelInfo> = runCatching {
    // implementation
}

// ✅ Handle results explicitly in UI
when (val result = viewModel.state.value) {
    is UiState.Loading -> LoadingIndicator()
    is UiState.Success -> Content(result.data)
    is UiState.Error   -> ErrorMessage(result.error)
}
```

#### Compose Conventions
```kotlin
// ✅ Composable naming: PascalCase
@Composable
fun MessageBubble(message: Message, onRetry: () -> Unit) { ... }

// ✅ Extract complex logic into remember blocks
val formattedText = remember(message.content) {
    markdownToAnnotatedString(message.content)
}

// ✅ Use rememberUpdatedState for callbacks in callbacks/lambdas
val currentOnSend by rememberUpdatedState(onSend)

// ❌ Avoid: putting heavy computation inside composables without remember
```

#### Hilt / DI
```kotlin
// ✅ Inject in ViewModels
@HiltViewModel
class ChatViewModel @Inject constructor(
    private val engineRepository: EngineRepository,
    private val memoryManager: MemoryManager
) : BaseViewModel() { ... }

// ✅ Bind interfaces in modules
@Module
@InstallIn(SingletonComponent::class)
abstract class VoiceModule {
    @Binds @Singleton
    abstract fun bindWakeWordEngine(engine: SherpaOnnxWakeWordEngine): WakeWordEngine
}

// ❌ Avoid: manual instantiation of injected classes
```

#### Git Commits
```
feat(chat): add markdown code block syntax highlighting
fix(engine): recover from GPU delegate crash with CPU fallback
docs: add GPU acceleration troubleshooting guide
refactor(memory): extract embedding routing into separate class
test(models): add catalog validator edge case tests
chore(deps): bump sherpa-onnx to 1.13.4
```

Format: `<type>(<scope>): <description>`

Types: `feat`, `fix`, `docs`, `refactor`, `test`, `chore`, `perf`

---

## Testing

AndroLLM uses a layered testing strategy:

### Unit Tests (JUnit 4 + mockk + Turbine)
Run with: `./gradlew test`

Tests should cover:
- ViewModel state transitions
- Repository operations (mocked DAOs)
- Catalog parsing and validation
- Network response serialization
- Memory extraction and vector math
- Engine compat layer: container metadata, chat templates, stop-sequence tracking, tool-call scanning

Pattern:
```kotlin
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {
    private lateinit var viewModel: ChatViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        // Initialize injected dependencies with fakes
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `send message adds user message and triggers generation`() = runTest {
        // Arrange
        val expectedContent = "Hello"
        // Act
        viewModel.sendMessage(expectedContent)
        // Assert with Turbine
        viewModel.state.test {
            val success = awaitItem()
            assertTrue(success is UiState.Success)
        }
    }
}
```

### Instrumented Tests (Espresso + Compose UI Test)
Run with: `./gradlew connectedAndroidTest`

Tests for:
- Chat screen message rendering
- Navigation transitions
- Voice overlay visibility

### Model Tests
Engine-level tests verify:
- Container metadata parsing correctness (`ContainerMetadataReader`)
- Family resolution and chat template rendering
- Memory estimation accuracy
- Serialization round-trips for `GenerationConfig`

---

## Pull Requests

### Before Submitting

1. **Rebase** on the latest `main` branch
2. **Run the full test suite**: `./gradlew test connectedAndroidTest`
3. **Run Spotless**: `./gradlew spotlessCheck` (fix with `./gradlew spotlessApply`)
4. **Run Detekt**: `./gradlew detekt` (address any violations)
5. **Update documentation** if you changed APIs or behavior
6. **Add tests** for new functionality

### PR Template

```markdown
## Description
What does this PR change?

## Type of Change
- [ ] Bug fix
- [ ] New feature
- [ ] Breaking change
- [ ] Documentation update

## Testing
- [ ] Unit tests pass
- [ ] Instrumented tests pass
- [ ] Manual testing on device completed
- [ ] Benchmarks ran (if applicable)

## Checklist
- [ ] My code follows the style guidelines
- [ ] I have commented my code, particularly in hard-to-understand areas
- [ ] I have made corresponding changes to the documentation
- [ ] My changes generate no new warnings
```

### Review Process

1. At least one maintainer review required
2. All CI checks must pass (when configured)
3. Squash merge preferred for clean history
4. Maintainers may request changes before merging

---

## Documentation

Good documentation is as important as good code. When contributing:

### README Updates
If you add a feature visible to users, update the README feature list and any relevant installation steps.

### Javadoc/KDoc
Public APIs must have KDoc comments:
```kotlin
/**
 * Loads a .litertlm model from the given file path.
 *
 * @param modelPath Absolute path to the .litertlm file
 * @param config Generation configuration (temperature, top-k, etc.)
 * @return Result containing the loaded model info or an error
 * @throws IllegalStateException if the engine is not initialized
 */
suspend fun loadModel(modelPath: String, config: GenerationConfig): Result<EngineModelInfo>
```

### New Feature Docs
New features should have a corresponding `documentation/` entry explaining:
- What it does
- How to configure it
- Known limitations
- Example usage

---

## Reporting Bugs

Use the [bug report template](https://github.com/ShadowSafin/AndroLLM/issues/new?template=bug_report.md) when filing issues. Include:

1. **Device info**: Model, Android version, RAM
2. **App version**: From Settings → About
3. **Steps to reproduce**: Numbered, specific actions
4. **Expected behavior**: What should have happened
5. **Actual behavior**: What actually happened
6. **Logs**: Export from Settings → Logs & Diagnostics
7. **Screenshots/video**: If the issue is visual

---

## Feature Requests

Use the [feature request template](https://github.com/ShadowSafin/AndroLLM/issues/new?template=feature_request.md). Please explain:

1. **The problem** you're trying to solve
2. **Why it matters** to you and potentially other users
3. **Your proposed solution** (optional)
4. **Alternative solutions** you've considered

---

## First-Time Contributor Tips

1. Look for issues labeled `good first issue` — these are scoped for newcomers
2. Read the [ARCHITECTURE.md](documentation/ARCHITECTURE.md) doc to understand the system
3. Ask questions in issues — maintainers are happy to help
4. Start small: a documentation fix, a test addition, a typo correction
5. Join the conversation in [Discussions](https://github.com/ShadowSafin/AndroLLM/discussions)

---

## Code of Conduct

This project follows the [Code of Conduct](CODE_OF_CONDUCT.md). Be kind, be patient, and remember that this is open-source software built by volunteers.
