# AndroLLM Architecture

Complete architectural overview of the AndroLLM application.

---

## Design Philosophy

AndroLLM is built on three core principles:

1. **Privacy by default** — Local inference runs entirely on-device; cloud features are opt-in
2. **Modular independence** — Each feature module is a self-contained unit depending only on core libraries
3. **Graceful degradation** — Every feature has a fallback path (Vulkan→CPU, cloud→local, embeddings→keywords)

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
│                          NATIVE LAYER                               │
│  llama.cpp (C++) · sherpa-onnx (ONNX Runtime Mobile)               │
│  libandrollm_llama.so · Vulkan shaders · ONNX models               │
└─────────────────────────────────────────────────────────────────────┘
```

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
│  (shared)     │  │  (native)     │  │  (screens)    │
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
        ├──▶ EngineRepository.buildChatPrompt()      [Jinja template]
        │
        └──▶ INFERENCEROUTING
                │
        ┌───────┴────────┐
        ▼                ▼
  Local Path         Cloud Path
  EngineRepository   CloudGateway.streamChat()
        │                │
        ▼                ▼
  LlamaCppEngine     LiteLLMClient
        │                │
        ▼                ▼
  LlamaJniBridge     SSE Stream
  nativeGenerate()   → CloudStreamEvent[]
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

---

## Inference Engine Architecture

The engine module is the bridge between the Kotlin application layer and the native C++ inference runtime.

### Class Hierarchy

```
InferenceEngine (interface)
    │
    └── LlamaCppEngine (@Singleton)
            │
            ├── engineHandle: Long (native pointer)
            ├── vulkanSupported: Boolean
            └── generationActive: AtomicBoolean

EngineRepository (interface)
    │
    └── DefaultEngineRepository (@Singleton)
            │  (adds Mutex serialization + state publishing)
            └── delegates to InferenceEngine
```

### Lifecycle States

```
UNINITIALIZED
     │
     │ initialize(config)
     ▼
INITIALIZED
     │
     │ loadModel(model, config)
     ▼
MODEL_LOADING
     │
     ├──── success ───► MODEL_LOADED
     └──── failure ───► MODEL_ERROR
```

### Multi-Turn Conversation Strategy

The engine maintains a single `llama_context` across turns. The KV cache IS the conversation state.

**Continuation (new message only):**
```
1. Render new message + assistant prefix with Jinja template
2. Prefill-decode at current chatPosition
3. Update chatPosition += newly_generated_tokens
4. Return accumulated tokens
```

**Full re-render (edit/delete/regenerate/system prompt change):**
```
1. Reset chatPosition to 0
2. Render ALL messages with templates
3. Prefill entire sequence
4. Decode from start
```

**Context shift (when pos_check >= nCtx - 4):**
```
1. Discard oldest tokens after system prompt
2. Shift remaining tokens left in KV cache
3. Continue decoding from shifted position
```

---

## Native Engine Architecture (C++)

### LlamaEngine (RAII Struct)

```cpp
struct LlamaEngine {
    common_init_result_ptr initResult;       // Holds llama_model*
    llama_context_ptr ctxOwner;              // Holds llama_context*
    common_sampler* sampler;                 // Per-request sampler
    common_chat_templates_ptr chatTmpls;     // Jinja templates

    // Chat state
    std::vector<common_token> chatMsgs;
    size_t chatPosition = 0;
    size_t systemPromptEnd = 0;

    // Embedding model (separate handle)
    common_init_result_ptr embedInitResult;
    llama_model* embedModel = nullptr;
    llama_context* embedCtx = nullptr;

    // Corruption recovery tracking
    int recoveryCount = 0;
    bool cpuSessionFallback = false;
    bool vulkanDeviceLost = false;
};
```

### JNI Bridge Functions (`native_api.cpp`)

| Function | Kotlin Signature | Purpose |
|---|---|---|
| `nativeCreate` | `fun nativeCreate(configJson: String): Long` | Allocate LlamaEngine |
| `nativeLoadModel` | `fun nativeLoadModel(handle, path, cfg)` | Load GGUF + create context |
| `nativeGenerate` | `fun nativeGenerate(...)` | Single-turn prefill+decode |
| `nativeGenerateChat` | `fun nativeGenerateChat(...)` | Multi-turn diff continuation |
| `nativeApplyChatTemplate` | `fun nativeApplyChatTemplate(...)` | Jinja template rendering |
| `nativeResetChat` | `fun nativeResetChat(handle)` | Clear KV cache state |
| `nativeCancel` | `fun nativeCancel(handle)` | Set cancel flag mid-decode |
| `nativeUnload` | `fun nativeUnload(handle)` | Destroy context + model |
| `nativeRelease` | `fun nativeRelease(handle)` | Free LlamaEngine struct |
| `nativeWarmUp` | `fun nativeWarmUp(handle): String` | Compile GPU shaders |
| `nativeGetMemoryStats` | `fun nativeGetMemoryStats(handle): String` | RAM/GPU memory info JSON |
| `nativeBenchmark` | `fun nativeBenchmark(...)` | Quick performance benchmark |
| `nativeVulkanAvailable` | `fun nativeVulkanAvailable(): Boolean` | Device Vulkan capability check |
| Embedding variants | See below | Separate embedding model handle |

### Vulkan Validation & Corruption Recovery

After loading a model with GPU offloading:

```cpp
// Phase 1: Greedy test (temp=0) on 5 prompts
// Phase 2: Long-context test (forces KV shifts)
// Phase 3: Sampling tests (standard, typical_p, mirostat)
// Compare every sampled token + full logit vectors against CPU reference
// Report: "passed" / "failed" / "skipped"
```

Runtime corruption escalation ladder (in `decode_safe()` wrapper):
1. `VK_ERROR_DEVICE_LOST` → set `vulkanDeviceLost=true`, increment counter
2. After successful decode → clear `vulkanDeviceLost`
3. NaN/INF logits → recreate context on same GPU backend
4. If GPU recreation fails → reload on CPU (`cpuSessionFallback=true`)

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
    │       │       │       └── LlamaEmbeddingProvider (local GGUF)
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
    format TEXT DEFAULT 'GGUF',
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
| Native inference | Backend (separate thread) | Mutex-serialized; callback on IO |
| Streaming token delivery | Main (throttled) | `delay(16ms)` for ~60fps |
| Voice audio capture | Dedicated daemon thread | `voice-capture` named thread |
| Background memory indexing | WorkManager | `MemoryIndexingWorker` |
| Model downloads | IO dispatcher | `ModelDownloadWorker` (WorkManager) |

**Critical rule:** Never call JNI functions from the main thread during active generation. The engine uses a dedicated mutex to serialize `generate` and `generateQuiet` calls.

---

## Security Architecture

See [Security Architecture Deep Dive](docs/security/security-architecture.md) for details.

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
  │   Local     │   │  Cloud AI   │   │  HuggingFace│
  │  GGUF Model │   │  Providers  │   │   Models    │
  │  (on-device)│   │  (LiteLLM)  │   │  (downloads)│
  └─────────────┘   └─────────────┘   └─────────────┘
```
