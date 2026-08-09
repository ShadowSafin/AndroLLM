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
- [Working with the Native Engine](#working-with-the-native-engine)
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
| Chat ViewModel | `feature/chat/src/main/java/io/androllm/feature/chat/ChatViewModel.kt` |
| Voice service | `feature/voice/src/main/java/io/androllm/feature/voice/service/VoiceAssistantService.kt` |
| Memory manager | `core/memory/src/main/java/io/androllm/core/memory/MemoryManager.kt` |
| Cloud gateway | `core/cloud/src/main/java/io/androllm/core/cloud/CloudGateway.kt` |
| Native bridge | `engine/src/main/java/io/androllm/engine/jni/LlamaJniBridge.kt` |
| JNI implementation | `engine/src/main/cpp/native_api.cpp` |
| Database | `core/database/src/main/java/io/androllm/core/database/AppDatabase.kt` |

### Module Quick-Jump (Cmd/Ctrl+Shift+N)

Type the module prefix to jump directly:
- `:app` — Application module
- `:core:common` — Base types
- `:core:database` — Room database
- `:engine` — Native engine
- `:feature:chat` — Chat screen
- `:feature:voice` — Voice assistant

---

## Debugging

### Log Filtering

Use Timber with tagged logs. Filter in Logcat by tag prefix:

| Tag Prefix | Category |
|---|---|
| `AndroLLM` | General app logs |
| `Engine` | Inference engine operations |
| `Voice` | Voice assistant lifecycle |
| `Memory` | Memory system operations |
| `Cloud` | Cloud provider interactions |
| `Network` | HTTP requests/responses |
| `Database` | Room database operations |
| `Vulkan` | Vulkan backend diagnostics |

### Debugging Native Code

Attach the Android profiler to debug C++ code:

1. Run the app with the debug variant
2. Android Studio → View → Tool Windows → Profiler
3. Select your process → Native Allocation / CPU / GPU
4. Set breakpoints in `native_api.cpp` via the C++ debugger

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
2. **Memory Profiler**: Watch for leaks in `LlamaEngine` handle management
3. **Energy Profiler**: Check voice service power consumption

### GPU Profiling (Vulkan)

To profile Vulkan performance:

```bash
# Enable GPU validation layers (requires Vulkan SDK)
adb shell setprop debug.vulkan.layers VK_LAYER_KHRONOS_validation

# Use RenderDoc (cross-platform GPU debugger)
# Attach to the app process and capture a frame during generation
```

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
// 1. Add architecture string to SupportedArchitectures.kt in core:models
// 2. Add any architecture-specific sampling params to GenConfig in native_api.cpp
// 3. Add catalog entry with architecture field in CatalogModels.kt
// 4. Test with a model of that architecture
```

---

## Adding a New Module

### When to Add a Module

Add a new module when:
- The functionality is independently testable
- It has a distinct dependency footprint
- Multiple features would benefit from it
- It contains significant native code

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
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.1"
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

## Working with the Native Engine

### Modifying llama.cpp

The vendored llama.cpp is at `engine/src/main/cpp/llama.cpp/`. It is **stock upstream** — never patch it directly. Instead:

1. Pull the latest upstream changes:
   ```bash
   cd engine/src/main/cpp/llama.cpp
   git pull origin main
   ```
2. Rebuild the engine:
   ```bash
   ./gradlew :engine:clean :engine:build
   ```

### Adding a New JNI Function

1. Declare in `LlamaJniBridge.kt`:
   ```kotlin
   external fun nativeMyNewFunction(handle: Long, param: String): String
   ```
2. Implement in `native_api.cpp`:
   ```cpp
   extern "C" JNIEXPORT jstring JNICALL
   Java_io_androllm_engine_jni_LlamaJniBridge_nativeMyNewFunction(
       JNIEnv* env, jclass, jlong handle, jstring param) {
       // Implementation
   }
   ```
3. Rebuild: `./gradlew :engine:build`

### Understanding the JNI Bridge

The JNI bridge follows these patterns:
- **Handle-based**: Every function takes a `Long` handle (pointer to `LlamaEngine`)
- **JSON strings**: Complex parameters serialized as JSON, deserialized in C++
- **UTF-16 round-trip**: Java `String` → UTF-16 → `std::wstring` → `std::string`(UTF-8) for C++
- **Callback via jobject**: Token callbacks passed as Java/Kotlin lambdas wrapped in `JNIEnv`

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
