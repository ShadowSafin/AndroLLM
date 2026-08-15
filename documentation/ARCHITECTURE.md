# AndroLLM Architecture

Complete architectural overview of the AndroLLM application.

---

## Design Philosophy

AndroLLM is built on three core principles:

1. **Privacy by default** — Local inference runs entirely on-device; cloud features are opt-in
2. **Modular independence** — Each feature module is a self-contained unit depending only on core libraries
3. **Graceful degradation** — Every feature has a fallback path (GPU→CPU, cloud→local, embeddings→keywords)

---

## Layered Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│                         PRESENTATION LAYER                          │
│  Feature modules (home, chat, models, settings, voice, etc.)       │
│  Jetpack Compose · StateFlow · ViewModel · Navigation Compose      │
├─────────────────────────────────────────────────────────────────────┤
│                           DOMAIN LAYER                              │
│  Core interfaces · Use cases · Domain models                       │
│  InferenceEngine · MemoryManager · CloudGateway · SpeechRecognizer │
├─────────────────────────────────────────────────────────────────────┤
│                           DATA LAYER                                │
│  Repositories · DAOs · Network clients · DI modules                │
│  ConversationRepository · ModelRepository · LiteLLMClient          │
├─────────────────────────────────────────────────────────────────────┤
│                           ENGINE LAYER                              │
│  LiteRT-LM 0.16.0 (chat/generation) · LiteRT 2.2.0 CompiledModel   │
│  (embeddings) — 100% Kotlin/Java, no native code, no NDK, no JNI   │
└─────────────────────────────────────────────────────────────────────┘
```

The engine layer is a **pure JVM/Android library stack**: Google's LiteRT-LM Kotlin
API (`com.google.ai.edge.litertlm:litertlm-android:0.16.0`) drives chat/generation,
and the raw LiteRT `CompiledModel` API (`com.google.ai.edge.litert:litert:2.2.0`)
drives on-device embeddings. There is no C/C++, no NDK, no CMake, and no vendored
inference runtime in the repository — the `engine/src/main/cpp/` tree was deleted
in the LiteRT migration.

---

## Module Dependency Graph

```
                    ┌──────────────┐
                    │   app/       │
                    │  (entry point)│
                    └──────┬───────┘
                           │ depends on
        ┌──────────────────┼──────────────────┐
        ▼                  ▼                  ▼
┌───────────────┐  ┌───────────────┐  ┌───────────────┐
│  core:* libs  │  │  engine/      │  │ feature:*     │
│  (shared)     │  │  (LiteRT-LM)  │  │  (screens)    │
└───────┬───────┘  └───────────────┘  └───────┬───────┘
        │                                      │
        └──────────────────┬───────────────────┘
                           │ all depend on
                           ▼
                    ┌──────────────┐
                    │ core:common  │
                    │ (base types) │
                    └──────────────┘
```

### Cross-Module Dependencies

| Module | Depends On |
|---|---|
| `app` | All feature modules, `core:database`, `core:datastore`, `core:navigation`, `engine` |
| `core:*` | `core:common`, `core:ui` (where applicable) |
| `feature:*` | `core:common`, `core:ui`, `core:database`, `core:navigation`, `core:models`, `engine` (some) |

Feature modules never depend on each other. This allows independent development and testing.

---

## Data Flow Architecture

### Chat Generation Flow

```
User types message
        │
        ▼
ChatViewModel.sendMessage(text)
        │
        ├──▶ ConversationRepository.saveMessage()    [Room DB - async]
        │
        ├──▶ MemoryManager.retrieveContext()         [Embedding search]
        │       │
        │       └──▶ CosineVectorIndex.search()      [In-memory]
        │       └──▶ Keyword match fallback          [DB query]
        │
        ├──▶ EngineRepository.buildChatPrompt()      [Family chat template]
        │
        └──▶ INFERENCEROUTING
                │
        ┌───────┴────────┐
        ▼                ▼
  Local Path         Cloud Path
  EngineRepository   CloudGateway.streamChat()
        │                │
        ▼                ▼
  LiteRtLmEngine     LiteLLMClient
        │                │
        ▼                ▼
  LiteRT-LM runtime   SSE Stream
  (CPU XNNPACK /      → CloudStreamEvent[]
   GPU OpenCL)
        │                │
        ▼                ▼
  Flow<Result<StreamChunk>>    Flow<CloudStreamEvent>
        │                │
        └───────┬────────┘
                ▼
       ChatViewModel._messages.add()
                │
                ▼
          ChatScreen UI update
                │
                ▼
       MemoryManager.processExchange()  [async, 2s delay]
