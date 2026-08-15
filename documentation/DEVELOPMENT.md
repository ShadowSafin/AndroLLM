# Development Guide

A comprehensive guide for developers working on AndroLLM.

---

## Table of Contents

- [IDE Setup](#ide-setup)
- [Code Navigation](#code-navigation)
- [Debugging](#debugging)
- [Profiling](#profiling)
- [Logging](#logging)
- [Adding a New Feature](#adding-a-new-feature)
- [Adding a New Module](#adding-a-new-module)
- [Working with the Engine](#working-with-the-engine)
- [Working with Voice Models](#working-with-voice-models)

---

## IDE Setup

### Android Studio Configuration

1. **Open the project**: File → Open → select the AndroLLM root directory
2. **Wait for Gradle sync**: The first sync may take 10–15 minutes
3. **Configure Kotlin style inspection**:
   - Settings → Editor → Inspections → Kotlin
   - Enable: "Kotlin style" inspections
4. **Enable Compose preview**:
   - Settings → Editor → File Types → Compose
   - Mark `*.kt` files as Compose sources

### Recommended Plugins

| Plugin | Purpose |
|---|---|
| **Compose** | Preview, lint, and refactoring support for Compose |
| **Material Theme Editor** | Explore and customize the design system |
| **Git Flow Integration** | Branch management workflows |
| **Statistic** | Lines of code, file counts per module |

---

## Code Navigation

### Finding Key Classes

| Want to find... | Look here... |
|---|---|
| App entry point | `app/src/main/java/io/androllm/app/MainActivity.kt` |
| Navigation graph | `app/src/main/java/io/androllm/app/navigation/AppNavHost.kt` |
| Inference interface | `engine/src/main/java/io/androllm/engine/api/InferenceEngine.kt` |
| LiteRT-LM engine impl | `engine/src/main/java/io/androllm/engine/core/LiteRtLmEngine.kt` |
| Embedding engine | `engine/src/main/java/io/androllm/engine/embedding/LiteRtEmbeddingEngine.kt` |
| Family compat layer | `engine/src/main/java/io/androllm/engine/compat/` |
| Chat ViewModel | `feature/chat/src/main/java/io/androllm/feature/chat/ChatViewModel.kt` |
| Voice service | `feature/voice/src/main/java/io/androllm/feature/voice/service/VoiceAssistantService.kt` |
| Memory manager | `core/memory/src/main/java/io/androllm/core/memory/MemoryManager.kt` |
| Cloud gateway | `core/cloud/src/main/java/io/androllm/core/cloud/CloudGateway.kt` |
| Database | `core/database/src/main/java/io/androllm/core/database/AppDatabase.kt` |

### Module Quick-Jump (Cmd/Ctrl+Shift+N)

Type the module prefix to jump directly:
- `:app` — Application module
- `:core:common` — Base types
- `:core:database` — Room database
- `:engine` — LiteRT-LM engine
- `:feature:chat` — Chat screen
- `:feature:voice` — Voice assistant

---

## Debugging

### Log Filtering

Use Timber with tagged logs. Filter in Logcat by tag prefix:

| Tag Prefix | Category |
|---|---|
| `AndroLLM` | General app logs |
| `AndroLLM-Engine` | Inference engine operations (see `RuntimeLogger`) |
| `Voice` | Voice assistant lifecycle |
| `Memory` | Memory system operations |
| `Cloud` | Cloud provider interactions |
| `Network` | HTTP requests/responses |
| `Database` | Room database operations |

### Debugging the Engine

The engine's `RuntimeLogger` writes to the `AndroLLM-Engine` logcat tag with
stages, timing, and generation stats. To see the **full rendered chat prompt**
(useful for template/tooling bugs), enable developer diagnostics in the app
(`debugTokenLogging`) — a summary line is always logged, but the complete
prompt is only dumped when the flag is on.

### Debugging Streaming Tokens

Token streaming is throttled to ~60fps. To see raw token output:

```kotlin
// In ChatViewModel, add debug logging
override fun generateChatStream(...) = flow {
    emit(Result.Loading)
    engine.generateChatStream(messages, addAssistant, config).collect { chunk ->
        Timber.d("TOKEN: [%s] finished=%s", chunk.delta.take(20), chunk.finished)
        emit(chunk)
    }
}
```

### Database Inspection

Use Android Studio's Database Inspector:
1. Run the app on a device/emulator
2. View → Tool Windows → Database Inspector
3. Select `app.db` (main database) and `memory.db` (memory database)
4. Query tables directly or watch live changes

### Network Inspection

Ktor client logging is enabled in debug builds:
```kotlin
install(Logging) {
    level = LogLevel.INFO  // Shows request/response headers and bodies
}
```

For cloud provider debugging, use OkHttp's logging interceptor (built into `CloudHttpClientFactory`).

---

## Profiling

### Performance Profiling

Use Android Studio's Profiler for CPU, memory, and energy:

1. **CPU Profiler**: Identify slow operations in ViewModels and background workers
2. **Memory Profiler**: Watch for leaks in engine session handling (conversations hold KV cache)
3. **Energy Profiler**: Check voice service power consumption

### GPU Profiling (GPU Delegate)

The LiteRT GPU delegate runs on OpenCL. For GPU profiling:

1. Use **Android GPU Inspector** to capture GPU/OpenCL workloads during generation
2. Monitor `MemoryStats` (`gpuFree`/`gpuTotal`) in Developer Diagnostics to watch delegate memory
3. Watch for fallbacks: `backend=GPU` → `backend=CPU` in logs indicates delegate issues

### Token Generation Benchmarking

Use the built-in developer diagnostics:

1. Enable Developer Mode: Settings → Developer Options → toggle on
2. Navigate to Developer screen
3. Tap "Benchmark" next to any loaded model
4. Results show tokens/sec, load time, and memory usage

---

## Logging

All logging uses [Timber](https://github.com/JakeWharton/timber). Log levels map to Android log priorities:

| Timber Level | Log Priority | Usage |
|---|---|---|
| `Timber.v()` | VERBOSE | Verbose tracing (disabled in release) |
| `Timber.d()` | DEBUG | Debug information (development builds) |
| `Timber.i()` | INFO | Important state changes |
| `Timber.w()` | WARN | Recoverable errors, warnings |
| `Timber.e()` | ERROR | Failures that need attention |

### Enabling Verbose Logging

In debug builds, verbose logging is automatically enabled. To force it in release:

```kotlin
// In AndroLLMApplication.onCreate()
if (BuildConfig.DEBUG) {
    Timber.plant(Timber.DebugTree())
} else {
    Timber.plant(CrashlyticsTree())  // When Firebase Crashlytics is integrated
}
```

### Exporting Logs

The Developer screen includes a log export feature that captures the last N log entries to a file. Access via:
Settings → Developer Options → Logs & Diagnostics → Export Logs

---

## Adding a New Feature

### Checklist

1. **Determine the module**: Does the feature belong in an existing module or a new one?
2. **Define the interface**: Create the public API interface in `core:`
3. **Implement the interface**: Create the concrete implementation
4. **Add DI bindings**: Register in the appropriate Hilt `@Module`
5. **Create the UI**: Build the Compose screen in a `feature:` module
6. **Add navigation**: Register routes in `Routes.kt` and `AppNavHost.kt`
7. **Write tests**: At least one unit test per new class
8. **Update documentation**: Add to relevant docs and README

### Example: Adding a New Cloud Provider

```kotlin
// 1. No code changes needed — LiteLLM proxy handles any OpenAI-compatible provider
// Just add a new CloudProvider entry via the UI

// 2. Or, if adding native provider support:
// a. Create interface method in CloudGateway
// b. Implement in LiteLLMClient
// c. Add test in core:cloud/src/test/
```

### Example: Adding a New Model Architecture

```kotlin
// 1. Add the family to ModelFamily.kt in the engine (if not already present)
// 2. Add a registry entry in ModelFamilyRegistry.kt mapping the container's
//    llm_model_type → family + chat template
// 3. Add any family-specific special/stop tokens to SpecialTokens.kt
// 4. Add a catalog entry (with family/architecture fields) in CatalogModels.kt
// 5. Test with a .litertlm model of that architecture
```

---

## Adding a New Module

### When to Add a Module

Add a new module when:
- The functionality is independently testable
- It has a distinct dependency footprint
- Multiple features would benefit from it

> Note: the inference engine has **no native code** — the `engine` module is a
> pure Kotlin/Java module consuming LiteRT-LM/LiteRT AARs. Do not add native
> code to it. The only NDK module in the repo is `:whisper`.

### Module Template

```kotlin
// build.gradle.kts
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    id("dagger.hilt.android.plugin")
    id("com.google.devtools.ksp")
}

android {
    namespace = "io.androllm.core.<name>"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:ui"))
    // ... other dependencies
}
```

```kotlin
// Add to settings.gradle.kts
include(":core:<name>")
```

```kotlin
// Add to app/build.gradle.kts dependencies
implementation(project(":core:<name>"))
```

---

## Working with the Engine

### Architecture at a Glance

The engine is a 100% Kotlin/Java module:

- **`LiteRtLmEngine`** wraps `com.google.ai.edge.litertlm.Engine` — the LiteRT-LM
  Kotlin API. It owns the `Conversation`, applies family chat templates
  (`ExperimentalFlags.overwritePromptTemplate`), configures sampling, and
  streams tokens.
- **Compat layer** (`engine/.../compat/`) resolves the model family from
  container metadata, picks the right template/tokenizer/stop tokens, and
  post-processes output (`StopSequenceTracker`, `OutputDecoder`).
- **`LiteRtEmbeddingEngine`** uses the raw LiteRT `CompiledModel` API for the
  EmbeddingGemma 300M embedding model (no LiteRT-LM needed).
- **Hilt bindings** live in `engine/.../di/EngineModule.kt`:
  `LiteRtLmEngine` → `InferenceEngine`, `DefaultEngineRepository` → `EngineRepository`.

### Upgrading LiteRT-LM

1. Update the version in `gradle/libs.versions.toml`:
   - `litertlm-android` (chat runtime)
   - `litert` (embedding runtime)
2. Run the engine unit tests: `./gradlew :engine:test`
3. Run the instrumented stress test on a device with a `.litertlm` model
4. Check the LiteRT-LM release notes for API changes (`ExperimentalFlags`,
   `SamplerConfig`, etc.)

### Adding a New Family

1. Add the enum entry to `ModelFamily.kt`
2. Map the container's `llm_model_type` (or fallback signature) in `ModelFamilyRegistry.kt`
3. Provide the chat template in `ChatTemplates.kt` (override via `ExperimentalFlags.overwritePromptTemplate`)
4. Set special tokens / stop tokens in `SpecialTokens.kt`
5. Add unit tests in `engine/src/test/.../compat/`

---

## Working with Voice Models

Voice models are bundled as ONNX files in `app/src/main/assets/voice/`. They are downloaded at build time by the `downloadVoiceModels` Gradle task.

### Model Assets

| Model | Path | Size | Purpose |
|---|---|---|---|
| ASR (English 20M) | `voice/asr/` | ~8 MB | Streaming speech recognition |
| KWS (Zh-En 3M) | `voice/kws/` | ~3 MB | Wake word detection |
| TTS (VITS-LJS) | `voice/tts/` | ~114 MB | Text-to-speech (lazily loaded) |

### Updating Voice Models

1. Download the new model from [sherpa-onnx releases](https://github.com/k2-fsa/sherpa-onnx/releases)
2. Replace files in `app/src/main/assets/voice/<type>/`
3. Update `VoiceModels.kt` asset path constants if filenames changed
4. Update `downloadVoiceModels` task in `app/build.gradle.kts` if auto-download URLs changed
5. Rebuild: `./gradlew assembleDebug`

### Adding a New Language ASR Model

1. Download the sherpa-onnx model package for the target language
2. Place ONNX files in `app/src/main/assets/voice/asr/`
3. Update `SherpaOnnxStreamingRecognizer.kt` to load the new model paths
4. Add language selection UI in `VoiceAssistantSection.kt`
5. Add tests in `core:voice/src/test/`

---

## Code Review Checklist

Before requesting a review, verify:

- [ ] All new public APIs have KDoc comments
- [ ] Unit tests cover the new functionality
- [ ] No TODO/FIXME comments left without issue links
- [ ] `spotlessApply` has been run
- [ ] No debug `Log.d` or `println` statements in production code
- [ ] Coroutines are properly scoped (`viewModelScope`, `launch`, `repeatOnLifecycle`)
- [ ] No memory leaks (ViewModels don't hold Context; Services have proper lifecycle)
- [ ] Threading is correct (no main-thread blocking operations)
- [ ] Edge cases handled (null safety, empty lists, network failures)
- [ ] Engine calls stay on background dispatchers; UI only observes `StateFlow`