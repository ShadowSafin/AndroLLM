# LiteRT-LM Integration Guide

Technical deep dive into how AndroLLM integrates Google's LiteRT-LM runtime for
on-device inference.

---

## Overview

LiteRT-LM is Google's on-device LLM inference runtime — the LLM successor to
TensorFlow Lite. AndroLLM consumes it as a **pure Kotlin/Java dependency**
(`com.google.ai.edge.litertlm:litertlm-android`). There is no native code in
the inference path: no `src/main/cpp/` tree, no JNI, no NDK, no CMake. The
former llama.cpp + JNI bridge was removed in the LiteRT-LM migration.

The engine module (`:engine`) wraps three layers:

```
┌─────────────────────────────────────────────────────────────┐
│ Kotlin engine (io.androllm.engine)                          │
│  api/    — InferenceEngine, EngineRepository, EngineState  │
│  core/   — LiteRtLmEngine, NativeToolCallScanner           │
│  compat/ — family resolution, templates, decoding          │
│  backend/ — NPU/GPU/CPU selection, PerformanceProfiles     │
│  diagnostics/ — performance monitor, crash guard           │
│  embedding/ — LiteRtEmbeddingEngine (raw LiteRT)           │
├─────────────────────────────────────────────────────────────┤
│ LiteRT-LM Kotlin API  (com.google.ai.edge.litertlm)         │
│  Engine · Conversation · SamplerConfig · ConversationConfig │
├─────────────────────────────────────────────────────────────┤
│ LiteRT runtime (CPU XNNPACK / GPU OpenCL / NPU delegate)    │
└─────────────────────────────────────────────────────────────┘
```

---

## Dependencies

| Artifact | Version | Purpose |
|---|---|---|
| `com.google.ai.edge.litertlm:litertlm-android` | 0.16.0 | Chat/generation runtime (`.litertlm` containers) |
| `com.google.ai.edge.litert:litert` | 2.2.0 | Raw `CompiledModel` API for the embedding model |
| `org.tensorflow.lite:interpreter` | classic API | Classic `Interpreter` fallback surface |

Both are published to Google Maven and resolved normally by Gradle.

---

## LiteRtLmEngine

**File:** `engine/src/main/java/io/androllm/engine/core/LiteRtLmEngine.kt`

The `@Singleton` implementation of `InferenceEngine`, responsible for the
complete chat lifecycle:

### Loading a Model

1. **Validate** — `LiteRtValidator` checks the container header before anything
   else touches the file (corrupted/renamed files fail fast).
2. **Read metadata** — `ContainerMetadataReader` parses the embedded
   `LlmMetadata` proto (family, context length, tokenizer kind, stop tokens).
3. **Resolve family** — `ModelFamilyRegistry` maps `llm_model_type` →
   `ModelFamily` (+ `ModelFamilyConfig`: template, tokens, defaults).
4. **Estimate RAM** — `MemoryEstimator` + `ModelResourceGuard` refuse loads that
   exceed the device's available memory.
5. **Create the runtime Engine** with an `EngineConfig` (backend: GPU default)
   and a `ConversationConfig` (context length from container metadata).
6. **Apply the chat template** — the family template is forced via
   `ExperimentalFlags.overwritePromptTemplate` so the container template and the
   Kotlin-side renderer agree.
7. **Coherence probe** — a temperature-0 self-test (`CoherenceChecker`) verifies
   sane output before the UI marks the model ready.

### Generating

```kotlin
// Simplified
val conversation = Conversation.create(engine, conversationConfig)
conversation.appendMessage(role, text)        // user turn
engine.streamGenerate(conversation, samplerConfig) { partial, done ->
    // partial: token fragment (string, decoded)
    // done:    finish reason (STOP / MAX_TURNS / ERROR)
}
```

- `SamplerConfig` carries temperature, top-k, top-p, random seed per turn
  (`randomSeed: ULong` so same input → same output is reproducible).
- The `StopSequenceTracker` completes stop sequences that span stream
  fragments; `OutputDecoder` strips special tokens (`<bos>`, `<eos>`, …) and
  cuts at stop tokens.
- Generation runs on `Dispatchers.Default`; a 60-second stall watchdog and a
  120-second generation watchdog are armed per turn.

### Context Overflow Recovery

When a conversation fills the KV cache, LiteRT-LM raises
`INVALID_ARGUMENT: Input token ids are too long`. `sendMessageWithRetry`
responds automatically: it trims the oldest turns (preserving the system prompt
and recent messages), reseeds the `Conversation`, and retries once.

### Tool Calling

