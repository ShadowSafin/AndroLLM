# AndroLLM Project Structure

This document describes the module layout and dependency graph of the AndroLLM Gradle project.

---

## Module Count

**33 Gradle modules** organized into four tiers: Application, Core libraries, Feature modules, and engine/voice/documentation support modules.

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
| `Model` | Primary domain model for a downloaded model file |
| `CatalogModel` | Rich remote catalog entry (37 fields: families, architectures, tags, licenses, RAM fit, trending score, etc.) |
| `Conversation` / `Message` | Chat domain objects |
| `AppSettings` | App-wide settings model |
| `Enums` | `DownloadStatus`, `ModelFormat` (GGUF legacy, LITERTLM, TFLITE, …), `ModelStatus`, `ModelCategory`, `ModelOrigin` |
| `catalog/CatalogRepository` | Loads/refreshes catalog JSON from bundled asset or HuggingFace |
| `catalog/CatalogLoader` | Parses + validates catalog JSON |
| `catalog/CatalogParser` | JSON deserialization for catalog |
| `catalog/CatalogValidator` | Validates IDs uniqueness, required fields, LiteRT artifacts (`.litertlm` / `.tflite` only), SHA-256 format, HTTPS URLs |
| `catalog/ModelSearchEngine` | Search/filter/sort over filter dimensions and sort options |
| `catalog/RecommendationEngine` | Scores models by RAM fit, size fit, quant tier, popularity, curated flag |
| `catalog/CatalogModels` | Hardcoded built-in models (Gemma, Qwen, DeepSeek variants) |
| `catalog/ModelInspector` | Reads `.litertlm` container metadata (LlmMetadata proto) for display; also parses GGUF headers of imported files via `GgufReader` |
| `catalog/QuantClassifier` | Maps quant strings → `QuantLevel` enum |
| `catalog/ParameterCount` | Parses "1.5B", "407M" → double |
| `gguf/GgufReader` + `gguf/GgufType` | Pure-JVM GGUF v2/v3 header parser — used ONLY for metadata inspection of GGUF files in the import flow; GGUF is not runnable by the LiteRT runtime |

### `core:network` — HTTP & Downloads
Namespace: `io.androllm.core.network`

| Class | Role |
|---|---|
| `HttpClientFactory` | Ktor `HttpClient(Android)` singleton with ContentNegotiation + Logging |
| `ModelApi` / `HuggingFaceApi` | Retrofit-style API interfaces for HuggingFace repo (filtered to `litertlm` artifacts) |
| `DownloadManager` | Background file downloads with progress tracking |
| `NetworkModule` | Hilt `@Module` providing `HttpClient` |
| `catalog/CatalogNetworkModule` + `HfCatalogRemoteSource` | Remote catalog refresh from HuggingFace |
| `repository/HuggingFaceRepository` | Search/query HuggingFace model repos for LiteRT artifacts |
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
| `embedding/` | `EmbeddingProvider` interface, `RoutingEmbeddingProvider`, `CloudEmbeddingProvider`, `LiteRtEmbeddingProvider` (LiteRT CompiledModel via `LiteRtEmbeddingEngine`) |
| `intelligence/` | `MemoryIntelligence` interface, `RoutingMemoryIntelligence`, `CloudMemoryIntelligence`, `LocalMemoryIntelligence` |
| `extraction/` | `MemoryExtractor`, `ExtractionJson`, `ExtractionJsonParser` |
| `context/` | `ContextBuilder` — formats memories for system prompt injection |
| `summarize/` | `ConversationSummarizer`, `SummaryPrompts` |
| `background/` | `MemoryBackgroundScheduler`, `MemoryIndexingWorker` (WorkManager) |
| `di/MemoryModule` | Hilt bindings |

### `core:attachments` — Chat Attachments
Namespace: `io.androllm.core.attachments`

Conversation-scoped file attachments for cloud models (see
[Chat Attachments](features/chat-attachments.md)). Files are parsed and
OCR'd on-device, cached per conversation, and never indexed.

