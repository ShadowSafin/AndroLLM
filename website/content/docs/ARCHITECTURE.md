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
│                          AGENT LAYER                                │
│  Planner · Execution Engine · Tool Registry · Loop & Observation  │
│  Context Propagation · Working Memory · Retry · Validation · Permissions │
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
        ├──▶ AgentPlanner.createPlan()                [Goal→Graph, hidden unless dev mode]
        │       │
        │       ▼
        │   ToolRouter.route() + ToolRanker.rank()   [smallest set, health-ranked]
        │       │
        │       ▼
        │   ToolRunCoordinator.runLocalWorkflow()     [AGENT LOOP]
        │       ├──▶ Validation → Permission → Confirmation → Execution (sandbox + 20s timeout)
        │       ├──▶ Output validation + health update + confidence + structured log (dev)
        │       ├──▶ Working Memory (variableStore) + Context Propagation (agentContext)
        │       ├──▶ Parallel (async) vs Sequential (dependency graph)
        │       ├──▶ Conditional IF/ELSE evaluation
        │       ├──▶ Retry (3× backoff) → Alternative tool → Recovery
        │       └──▶ Loop Guard (12 total, 2 consecutive, dedupe, disable on failure)
        │       │
        │       └──▶ Need More Work? → re-plan → next tool(s) → Goal Complete?
        │
        └──▶ INFERENCEROUTING (tool results injected as system feedback)
                │
        ┌───────┴────────┐
        ▼                ▼
  Local Path         Cloud Path
  EngineRepository   CloudGateway.streamChat()
  (native tool_calls) │
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
       ChatViewModel._messages.add()   [never-blank guard grounded in tool results]
                │
                ▼
          ChatScreen UI update
                │
                ▼
       MemoryManager.processExchange()  [async, 2s delay]
```

### Chat Attachments Flow

Conversation-scoped file attachments (cloud models only) — the pipeline is
temporary by design: nothing is indexed, nothing is stored in a searchable
library, and the per-conversation cache is removed when the conversation is
deleted.

```
User
   │
   ▼
Attach Files                        [paperclip picker: Files / Images / Camera / Gallery]
   │
   ▼
Temporary Parsing                   [core:attachments — PDF/Office/text parsers + ML Kit OCR]
   │
   ▼
Conversation Context                [extracted text injected with the prompt;
   │                                  native image parts for vision providers]
   ▼
Cloud Model
   │
   ▼
Response
```

The whole feature is gated by capability flags (`supportsAttachments`,
`supportsVision`) — never provider names. Local models pass no cloud model
id, so every flag resolves to false: no parsing, no OCR, no uploads, and the
paperclip button and attachment settings are hidden. A request carrying
attachments is rejected at the ViewModel before it can reach a local
inference engine.

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

### Backend Selection (`engine/backend`)

- `BackendSelector` — determines ordered fallback chain (NPU → GPU → CPU) from
  probe results and model compatibility flags
- `HardwareBackendProbe` — startup hardware probe detecting SoC vendor, GPU
  identity, NPU availability, and vendor dispatch libraries
- `InferenceBackend` — sealed class with NPU/GPU/CPU backends, each building
  the native LiteRT-LM `Backend`
- `NpuVendor` — vendor detection (Qualcomm, MediaTek, Google Tensor)
- `PerformanceProfiles` — device-class-specific presets (LOW_END, MID_RANGE,
  FLAGSHIP, GPU/NPU/CPU_OPTIMIZED)

### Diagnostics (`engine/diagnostics`)

- `RuntimeLogger` — tag-scoped logger with rate-limited warnings and
  conditional verbose/debug logging
- `EnginePerformanceMonitor` — lock-free pipeline-stage profiler (model init,
  container read, conversation create, first-token latency, warmup)
- `EngineCrashGuard` — crash recording, backend auto-disable after 3
  failures, crash telemetry ring buffer
- `EngineDiagnostics` + `EngineDiagnosticsCollector` — aggregated diagnostics
  model for the developer panel

### Utils (`engine/utils`)

- `MemoryEstimator` — predicts RAM requirements from model metadata
- `ThreadManager` — device-class-adaptive threading with cached hardware info
  (5-tier classification: low→high)
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

## Agent Architecture

The agent layer is the autonomous orchestration system that turns a single user request into a complete multi-step workflow. It is the only layer that plans, chains, validates, and recovers tool calls — the inference engine and UI never execute tools directly.

```
User Request
     │
     ▼
 ┌──────────┐    Goal → Required Info → Required Tools → Order → Dependencies
 │  Planner │──→ Execution Graph (Research → Summarize → SMS Draft → Send → Verify)
 └────┬─────┘    internal only; hidden unless developer mode
      │
      ▼
 ┌──────────────┐   Tool Selection (smallest required set, health-ranked)
 │ Tool Registry│──→ ToolSpec: name, description, input/output schema, permission,
 └──────┬───────┘    cost, privacy, latency, failureModes, dependencies, capabilities
        │
        ▼
 ┌─────────────────┐  Validation → Permission → Confirmation → Execution → Observation
 │ Execution Engine│──→ Loop: Execute → Observe → Replan → Next Tool → Goal Complete?
 └────────┬────────┘    (parallel, conditional, retry, recovery, sandboxing)
          │
     ┌────┴─────────────────────────────┐
     │ Working Memory + Context Propagation │
     └──────────────────────────────────┘
