# AndroLLM Project Structure

This document describes the module layout and dependency graph of the AndroLLM Gradle project.

---

## Module Count

**26 Gradle modules** organized into three tiers: Application, Core libraries, and Feature modules.

---

## Tier 1: Application Module

| Module | Namespace | Purpose |
|---|---|---|
| `app` | `io.androllm.app` | Entry point, `Application` class, root navigation graph, Firebase auth integration |

Key classes in `app`:
- `AndroLLMApplication.kt` — Hilt application class, WorkManager configuration, telemetry initialization
- `MainActivity.kt` — Single-activity host, theme application, lifecycle management
- `navigation/AppNavHost.kt` — Root `NavHost` wiring all 15 destination routes
- `auth/FirebaseAuthScreen.kt` — Google Sign-In + GitHub OAuth login UI
- `profile/ProfileSetupScreen.kt` — One-time profile completion flow

---

## Tier 2: Core Library Modules

These are shared libraries consumed by the `app` module and individual feature modules. Each targets a single responsibility.

### `core:common` — Foundation Types
Namespace: `io.androllm.core.common`

Provides the base types every other module depends on:

| Class | Role |
|---|---|
| `Result<T, E>` | Sealed class wrapping success/error for operation results |
| `UiState<T>` | Sealed interface for UI loading/success/error states |
| `BaseViewModel` | Base class providing coroutine scope and common ViewModel utilities |
| `BaseRepository` | Interface contract for repository implementations |
| `UseCase<R, P>` | Generic base for use-case objects |
| `AppConstants` | App-wide constant definitions (navigation routes, DB version, etc.) |
| `Extensions.kt` | Cross-cutting Kotlin extension functions |

### `core:ui` — Design System
Namespace: `io.androllm.core.ui`

Implements "The Parchment Ledger" design system defined in `DESIGN.md`.

| Package | Contents |
|---|---|
| `theme/` | `Color.kt`, `Shape.kt`, `Theme.kt`, `Type.kt` — token definitions |
| `components/` | `CloudGlassCard`, `CloudCapsuleButton`, `CloudChip`, `SectionHeader`, `EmptyState`, `LoadingIndicator`, `GradientBackground`, `CloudAtmosphericBackground`, `CloudBugdroidLogo`, `ModelWalletCard`, `DebugOverlay`, `PromptStudioCarousel`, `CloudBottomNavigationBar`, `CloudAdaptiveNavigation` |

### `core:database` — Persistence
Namespace: `io.androllm.core.database`

Room database with 4 entities, 4 DAOs, and 4 repositories. Version 5 with WAL mode.

| Entity | Table | Fields |
|---|---|---|
| `ConversationEntity` | `conversations` | id, title, created_at, updated_at, last_message_preview, message_count, is_pinned, is_archived |
| `MessageEntity` | `messages` | id, conversation_id (FK), role, content, timestamp, is_pending, model_id, is_bookmarked, origin |
| `ModelEntity` | `models` | id, name (unique), description, file_path, file_size, format, parameters, quantization, context_length, download_url, is_downloaded, is_loaded, download_status, status, sha256, architecture, family, license, min_ram_gb, recommended_ram_gb, is_favorite, is_default, added_date, last_used_date |
| `SettingsEntity` | `settings` | id (PK="app"), theme, language, storage_path, developer_mode, first_launch, model_path, gemini_api_key_encrypted |

Migrations:
- `1→2`: Added `is_pinned`, `is_archived` to conversations; `is_bookmarked` to messages
- `2→3`: Added `license` column to models
- `3→4`: Added `origin` column to messages (TYPED/VOICE/AUTOMATION)
- `4→5`: Added `gemini_api_key_encrypted` to settings

### `core:datastore` — Preferences
Namespace: `io.androllm.core.datastore`

- `PreferencesDataStore` — Singleton wrapper around `datastore-preferences`
- `UserPreferences` — Typed preference keys for theme, language, developer mode, etc.
- Hilt `@Module` binding in `di/DataStoreModule.kt`

### `core:navigation` — Routing
Namespace: `io.androllm.core.navigation`

- `Routes.kt` — Centralized route constants and builder functions
- `NavigationExtensions.kt` — `NavHostController` convenience extensions

### `core:models` — Domain Models & Catalog
Namespace: `io.androllm.core.models`