| Sub-package | Classes |
|---|---|
| `model/` | `ChatAttachment`, `AttachmentStatus`, `AttachmentSettings` |
| `parser/` | `ParserRegistry`, `DocumentParser`, `TextParser`, `PdfParser` (PDFBox), `OfficeParsers` (DOCX/PPTX/XLSX/EPUB), `ImageParser` (ML Kit OCR) |
| — | `AttachmentProcessor` (copy → parse → OCR), `AttachmentCache` (per-conversation dir), `AttachmentSettingsStore` (DataStore), `ProviderCapabilities` (supportsAttachments / supportsVision / supportsStreaming / supportsToolCalling) |
| `di/AttachmentModule` | Hilt bindings |

### `core:permissions` — Permission & Access Manager
Namespace: `io.androllm.core.permissions`

Central permission/access system backing the first-launch setup screen and Settings → Permissions & Access.

| Class | Role |
|---|---|
| `PermissionManager` | Singleton facade: live state, request order, permanent-denial detection, feature→permission derivation |
| `PermissionHandler` | Interface for one gate (runtime permission or special access) |
| `PermissionState` | Live state enum (GRANTED / DENIED / PERMANENTLY_DENIED / NEEDS_SETTINGS / NOT_REQUIRED / …) |
| `Feature` | Declarative feature → handler mapping |
| `handler/` | Microphone, Notifications, Accessibility, Contacts, SMS, Calendar, Camera, Location, Bluetooth, Alarms handlers |
| `di/PermissionModule` | Hilt multibinding registering every handler |

### `core:voice` — Voice Infrastructure
Namespace: `io.androllm.core.voice`

See [Voice Assistant Architecture](voice/voice-assistant.md) for details.

| Sub-package | Classes |
|---|---|
| `wakeword/` | `WakeWordEngine` interface, `OpenWakeWordEngine` (Hilt-bound), `SherpaOnnxWakeWordEngine` (actual implementation) |
| `asr/` | `SpeechRecognizer` interface, `SherpaRecognizer`, `SherpaOnnxStreamingRecognizer`, `GeminiStreamingRecognizer`, whisper.cpp STT (`:whisper` module) |
| `tts/` | `OfflineTtsEngine` interface, `PiperSpeechSynthesizer`, `SherpaOnnxOfflineTtsEngine`, `GeminiOfflineTtsEngine` |
| `vad/` | `Vad` (energy-based), `SherpaVad` (interface wrapper) |
| `audio/` | `AudioRecorder` (16kHz mono, 200ms chunks), `AudioPlayer` (AudioTrack MODE_STREAM) |
| `model/` | `VoiceSettings`, `VoiceModels` (asset paths for ONNX models) |

### Agent Core Modules

| Module | Namespace | Role |
|---|---|---|
| `core:tools` | `io.androllm.core.tools` | `ToolSpec` registry (47 built-in tools), `ToolPlanner` (cloud native function calling + local `planLocal` JSON fallback), `ToolRunCoordinator`, `ToolConfirmationManager`, `ToolPromptBuilder` (tool advertisement system message, context-budgeted) |
| `core:mcp` | `io.androllm.core.mcp` | MCP client — connect external tools via Streamable HTTP; tools surface as `mcp_<server>_<tool>` |
| `core:accessibility` | `io.androllm.core.accessibility` | Accessibility-driven UI automation (tap/type/scroll/drag/swipe/pinch gestures) |
| `core:runtime` | `io.androllm.core.runtime` | Runtime component registry |
| `core:permissions` | `io.androllm.core.permissions` | Permission manager (see above) |

---

## Tier 3: Feature Modules

Each feature module owns one screen or service. They depend on `core:*` modules but not on each other.