```

### Component Map (`core/tools`)

| Component | Location | Responsibility |
|---|---|---|
| `AgentPlanner` | `agent/AgentPlanner.kt` | Builds internal `AgentPlan` + `ExecutionGraph` before any tool; splits sequential markers (`then, after, before, first/second/last, and then, once finished, after researching`) and detects parallel/conditional branches |
| `ToolPlanner` | `planner/ToolPlanner.kt` | LLM-driven tool selection (cloud `tools` array vs local JSON-grammar `{"calls":[...]}`); merges planner graph with router routing |
| `ToolRouter` | `router/ToolRouter.kt` | Deterministic keyword routing; composite union for multi-intent (`Research then SMS → WEB+COMMUNICATION`), confidence scoring, sequential/parallel markers |
| `ToolRegistry` | `registry/ToolRegistry.kt` | Single source of truth `Map<String, Tool>`; Hilt multibinding `Set<Tool>`, alias normalization, strict validation |
| `ToolRunCoordinator` | `coordinator/ToolRunCoordinator.kt` | Provider-agnostic glue: multi-round workflow, cloud `role=tool` chunking, local feedback injection |
| `ToolLoopGuard` | `coordinator/ToolLoopGuard.kt` | Per-turn guard: total cap 12, consecutive cap 2, dedupe `(name,args)`, disable on 2 failures; injects `stopReason` |
| `ToolExecutor` | `executor/ToolExecutor.kt` | **Only** executor: permission gate → confirmation gate → timeout (20s default, per-tool override) → sandboxed execution |
| `ToolHealthMonitor` | `monitoring/ToolHealthMonitor.kt` | Tracks `avgLatency, failureRate, timeoutRate, successRate, lastSuccess`; `healthScore 0..1` for ranking |
| `ToolRanker` | `monitoring/ToolRanker.kt` | Ranks candidates by `health 40 + speed 15 + cost 10 + privacy 10 + local 10 + available 5 + queryHits` |
| `ToolCallValidator` | `validation/ToolCallValidator.kt` | Strict JSON-schema validation (`required, types, enums, extra fields, nullable`) |
| `ToolOutputValidator` | `validation/ToolOutputValidator.kt` | Validates every tool output (rejects `{}`, blank, missing `temperature/rain` for weather, missing `path` for files) |
| `PromptInjectionDetector` | `validation/PromptInjectionDetector.kt` | Sanitizes hidden tool syntax in user prompts and tool outputs |
| `ToolExecutionLogger` + `ToolExecutionTraceStore` | `validation/ToolExecutionLogger.kt`, `trace/ToolExecutionTraceStore.kt` | Structured logs: `executionId, goal, planner, toolSelected, arguments, executionTime, result, validation, nextStep, finalStatus, confidence` (dev mode only) |
| `AgentVariableStore` | `agent/AgentVariableStore.kt` | Per-conversation `Map<String,String>` scoped to turn, reset on `beginTurn(scope)` |
| `AgentContextBuilder` | `agent/AgentContextBuilder.kt` | Injects `CURRENT CONTEXT` block (time, battery, clipboard, foreground app, device, network, variables) each round |
| `ClarificationEngine` | `clarification/ClarificationEngine.kt` | Asks only missing info: `"Which Dad contact should I message?"` not `"Can you clarify?"` |
| `DeviceContextProvider` | `agent/DeviceContextProvider.kt` | Collects live facts never asked from user |

### Execution Loop & Observation

```
User
 ↓
Planner (internal graph) ──┐
 ↓                         │
Tool Selection (registry + router + ranker + health) │
 ↓                         │
Execute Tool (validation → permission → confirmation → sandbox + timeout) │
 ↓                         │
Observe Result (output validation → confidence 0..1 → health update → structured log) │
 ↓                         │
Need More Work? ── YES ────┘
 ↓ NO
Goal Completed / User interaction / Permission / Unrecoverable / Safety
 ↓