| Class | Role |
|---|---|
| `Model` | Primary domain model for a downloaded GGUF file |
| `CatalogModel` | Rich remote catalog entry (37 fields: families, architectures, tags, licenses, RAM fit, trending score, etc.) |
| `Conversation` / `Message` | Chat domain objects |
| `AppSettings` | App-wide settings model |
| `Enums` | `DownloadStatus`, `ModelFormat`, `ModelStatus`, `ModelCategory`, `ModelOrigin` |
| `catalog/CatalogRepository` | Loads/refreshes catalog JSON from bundled asset or HuggingFace |
| `catalog/CatalogLoader` | Parses + validates catalog JSON |
| `catalog/CatalogParser` | JSON deserialization for catalog |
| `catalog/CatalogValidator` | Validates IDs uniqueness, required fields, supported architectures, SHA-256 format, HTTPS URLs |
| `catalog/SupportedArchitectures` | Whitelist of 137 llama.cpp-supported architectures |
| `catalog/ModelSearchEngine` | Search/filter/sort over 16 filter dimensions, 11 sort options |
| `catalog/RecommendationEngine` | Scores models by RAM fit, size fit, quant tier, popularity, curated flag |
| `catalog/OfficialModelCatalog` | Hardcoded built-in models (Gemma, Qwen, DeepSeek variants) |
| `catalog/QuantClassifier` | Maps quant strings → `QuantLevel` enum |
| `catalog/ParameterCount` | Parses "1.5B", "407M" → double |

### `core:network` — HTTP & Downloads
Namespace: `io.androllm.core.network`

| Class | Role |
|---|---|
| `HttpClientFactory` | Ktor `HttpClient(Android)` singleton with ContentNegotiation + Logging |
| `ModelApi` / `HuggingFaceApi` | Retrofit-style API interfaces for HuggingFace repo |
| `DownloadManager` | Background file downloads with progress tracking |
| `NetworkModule` | Hilt `@Module` providing `HttpClient` |
| `catalog/CatalogNetworkModule` + `HfCatalogRemoteSource` | Remote catalog refresh from HuggingFace |
| `repository/HuggingFaceRepository` | Search/query HuggingFace model repos |
| `repository/ModelRepositoryProvider` / `RepositoryRegistry` | Factory registry for model sources |

### `core:cloud` — Cloud AI Providers
Namespace: `io.androllm.core.cloud`

| Class | Role |
|---|---|
| `CloudGateway` | Singleton facade for chat/embedding operations against cloud providers |
| `ProviderManager` | CRUD for providers; API key encrypt/decrypt via `KeyCipher`; health probes; model discovery via `/v1/models` |
| `CloudSettingsStore` | Persistent cloud settings (enabled flag, default provider/model, favorites) |
| `ProviderHealthMonitor` | Periodic health checks across all providers |
| `network/LiteLLMClient` | Retrofit + OkHttp client for SSE streaming chat completions |
| `network/CloudHttpClientFactory` | OkHttp singleton (connect=10s, read=120s, write=120s, HTTP/2) |
| `network/StreamingParser` | Pure-JVM SSE parser (`data:` lines → `CloudStreamEvent` sealed interface) |
| `network/RetryPolicy` | Exponential backoff retry on IOException, 408, 429, 5xx |
| `security/KeyCipher` | Android Keystore-backed AES-256/GCM encryption for API keys |
| `model/CloudChatMessageSerializer` | JSON serializer adapting chat messages for cloud providers |

### `core:utils` — Utilities
Namespace: `io.androllm.core.utils`

| Class | Role |
|---|---|
| `PermissionUtils` | Accompanist permission request helpers |
| `StorageUtils` | Disk space, directory sizing, cache clearing |
| `ConnectivityUtils` | Network availability checks |
| `DeviceInfoCollector` | Device specs for diagnostics (CPU, GPU, RAM, OS version) |
| `LogUtils` | Timber tree configuration |
| `StageTracer` | Timing utility for pipeline stage profiling |

### `core:telemetry` — Performance Tracking
Namespace: `io.androllm.core.telemetry`

| Class | Role |
|---|---|
| `TelemetryRepository` | Stores performance metrics (token/sec, load time, error counts) |
| `TelemetryModels` | Data classes for telemetry events |

### `core:memory` — Persistent Memory
Namespace: `io.androllm.core.memory`