| Module | Namespace | Key Classes |
|---|---|---|
| `feature:home` | `io.androllm.feature.home` | `HomeScreen`, `HomeViewModel`, `ChatActivityCard` |
| `feature:chat` | `io.androllm.feature.chat` | `ChatScreen`, `ChatViewModel`, `MessageCard`, `ComposeInputArea`, `MarkdownRenderer`, `ConversationDrawer`, `GenerationStatsPanel`, `ModelParameterSheet`, `SearchOverlay`, `TypingAndThinkingIndicator`, `ConversationExporter`, `ConversationSharer` |
| `feature:models` | `io.androllm.feature.models` | `ModelsScreen`, `ModelsViewModel`, `DownloadManager`, `ModelDownloadWorker`, `CompatibilityAnalyzer`, `CatalogModelMapper`, `ModelBenchmarker`, `CloudDownloadProgress` |
| `feature:settings` | `io.androllm.feature.settings` | `SettingsScreen`, `SettingsViewModel`, `VoiceAssistantSection` |
| `feature:voice` | `io.androllm.feature.voice` | `VoiceAssistantService` (foreground), `VoiceAssistantController` (singleton StateFlow), `VoiceAssistant`, `VoiceOverlayWindow` (TYPE_APPLICATION_OVERLAY), `VoiceOverlay`, `VoiceCommandRouter`, `SentenceAssembler`, `ChatManager`, `VoiceNotifications` |
| `feature:splash` | `io.androllm.feature.splash` | `SplashScreen` |
| `feature:onboarding` | `io.androllm.feature.onboarding` | `OnboardingScreen`, `OnboardingViewModel`, `OnboardingIllustrations` |
| `feature:setup` | `io.androllm.feature.setup` | `PermissionSetupScreen`, `PermissionSetupViewModel`, `PermissionsAccessScreen`, `PermissionsAccessViewModel` |
| `feature:profile` | `io.androllm.feature.profile` | `ProfileScreen`, `ProfileViewModel`, `ProfileSetupScreen`, `ProfileSetupViewModel` |
| `feature:prompts` | `io.androllm.feature.prompts` | `PromptLibraryScreen`, `PromptLibraryViewModel`, `PromptLibrary` |
| `feature:developer` | `io.androllm.feature.developer` | `DeveloperScreen`, `DeveloperViewModel` |
| `feature:cloud` | `io.androllm.feature.cloud` | `CloudProvidersScreen`, `CloudProvidersViewModel`, `CloudModelsScreen`, `CloudModelsViewModel`, `CloudFormParsers` |

---

## The Engine Module

The `engine` module hosts the **LiteRT-LM** inference runtime integration — a
100% Kotlin/Java module. There is **no native code**: no `src/main/cpp/` tree,
no JNI, no NDK, no CMake. The LiteRT-LM and LiteRT AARs come from Google Maven.