```

### Voice Assistant Flow

```
VoiceAssistantService.startLoop()
        │
        ▼
  AudioRecorder @ 16kHz mono
        │  Channel<FloatArray> (200ms chunks)
        ▼
  ┌─────────────────────────┐
  │    WAKE WORD PHASE      │
  │  SherpaOnnxWakeWord     │
  │  Engine.feed(chunk)     │
  └───────────┬─────────────┘
              │ KEYWORD DETECTED
              ▼
  ┌─────────────────────────┐
  │    ASR PHASE            │
  │  SherpaOnnxStreaming    │
  │  Recognizer.feed(chunk) │
  └───────────┬─────────────┘
              │ ENDPOINT (silence)
              ▼
  VoiceCommandRouter.match(transcript)
        │
   ┌────┴────┐
   ▼         ▼
 Local cmd  LLM route
   │         │
   ▼         ▼
System    ChatManager
command   .sendMessageStream()
   │         │
   └────┬────┘
        ▼
  ┌─────────────────┐
  │  Sentence       │
  │  Assembler      │
  └────────┬────────┘
           │ per-sentence
           ▼
  ┌─────────────────┐     ┌──────────────┐
  │ Piper TTS       │────▶│ AudioPlayer  │
  │ (VITS-LJS)      │     │ (AudioTrack) │
  └─────────────────┘     └──────┬───────┘
                                 │
                    VAD barge-in ─┘ (during playback)
```

LLM inference in the voice path is either the local **LiteRT-LM** engine or a
configured cloud provider via LiteLLM — the voice pipeline itself (wake word,
ASR, VAD, TTS) is entirely sherpa-onnx / whisper.cpp based and unchanged.

---

## Inference Engine Architecture

The `engine` module hosts the LiteRT-LM integration: a Kotlin-only stack that
wraps Google's LiteRT-LM runtime and exposes the app's inference contract.

### Class Hierarchy

```
InferenceEngine (interface)
    │
    └── LiteRtLmEngine (@Singleton)
            │
            ├── engine: com.google.ai.edge.litertlm.Engine
            │       (stateful — created per model load)
            ├── conversation: com.google.ai.edge.litertlm.Conversation
            │       (multi-turn state; KV cache persists across turns)
            ├── familyConfig: ModelFamilyConfig
            │       (family contract resolved from container metadata)
            ├── outputDecoder: OutputDecoder
            │       (stop-sequence cutting + special-token stripping)
            └── generationActive: AtomicBoolean

EngineRepository (interface)
    │
    └── DefaultEngineRepository (@Singleton)
            │  (adds Mutex serialization + state publishing)
            └── delegates to InferenceEngine
```

Hilt wiring in `engine/di/EngineModule.kt`:
`LiteRtLmEngine → InferenceEngine` and `DefaultEngineRepository → EngineRepository`.

### Lifecycle States

```
UNLOADED
     │
     │ loadModel(model, config)
     ▼
LOADING
     │
     ├──── success ───► READY
     └──── failure ───► ERROR
     │
     │ unloadModel() / closeConversation()
     ▼