See [Memory Architecture Deep Dive](memory/memory-architecture.md) for details.

| Sub-package | Classes |
|---|---|
| `db/` | `MemoryDatabase` (separate Room instance), `MemoryDao`, `EmbeddingDao`, entities |
| `vector/` | `CosineVectorIndex` (in-memory brute-force cosine similarity), `VectorMath` |
| `embedding/` | `EmbeddingProvider` interface, `RoutingEmbeddingProvider`, `CloudEmbeddingProvider`, `LlamaEmbeddingProvider` (separate native handle) |
| `intelligence/` | `MemoryIntelligence` interface, `RoutingMemoryIntelligence`, `CloudMemoryIntelligence`, `LocalMemoryIntelligence` |
| `extraction/` | `MemoryExtractor`, `ExtractionJson`, `ExtractionJsonParser` |
| `context/` | `ContextBuilder` — formats memories for system prompt injection |
| `summarize/` | `ConversationSummarizer`, `SummaryPrompts` |
| `background/` | `MemoryBackgroundScheduler`, `MemoryIndexingWorker` (WorkManager) |
| `di/MemoryModule` | Hilt bindings |

### `core:voice` — Voice Infrastructure
Namespace: `io.androllm.core.voice`

See [Voice Assistant Architecture](voice/voice-assistant.md) for details.

| Sub-package | Classes |
|---|---|
| `wakeword/` | `WakeWordEngine` interface, `OpenWakeWordEngine` (Hilt-bound), `SherpaOnnxWakeWordEngine` (actual implementation) |
| `asr/` | `SpeechRecognizer` interface, `SherpaRecognizer`, `SherpaOnnxStreamingRecognizer`, `GeminiStreamingRecognizer` |
| `tts/` | `OfflineTtsEngine` interface, `PiperSpeechSynthesizer`, `SherpaOnnxOfflineTtsEngine`, `GeminiOfflineTtsEngine` |
| `vad/` | `Vad` (energy-based), `SherpaVad` (interface wrapper) |
| `audio/` | `AudioRecorder` (16kHz mono, 200ms chunks), `AudioPlayer` (AudioTrack MODE_STREAM) |
| `model/` | `VoiceSettings`, `VoiceModels` (asset paths for ONNX models) |

---

## Tier 3: Feature Modules

Each feature module owns one screen or service. They depend on `core:*` modules but not on each other.

| Module | Namespace | Key Classes |
|---|---|---|
| `feature:home` | `io.androllm.feature.home` | `HomeScreen`, `HomeViewModel`, `ChatActivityCard` |
| `feature:chat` | `io.androllm.feature.chat` | `ChatScreen`, `ChatViewModel`, `MessageCard`, `ComposeInputArea`, `MarkdownRenderer`, `ConversationDrawer`, `GenerationStatsPanel`, `ModelParameterSheet`, `SearchOverlay`, `TypingAndThinkingIndicator`, `ConversationExporter`, `ConversationSharer` |
| `feature:models` | `io.androllm.feature.models` | `ModelsScreen`, `ModelsViewModel`, `DownloadManager`, `ModelDownloadWorker`, `CompatibilityAnalyzer`, `OfficialModelCatalog`, `CatalogModelMapper`, `ModelBenchmarker`, `CloudDownloadProgress` |
| `feature:settings` | `io.androllm.feature.settings` | `SettingsScreen`, `SettingsViewModel`, `VoiceAssistantSection` |
| `feature:voice` | `io.androllm.feature.voice` | `VoiceAssistantService` (foreground), `VoiceAssistantController` (singleton StateFlow), `VoiceAssistant`, `VoiceOverlayWindow` (TYPE_APPLICATION_OVERLAY), `VoiceOverlay`, `VoiceCommandRouter`, `SentenceAssembler`, `ChatManager`, `VoiceNotifications` |
| `feature:splash` | `io.androllm.feature.splash` | `SplashScreen` |
| `feature:onboarding` | `io.androllm.feature.onboarding` | `OnboardingScreen`, `OnboardingViewModel`, `OnboardingIllustrations` |
| `feature:profile` | `io.androllm.feature.profile` | `ProfileScreen`, `ProfileViewModel`, `ProfileSetupScreen`, `ProfileSetupViewModel` |
| `feature:prompts` | `io.androllm.feature.prompts` | `PromptLibraryScreen`, `PromptLibraryViewModel`, `PromptLibrary` |
| `feature:developer` | `io.androllm.feature.developer` | `DeveloperScreen`, `DeveloperViewModel` |
| `feature:cloud` | `io.androllm.feature.cloud` | `CloudProvidersScreen`, `CloudProvidersViewModel`, `CloudModelsScreen`, `CloudModelsViewModel`, `CloudFormParsers` |