`NativeToolCallScanner` parses `<|tool_call|>` markers out of the native
output for Qwen/Gemma families — the model performs up to 3 tool rounds
natively (see [Tool calling](#tool-calling) below).

---

## Compat Layer

**Package:** `engine/src/main/java/io/androllm/engine/compat/`

| Class | Role |
|---|---|
| `ModelFamily` | Supported families: Gemma, Qwen2, Qwen2.5, Qwen3, Phi, Llama 3, DeepSeek, Mistral, SmolLM, TinyLlama |
| `ModelFamilyRegistry` | `llm_model_type` / template / name → family resolution |
| `ModelFamilyConfig` | Per-family contract: template, special tokens, defaults, tool-call support |
| `ContainerMetadataReader` | Defensive parser for the embedded `LlmMetadata` proto |
| `ChatTemplateRenderer` | Mirrors the family template (renders the same prompt the runtime builds) |
| `ChatTemplates` | Official template strings per family |
| `SpecialTokens` | `bos`/`eos`/forbidden token sets per family |
| `StopSequenceTracker` | Completes multi-fragment stop sequences |
| `OutputDecoder` | Special-token stripping + stop cutting |
| `TokenizerFiles` | Tokenizer file names per family |
| `ModelCompatibilityResolver` | Load-time compatibility checks; throws `ModelCompatibilityException` when unresolved |

### Context Detection

The **real context length is read from container metadata at load time**, not
from the catalog. Measured on device:

| Model | Detected context |
|---|---|
| Qwen2.5-1.5B | 4096 |
| Qwen3-0.6B | **2048** (overflows with the full tool advertisement — see below) |

---

## Embeddings (Raw LiteRT)

**File:** `engine/src/main/java/io/androllm/engine/embedding/LiteRtEmbeddingEngine.kt`

Chat uses LiteRT-LM; embeddings use the **raw LiteRT `CompiledModel` API** on a
regular `.tflite` model — EmbeddingGemma 300M:

- Model: `embeddinggemma-300M_seq512_mixed-precision.tflite` (~171 MB)
- Dimensions: 768 per sentence
- Tokenizer: `SentencePieceTokenizer` (Gemma 3 unigram, 262k vocab)
- Sequence length: 512; pooled output normalized before cosine search
- Runs on `Dispatchers.Default`; `LiteRtEmbeddingProvider` (core:memory) routes
  to it as the local embedding backend

---

## Tool Calling

Two paths, sharing one executor/confirmation/trace pipeline:

1. **Native tool calling** (Qwen2/2.5/3, Gemma): the model emits
   `<|tool_call|>` markers inline; `NativeToolCallScanner` extracts each call,
   the app executes it, appends the result, and continues — up to 3 rounds.
2. **JSON-compat fallback** (`ToolPlanner.planLocal`): for models without
   native markers, a small JSON-planning prompt (token budget 512, temperature
   0.1) produces `{ "calls": [...] }`; `ToolCallParser` tolerates fences,
   prose, and truncation.

Context budgeting for tool advertisements:

- `TOOL_AD_CAP_SMALL_MODEL = 4500` chars (~1100 tokens) for Qwen2/2.5/3
  families whose small repacks degrade with long tool lists
- Gemma 4B handles the full list; Qwen3-0.6B (2048 real context) still needs
  the cap

---

## Engine Bindings

**File:** `engine/src/main/java/io/androllm/engine/di/EngineModule.kt`

```kotlin
@Binds @Singleton
fun bindInferenceEngine(impl: LiteRtLmEngine): InferenceEngine

@Binds @Singleton
fun bindEngineRepository(impl: DefaultEngineRepository): EngineRepository
```

---

## Diagnostics

`RuntimeLogger` (engine/diagnostics) writes to the **`AndroLLM-Engine`** logcat
tag with stage timings and generation stats. Additional diagnostics:

- `EnginePerformanceMonitor` — lock-free pipeline-stage profiler tracking
  model init, container read, conversation creation, first-token latency,
  warmup. Exposes min/max/avg stats per stage.
- `EngineCrashGuard` — records every exception in the inference pipeline,
  auto-disables backends after 3 consecutive failures, provides crash
  telemetry ring buffer and summary.
- `EngineDiagnostics` + `EngineDiagnosticsCollector` — aggregates performance,
  memory, backend, and crash telemetry into a single snapshot for the
  developer diagnostics panel.
- `EngineDebugInfo` — backend (NPU/GPU/CPU), fallback history, MemoryStats
  (gpuFree, gpuTotal, recoveryCount), tokens/sec, time-to-first-token, load
  time.
- `RuntimeLogger.wRateLimited()` — rate-limited warnings to reduce logcat
  overhead during streaming.
- Conditional verbose/debug logging via `Log.isLoggable` guard.

---

## Error Model

The engine throws typed `EngineException` subtypes (`ModelCompatibilityException`,
context-overflow errors, GPU delegate errors, …) instead of crashing; the
repository layer maps them to `Result.Failure`, and the UI surfaces friendly
messages. See [Error Handling](../development/error-handling.md).

---

## Adding a New Family

1. Add the enum entry to `ModelFamily.kt`
2. Map the container's `llm_model_type` in `ModelFamilyRegistry.kt`
3. Provide the chat template in `ChatTemplates.kt`
4. Set special tokens / stop tokens in `SpecialTokens.kt`
5. Add unit tests under `engine/src/test/.../compat/`

See also [Model Formats](model-formats.md) and [Acceleration](acceleration.md).