Final Response (grounded in tool results)
```

- **Goal-oriented**: the loop continues until the *final goal* (e.g. `Dad receives SMS`) not just `search done`; `ToolRunCoordinator.runLocalWorkflow` asks internally *“Does original request require additional actions?”* and injects `"Reminder still needs …"` to prevent early exit.
- **Replanning**: after every tool the current history + feedback + context block is re-fed to `ToolPlanner.planLocal`; the model may emit `[]` (done) or next tool(s).

### Dependency Resolution & Context Propagation

- Sequential markers (`then, after, next, finally, before, first/second/last, and then, once finished, after researching, before sending`) enforce order `Search → Read → Summarize → Email` never reversed; enforced by `orderByDependencies` using the planner graph.
- Every tool receives `original request + conversation history + previous tool outputs (via feedback system message) + relevant memory + execution state (variables)` — no tool runs in isolation.
- Working memory `AgentVariableStore` persists `weather, search_results, last_tool_output` for the turn; `ToolRunCoordinator.storeToolResultMemory` writes each success, next tool reads via `variable_get`.

### Tool Selection, Ranking & Validation

1. `ToolRouter.route(query, hasAttachments, enabledTools)` classifies intents (`ATTACHMENT, MATH, DEVICE, WEB, COMMUNICATION, GENERAL`) and unions composite requests (`Research then SMS → WEB+COMMUNICATION`) — smallest required set.
2. `ToolRanker.rank(candidates, query)` scores by `health, speed (estimated + measured), reliability, cost, privacy, local preference`.
3. `ToolCallValidator` checks `tool exists, name non-empty, JSON schema`; `ToolOutputValidator` checks outputs; `PromptInjectionDetector` sanitizes.
4. `ToolRegistry` declares each `ToolSpec` with `name, description, parameters (JSON Schema), permission, requiresConfirmation, category, capabilities, estimatedLatencyMs, cost, privacyLevel, failureModes, dependencies, supportedBackends, availableOnDevice`.

### Retry Manager, Health & Recovery

- **Retry with backoff**: `TOOL_MAX_ATTEMPTS=3`, `RetryPolicy(initial 500ms, max 8000ms, jitter 150ms)`, never for confirmation-gated or non-retryable.
- **Alternative tool**: after retries, `findAlternativeTool` picks health-ranked alternative with same `permission/category`.
- **Health**: `ToolHealthMonitor` tracks `avgLatency (EMA), failureRate, timeoutRate, successRate, lastSuccessAt`; unhealthy tools are deprioritized.
- **Recovery without restart**: handles `timeouts, rate limits (backoff), network failures (retry), permission changes (clear error), API failures (retry/alt), malformed responses (output validation), missing params (clarification)` — the workflow continues from the next step, not from scratch.

### Working Memory & Loop Protection

- `AgentVariableStore.beginTurn(scope)` clears per conversation; variable tools `variable_set/get` implement `WHILE index<n` and `FOR EACH` loops.
- `ToolLoopGuard` prevents infinite execution: `repeated tool calls, repeated reasoning, repeated retries, circular execution` → safe abort with explanation.

### Sandboxing & Permission

- `ToolExecutor` isolates every call in `withTimeout` + `try/catch`; one failed tool returns `ToolResult.Failure` without crashing the agent.
- Permission manager: Settings → Automation master switch + per-tool toggles `ToolPermission` → Android runtime permissions (`SEND_SMS, READ_CONTACTS, CALL_PHONE, CALENDAR, RECORD_AUDIO, LOCATION`) requested lazily via confirmation card; confirmation required only for `SMS, Phone Calls, Payments, Email, Calendar Changes, Deleting Files, System Changes, External API with side effects`; everything else auto-executes.

### Streaming & Logging

- **Streaming**: `onActivity` chips (`Planning…, Searching Web…, Reading Sources…, Summarizing…, Preparing SMS…, Waiting for confirmation…, Sending…, Done`) and `ToolEvent(Started/Succeeded/Failed/Declined)` render live per-tool cards; throttled to ~60fps, never truncated.
- **Developer logs**: `ToolExecutionLogger.StructuredLog` + `ToolExecutionTraceStore` (200 entries) show `executionId, goal, planner (hidden reasoning), toolSelected, arguments, executionTime, result, validation, nextStep, finalStatus, confidence` — available in Developer → Tool Debug, not exposed to normal users.

### Interaction Without Exposing Reasoning

The planner's internal `AgentPlan` and hidden reasoning are never added to the user-visible chat; only `ToolPromptBuilder.advertisement()` (tool names + args) and context block are injected as system messages. Developer mode is the sole surface for graph inspection.

Implementation: [`ToolRunCoordinator.kt`](../../core/tools/src/main/java/io/androllm/core/tools/coordinator/ToolRunCoordinator.kt), [`AgentPlanner.kt`](../../core/tools/src/main/java/io/androllm/core/tools/agent/AgentPlanner.kt), [`ToolPlanner.kt`](../../core/tools/src/main/java/io/androllm/core/tools/planner/ToolPlanner.kt), [`ToolRegistry.kt`](../../core/tools/src/main/java/io/androllm/core/tools/registry/ToolRegistry.kt)

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