---

## The Engine Module

The `engine` module bridges the Kotlin app layer and the native llama.cpp inference engine.

```
engine/
├── src/main/java/io/androllm/engine/
│   ├── api/
│   │   ├── InferenceEngine.kt       # Primary interface: loadModel, generateChatStream, cancel...
│   │   ├── EngineRepository.kt      # Facade adding Mutex serialization for concurrent callers
│   │   └── EngineState.kt          # StateFlow-emitting sealed interface
│   ├── backend/
│   │   ├── BackendSelector.kt       # Chooses LLAMA_CPP_VULKAN vs CPU based on hardware
│   │   └── InferenceBackend.kt     # Backend type enum (QUALCOMM_QNN, LLAMA_CPP_VULKAN, ONNX_RUNTIME, CPU, VULKAN)
│   ├── di/EngineModule.kt           # Hilt bindings: InferenceEngine→LlamaCppEngine, EngineRepository→DefaultEngineRepository
│   ├── llama/LlamaCppEngine.kt      # Singleton: manages native engine handle lifecycle
│   ├── jni/LlamaJniBridge.kt        # external fun declarations for all native calls
│   ├── models/
│   │   ├── EngineModels.kt          # EngineConfig, ModelLoadConfig, GenerationConfig, EngineModelInfo, MemoryStats
│   │   └── MemoryStats.kt
│   └── utils/
│       ├── GgufValidator.kt         # Pure-Kotlin GGUF header validator (magic + arch + context_length)
│       ├── MemoryEstimator.kt       # Estimates RAM requirement from model metadata
│       └── ThreadManager.kt
│
└── src/main/cpp/
    ├── CMakeLists.txt               # Builds libandrollm_llama.so
    ├── native_api.cpp               # ~3700 lines: JNI entry points, LlamaEngine RAII, corruption recovery
    └── llama.cpp/                   # Vendored upstream llama.cpp (stock, never patched)
        ├── ggml/                    # GPU compute backends including Vulkan
        ├── src/                     # Model loading, tokenization, inference
        ├── common/                  # Chat parsing, sampling, arguments
        └── examples/                # Includes llama.android example code (stripped)
```

### Native Library

- Output: `libandrollm_llama.so`
- ABI: `arm64-v8a` by default; add `x86_64` via `-PandrollmAbis=arm64-v8a,x86_64` for emulator testing
- C++ standard: C++17
- NDK: 26.1.10909125
- STL: `c++_shared`
- Vulkan: Enabled at build time; runtime fallback to CPU if Vulkan unavailable on device

---

## Dependency Graph (Simplified)

```
app
├── core:common
├── core:ui
├── core:database
├── core:datastore
├── core:navigation
├── core:models
├── core:network
├── core:cloud
├── core:utils
├── core:telemetry
├── core:memory
├── core:voice
├── engine
├── feature:home
├── feature:chat
├── feature:models
├── feature:settings
├── feature:voice
├── feature:splash
├── feature:onboarding
├── feature:profile
├── feature:prompts
├── feature:developer
└── feature:cloud
```

Feature modules only depend on `core:*` modules and `engine`. Feature modules do **not** depend on each other.

---

## Build Configuration

| Setting | Value |
|---|---|
| Compile SDK | 34 |
| Min SDK | 28 |
| Target SDK | 34 |
| JVM Target | 17 |
| Kotlin | 2.1.20 |
| AGP | 8.6.0 |
| NDK | 26.1.10909125 |
| CMake | 3.22.1 |
| Hilt | 2.57.1 |
| Compose BOM | 2024.10.00 |
| Room | 2.8.4 |
| Ktor | 3.0.3 |
| Firebase BoM | 34.12.0 |
| sherpa-onnx | 1.13.4 |
| R8/ProGuard | Disabled in release (`isMinifyEnabled = false`) |
| CI/CD | Not configured |
| Product Flavors | None |