UNLOADED
```

### Multi-Turn Conversation Strategy

A single stateful LiteRT-LM `Conversation` holds the chat session: the runtime
maintains the KV cache and applies the chat template internally across
`conversation.sendMessageAsync(text)` calls. `StreamChunk`s stream from
`Flow<Message>` partials; the engine strips special tokens and enforces stop
sequences via `StopSequenceTracker`.

**Context overflow recovery:** when the KV cache fills, LiteRT-LM throws
`INVALID_ARGUMENT: Input token ids are too long` instead of evicting. The engine
reseeds the conversation — dropping the oldest turns (chat path) or the stale
history (plain generation path) — and retries once, so long multi-turn
conversations keep working.

**Family template enforcement:** before each conversation creation the engine
sets `ExperimentalFlags.overwritePromptTemplate` to the family's official chat
template, so the prompt shape always matches what the model was trained on —
never the container's embedded template.

### Compat Layer (`engine/compat`)

The compatibility layer makes one runtime serve many model families without
touching the engine itself:

| Class | Responsibility |
|---|---|
| `ModelFamily` | Enum of supported families (Gemma, Qwen2, Qwen2.5, Qwen3, Phi, Llama 3, DeepSeek, Mistral, SmolLM, TinyLlama) with tokenizer kind, native-tool-marker support, and tool-advertisement caps |
| `ModelFamilyRegistry` | Resolves a `.litertlm` container to a `ModelFamilyConfig` — from `LlmMetadata` proto first, then template/stop-token signatures, then the model name |
| `ContainerMetadataReader` | Reads the LiteRT `LlmMetadata` proto embedded in the container file (family, context length, tokenizer) |
| `ModelFamilyConfig` | Per-family contract: official chat template, `SpecialTokens`, `GenerationDefaults`, optional thinking channel |
| `ChatTemplateRenderer` | Renders the family's chat template (used for prompt mirroring and planner prompts) |
| `SpecialTokens` | bos/eos and all forbidden-in-output token strings per family |
| `StopSequenceTracker` | Detects completed stop sequences in the streaming fragment and cancels the native decode |
| `OutputDecoder` | Strips special tokens and cuts output at stop sequences |
| `TokenizerFiles` | Tokenizer file names per family (BPE `tokenizer.json` vs SentencePiece `tokenizer.model`) |
| `ModelCompatibilityException` | Thrown when a container's family/template cannot be resolved |

Real context length is detected from container metadata at load time (e.g.
Qwen2.5-1.5B → 4096, Qwen3-0.6B → 2048) rather than trusted from catalog claims.

### Backends

`BackendType` semantics under LiteRT-LM:

| Value | Produced by LiteRT engine? | Meaning |
|---|---|---|
| `CPU` | ✅ yes | XNNPACK CPU delegate |
| `GPU` | ✅ yes | OpenCL-based LiteRT GPU delegate |
| `QUALCOMM_QNN`, `LLAMA_CPP_VULKAN`, `ONNX_RUNTIME`, `VULKAN` | ❌ never | Legacy enum values kept **only** for serializer/UI backward compatibility with old persisted state |

Automatic GPU→CPU fallback with corruption recovery is tracked in
`MemoryStats` (`gpuFree`, `gpuTotal`, `recoveryCount`, `backend == "gpu"`).
NPU (QNN) is the next planned backend — not yet implemented.

### Embeddings (`engine/embedding`)

- `LiteRtEmbeddingEngine` — raw LiteRT `CompiledModel` interpreter driving the
  EmbeddingGemma 300M `.tflite` model (768-dim, seq 512)
- `SentencePieceTokenizer` — Gemma 3 unigram tokenizer (262k vocab) for
  token-id conversion before embedding

### Diagnostics (`engine/diagnostics`)

- `RuntimeLogger` — tag-scoped logger under the stable `AndroLLM-Engine` logcat tag

### Utils (`engine/utils`)

- `MemoryEstimator` — predicts RAM requirements from model metadata
- `ThreadManager` — thread-count recommendation and background-priority scoping
- `CoherenceChecker` — post-load self-test probe (temperature-0) detecting
  tokenizer/weight corruption
- `LiteRtValidator` — pre-load validation of `.litertlm` containers
- `ModelResourceGuard` — refuses loads that would exceed available RAM

---

## Memory System Architecture

### Components

```
MemoryManager (public interface)
    │
    ├── MemoryRepository (orchestrator)
    │       │
    │       ├── EmbeddingProvider (interface)
    │       │       ├── RoutingEmbeddingProvider
    │       │       │       ├── CloudEmbeddingProvider (LiteLLM)
    │       │       │       └── LiteRtEmbeddingProvider (LiteRtEmbeddingEngine)
    │       │       │
    │       │       └── CosineVectorIndex (brute-force in-memory)
    │       │
    │       ├── MemoryIntelligence (interface)
    │       │       ├── RoutingMemoryIntelligence
    │       │       │       ├── CloudMemoryIntelligence (LiteLLM)
    │       │       │       └── LocalMemoryIntelligence (local LLM)
    │       │       │
    │       │       └── MemorySettingsStore
    │       │
    │       └── ContextBuilder (formats memories for system prompt)
    │
    ├── Room DB (separate instance, lazy-open)
    │       ├── MemoryEntity
    │       ├── EmbeddingEntity (BLOB)
    │       ├── ProjectEntity
    │       ├── TagEntity
    │       ├── MemoryTagCrossRef
    │       ├── RelationshipEntity
    │       └── SummaryEntity
    │
    └── WorkManager (background indexing)
            └── MemoryIndexingWorker