```
engine/
└── src/main/java/io/androllm/engine/
    ├── api/
    │   ├── InferenceEngine.kt       # Primary interface: loadModel, generateChatStream, cancel...
    │   ├── EngineRepository.kt      # Facade adding Mutex serialization for concurrent callers
    │   ├── DefaultEngineRepository.kt # @Singleton impl: state publishing, stall watchdogs
    │   └── EngineState.kt           # StateFlow-emitting sealed interface (Unloaded/Loading/Ready/Error)
    ├── core/
    │   ├── LiteRtLmEngine.kt        # @Singleton: wraps the LiteRT-LM Engine + Conversation
    │   └── NativeToolCallScanner.kt # Parses <|tool_call|> markers from native output
    ├── compat/
    │   ├── ModelFamily.kt           # Supported families (Gemma, Qwen2/2.5/3, Phi, Llama3, ...)
    │   ├── ModelFamilyRegistry.kt   # Container metadata → family resolution
    │   ├── ModelFamilyConfig.kt     # Per-family contract: template, tokens, defaults
    │   ├── ContainerMetadataReader.kt # LlmMetadata proto reader
    │   ├── ContainerMetadata.kt     # Parsed container metadata model
    │   ├── FlatBufferReader.kt      # FlatBuffers wire parsing for the metadata proto
    │   ├── ProtoWireReader.kt       # Protobuf wire-format helpers
    │   ├── ChatTemplateRenderer.kt  # Renders the family chat template (prompt mirroring)
    │   ├── ChatTemplates.kt         # Official chat template strings per family
    │   ├── SpecialTokens.kt         # bos/eos/forbidden token sets per family
    │   ├── StopSequenceTracker.kt   # Completes stop sequences across stream fragments
    │   ├── OutputDecoder.kt         # Special-token stripping + stop cutting
    │   ├── TokenizerFiles.kt        # Tokenizer file names per family
    │   ├── ModelCompatibilityResolver.kt # Load-time compatibility checks
    │   └── ModelCompatibilityException.kt # Thrown on unresolved family/template
    ├── diagnostics/
    │   └── RuntimeLogger.kt         # Tag-scoped logger (logcat tag: AndroLLM-Engine)
    ├── di/
    │   └── EngineModule.kt          # Hilt bindings: LiteRtLmEngine → InferenceEngine,
    │                                #   DefaultEngineRepository → EngineRepository
    ├── embedding/
    │   ├── LiteRtEmbeddingEngine.kt # Raw LiteRT CompiledModel API (EmbeddingGemma 300M)
    │   └── SentencePieceTokenizer.kt # Gemma 3 unigram tokenizer (262k vocab)
    ├── memory/
    │   └── ContextManager.kt        # Context budget tracking for conversations
    ├── models/
    │   ├── EngineModels.kt          # EngineConfig, ModelLoadConfig, GenerationConfig,
    │   │                            #   EngineModelInfo, EngineDebugInfo, EngineException,
    │   │                            #   EngineCapabilities, EngineStats, StreamChunk, ...
    │   ├── MemoryStats.kt           # RAM/GPU telemetry + recovery counters
    │   └── BackendType               # CPU/GPU + legacy-compat enum values
    └── utils/
        ├── MemoryEstimator.kt       # RAM requirement prediction from metadata
        ├── ThreadManager.kt         # Thread-count recommendation + background priority
        ├── CoherenceChecker.kt      # Post-load temperature-0 self-test probe
        ├── LiteRtValidator.kt       # Pre-load .litertlm container validation
        └── ModelResourceGuard.kt    # Refuses loads that exceed available RAM
```

### Engine Dependencies

- **Chat/generation**: `com.google.ai.edge.litertlm:litertlm-android:0.16.0`
  (LiteRT-LM Kotlin API: `Engine`, `Conversation`, `SamplerConfig`, streaming)
- **Embeddings**: `com.google.ai.edge.litert:litert:2.2.0` — raw LiteRT
  `CompiledModel` API (`org.tensorflow.lite.Interpreter`)
- 100% Kotlin/Java — no `libandrollm_llama.so`, no `.so` files at all

---

## Support Modules

| Module | Namespace | Purpose |
|---|---|---|
| `:whisper` | `io.androllm.core.whisper` | whisper.cpp STT (`WhisperContext`, `WhisperLib`) — the only NDK/CMake module in the repo, used by the voice assistant's speech recognition |
| `:documentation` | `io.androllm.docs` | Bundled documentation module (docs app screens, `AppDocs`) |

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
├── core:permissions
├── core:tools
├── core:mcp
├── core:accessibility
├── core:runtime
├── core:voice
├── engine
├── whisper
├── documentation
├── feature:home
├── feature:chat
├── feature:models
├── feature:settings
├── feature:voice
├── feature:splash
├── feature:onboarding
├── feature:setup
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
| Compile SDK | 36 |
| Min SDK | 28 |
| Target SDK | 36 |
| JVM Target | 17 |
| ABI | arm64-v8a only |
| LiteRT-LM | `com.google.ai.edge.litertlm:litertlm-android:0.16.0` (Maven) |
| LiteRT | `com.google.ai.edge.litert:litert:2.2.0` (Maven) |
| Hilt | 2.57.1 |
| Room | v5 (WAL) |
| Firebase BoM | 34.12.0 |
| sherpa-onnx | 1.13.4 |
| R8/ProGuard | Disabled in release (`isMinifyEnabled = false`) |
| CI/CD | Not configured |
| Product Flavors | None |

> **No NDK, no CMake, no Vulkan SDK** are required to build the engine — the
> only native build in the repo is the `:whisper` module (whisper.cpp STT).