```

### Write Pipeline

```
processExchange(exchange):
  1. extract → List<ExtractedMemory>    (JSON schema contract)
  2. For each memory:
     a. embed content                   (if provider available)
     b. Check similarity threshold      (vs existing memories in category)
     c. if match ≥ threshold: merge tags/importance
     d. else if exact content match: update
     e. else: insert new (UUID, upsert entity + embedding)
  3. Link written memories "related_to" relationships
  4. maybeSummarize(exchange)            (if interval reached)
```

### Retrieval Algorithm

```
retrieve(query, filters, topK):
  1. Get candidate IDs from filters (category, project, tags, pinned, importance)
  2. Keyword match IDs from content + tag LIKE queries
  3. If vector index exists AND embedding provider available:
     a. Embed query text
     b. Vector search (topK×3 candidates) → cosine similarity scores
     c. Merge with keyword IDs → hybrid boost (+0.06)
     d. Sort: score + keyword_boost, then pinned, importance, recency
  4. Else: keyword/recency fallback sort
  5. Bump access timestamps on results
```

Local embeddings run through `LiteRtEmbeddingEngine` (LiteRT CompiledModel API)
— the former llama.cpp GGUF embedding handle was removed with the migration.

---

## Cloud Provider Architecture

### Provider Resolution Flow

```
Chat request
      │
      ▼
CloudGateway.streamChat()
      │
      ├── ProviderManager.resolveChatTarget()
      │       ├── Get default provider ID from CloudSettings
      │       ├── Find provider in providers list
      │       ├── Decrypt API key via KeyCipher
      │       └── Merge custom model overrides
      │
      ├── LiteLLMClient.streamChat(url, headers, body)
      │       ├── Build request (OpenAI-compatible format)
      │       ├── Add X-Accel-Buffering: no header
      │       └── Stream SSE response via Retrofit Call<ResponseBody>
      │
      └── StreamingParser.consumeLines()
              ├── Parse "data:" lines
              ├── Handle multi-line payloads
              └── Emit CloudStreamEvent sealed interface:
                      Delta(text)
                      Reasoning(text)
                      ToolCallDelta(...)
                      Usage(...)
                      Done
```

### KeyCipher (API Key Encryption)

```kotlin
class AndroidKeyCipher @Inject constructor(context: Context) : KeyCipher {
    private val keyAlias = "androllm_cloud_api_keys"

    override fun encrypt(plaintext: String): String {
        // 1. Generate random 12-byte IV
        // 2. AES-256/GCM encrypt with Keystore-backed key
        // 3. Prepend IV to ciphertext
        // 4. Base64 encode
        // Raw key NEVER leaves Keystore
    }

    override fun decrypt(ciphertext: String): String {
        // 1. Base64 decode
        // 2. Split IV (first 12 bytes) from ciphertext
        // 3. AES-256/GCM decrypt
        // Returns empty string for empty input (identity behavior)
    }
}
```

---

## Database Schema

### Main Database (AppDatabase, version 5)

```sql
-- conversations
CREATE TABLE conversations (
    id TEXT PRIMARY KEY,
    title TEXT,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    last_message_preview TEXT,
    message_count INTEGER DEFAULT 0,
    is_pinned INTEGER DEFAULT 0,
    is_archived INTEGER DEFAULT 0
);

-- messages
CREATE TABLE messages (
    id TEXT PRIMARY KEY,
    conversation_id TEXT NOT NULL,
    role TEXT NOT NULL,
    content TEXT NOT NULL,
    timestamp INTEGER NOT NULL,
    is_pending INTEGER DEFAULT 0,
    model_id TEXT,
    is_bookmarked INTEGER DEFAULT 0,
    origin TEXT DEFAULT 'TYPED',  -- TYPED | VOICE | AUTOMATION
    FOREIGN KEY (conversation_id) REFERENCES conversations(id)
);

-- models
CREATE TABLE models (
    id TEXT PRIMARY KEY,
    name TEXT UNIQUE NOT NULL,
    description TEXT,
    file_path TEXT NOT NULL,
    file_size INTEGER,
    format TEXT DEFAULT 'GGUF',   -- legacy default; catalog models are LITERTLM
    parameters TEXT,
    quantization TEXT,
    context_length INTEGER,
    download_url TEXT,
    is_downloaded INTEGER DEFAULT 0,
    is_loaded INTEGER DEFAULT 0,
    download_status TEXT DEFAULT 'NOT_DOWNLOADED',
    status TEXT DEFAULT 'NOT_LOADED',
    sha256 TEXT,
    architecture TEXT,
    family TEXT,
    license TEXT,
    min_ram_gb REAL,
    recommended_ram_gb REAL,
    is_favorite INTEGER DEFAULT 0,
    is_default INTEGER DEFAULT 0,
    added_date INTEGER,
    last_used_date INTEGER
);

-- settings
CREATE TABLE settings (
    id TEXT PRIMARY KEY DEFAULT 'app',
    theme TEXT DEFAULT 'SYSTEM',
    language TEXT DEFAULT 'en',
    storage_path TEXT,
    developer_mode INTEGER DEFAULT 0,
    first_launch INTEGER DEFAULT 1,
    model_path TEXT,
    gemini_api_key_encrypted TEXT
);
```

### Memory Database (MemoryDatabase, separate instance)

```sql
-- memories
CREATE TABLE memories (
    id TEXT PRIMARY KEY,
    content TEXT NOT NULL,
    category TEXT,
    importance REAL DEFAULT 0.5,
    project TEXT,
    tags TEXT DEFAULT '[]',
    pinned INTEGER DEFAULT 0,
    archived INTEGER DEFAULT 0,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    access_count INTEGER DEFAULT 0
);

-- embeddings
CREATE TABLE embeddings (
    memory_id TEXT PRIMARY KEY,
    vector BLOB NOT NULL,  -- FloatArray serialized
    dimension INTEGER NOT NULL,
    FOREIGN KEY (memory_id) REFERENCES memories(id)
);

-- summaries
CREATE TABLE summaries (
    conversation_id TEXT PRIMARY KEY,
    summary TEXT NOT NULL,
    generated_at INTEGER NOT NULL,
    token_count INTEGER DEFAULT 0
);

-- tags, projects, relationships tables...
```

---

## Navigation Architecture

### Route Registry

| Route | Parameters | Destination |
|---|---|---|
| `splash` | — | SplashScreen |
| `onboarding` | — | OnboardingScreen |
| `auth` | — | FirebaseAuthScreen |
| `profile_setup` | — | ProfileSetupScreen |
| `home` | — | HomeScreen |
| `chat` | — | ChatScreen (new conversation) |
| `chat/{conversationId}` | `conversationId` | ChatScreen (existing) |
| `chat/prompt/{prompt}` | `prompt` | ChatScreen (with prefilled prompt) |
| `models` | — | ModelsScreen |
| `models/{modelId}` | `modelId` | Navigate up (detail inline) |
| `settings` | — | SettingsScreen |
| `profile` | — | ProfileScreen |
| `prompts` | — | PromptLibraryScreen |
| `developer` | — | DeveloperScreen |
| `cloud/providers` | — | CloudProvidersScreen |
| `cloud/models/{providerId}` | `providerId` | CloudModelsScreen |

### Entry Flow

```
SplashScreen (checked auth + onboarding state)
    │
    ├── Authenticated + onboarded → HomeScreen
    ├── Not authenticated + onboarded → AuthScreen
    ├── Authenticated + not onboarded → OnboardingScreen
    └── Not authenticated + not onboarded → OnboardingScreen → AuthScreen
```

---

## Threading Model

| Operation | Thread | Mechanism |
|---|---|---|
| UI composition | Main | Compose runtime |
| ViewModel coroutines | Main (Dispatchers.Main) | `viewModelScope` |
| Database writes | Background | Room's internal thread pool |
| Database reads (Flow) | Configurable | `flowOn(Dispatchers.IO)` |
| Network requests | IO dispatcher | Ktor/OkHttp async |
| LiteRT inference | `Dispatchers.Default` | Blocking LiteRT calls never touch the main thread |
| Streaming token delivery | Main (throttled) | `delay(16ms)` for ~60fps |
| Voice audio capture | Dedicated daemon thread | `voice-capture` named thread |
| Background memory indexing | WorkManager | `MemoryIndexingWorker` |
| Model downloads | IO dispatcher | `ModelDownloadWorker` (WorkManager) |

**Critical rule:** LiteRT-LM blocking calls (conversation creation, `sendMessage`)
must never run on the main thread — the engine wraps them in
`withContext(Dispatchers.Default)`.

---

## Security Architecture

See [Security Architecture Deep Dive](security/security-architecture.md) for details.

Summary of security layers:

1. **Android Sandbox** — All app data in `/data/data/io.androllm.app/`
2. **Android Keystore** — AES-256/GCM encryption for API keys; key never leaves hardware
3. **HTTPS only** — All network traffic requires TLS 1.2+
4. **No cleartext** — `usesCleartextTraffic="false"` enforced
5. **Minimal permissions** — Only required permissions are requested
6. **No external analytics** — Zero third-party tracking

---

## Diagrams

### System Context Diagram

```
┌─────────────────────────────────────────────────────┐
│                   ANDROLLM APP                       │
│                                                      │
│  ┌──────────┐  ┌──────────┐  ┌──────────────────┐  │
│  │  Voice    │  │   Chat   │  │    Memory        │  │
│  │ Assistant │  │  Engine  │  │    System        │  │
│  └────┬─────┘  └────┬─────┘  └────────┬─────────┘  │
│       │             │                  │            │
│  ┌────▼─────────────▼──────────────────▼─────────┐  │
│  │         User Interface (Compose)               │  │
│  └──────────────────────────┬─────────────────────┘  │
│                             │                        │
│  ┌──────────────────────────▼─────────────────────┐  │
│  │           Data Persistence Layer                 │  │
│  │  (Room DB + DataStore + Keystore)               │  │
│  └──────────────────────────┬─────────────────────┘  │
└───────────────────────────┬───────────────────────────┘
                            │
         ┌──────────────────┼──────────────────┐
         │                  │                  │
         ▼                  ▼                  ▼
  ┌─────────────┐   ┌─────────────┐   ┌─────────────┐
  │  Local      │   │  Cloud AI   │   │  HuggingFace│
  │ .litertlm   │   │  Providers  │   │   Models    │
  │  Model      │   │  (LiteLLM)  │   │  (downloads)│
  │ (LiteRT-LM) │   │             │   │             │
  └─────────────┘   └─────────────┘   └─────────────┘
```