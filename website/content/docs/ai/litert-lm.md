# LiteRT-LM Engine

Deep dive into the LiteRT-LM inference runtime and how AndroLLM's engine module drives it.

---

## Overview

AndroLLM's local inference runs on **LiteRT-LM 0.16.0**
(`com.google.ai.edge.litertlm:litertlm-android:0.16.0`), Google's on-device LLM
inference runtime, alongside **LiteRT 2.2.0** for embeddings (raw
`CompiledModel` API). The engine is **100% Kotlin/Java** — there is no native
code, no NDK, no CMake, and no vendored llama.cpp.

All engine code lives in
`engine/src/main/java/io/androllm/engine/`.

---

## Runtime Artifacts

| Artifact | Coordinate | Used For |
|---|---|---|
| LiteRT-LM 0.16.0 | `com.google.ai.edge.litertlm:litertlm-android:0.16.0` | LLM inference (`.litertlm` containers) |
| LiteRT 2.2.0 | `com.google.ai.edge.litert:litert-android` | Embeddings via the raw `CompiledModel` API |

Both are fetched from Google's Maven repository — no local builds, no native
toolchains.

---

## Engine Packages

```
engine/src/main/java/io/androllm/engine/
├── api/          InferenceEngine, EngineRepository, DefaultEngineRepository, EngineState
├── core/         LiteRtLmEngine + compat layer + PrefixCache + BufferPool
├── compat/       ModelFamily, ModelFamilyRegistry, ModelFamilyConfig,
│                 ContainerMetadataReader, ChatTemplateRenderer, SpecialTokens,
│                 OutputDecoder, StopSequenceTracker, TokenizerFiles,
│                 ModelCompatibilityException
├── models/       EngineModelInfo, EngineConfig, GenerationConfig, EngineCapabilities,
│                 EngineDebugInfo, EngineException, EngineStats, MemoryStats,
│                 BackendType, ModelLoadConfig, ChatPromptMessage, StreamChunk
├── backend/      BackendSelector, HardwareBackendProbe, InferenceBackend,
│                 NpuVendor, PerformanceProfiles
├── diagnostics/  RuntimeLogger, EnginePerformanceMonitor, EngineCrashGuard,
│                 EngineDiagnostics, EngineDiagnosticsCollector
├── embedding/    LiteRtEmbeddingEngine, SentencePieceTokenizer
├── memory/       ContextManager (adaptive context sizing, KV-cache estimation)
└── utils/        MemoryEstimator, ThreadManager (device-class-adaptive,
                  maximum safe core allocation), CoherenceChecker,
                  LiteRtValidator, ModelResourceGuard
```

### The two public faces

```
InferenceEngine (interface — loadModel, generateChatStream, cancel, …)
    │
    └── LiteRtLmEngine (@Singleton, core/) — wraps the LiteRT-LM runtime

EngineRepository (interface — facade with Mutex serialization)
    │
    └── DefaultEngineRepository (@Singleton)
            ├── serializes concurrent generate calls with a Mutex
            └── publishes lifecycle state via EngineState (StateFlow)
```

`ChatViewModel` and the voice `ChatManager` always talk to
`EngineRepository`, never to the runtime directly.

---

## LiteRtLmEngine Lifecycle

```
UNINITIALIZED
     │
     │ initialize(config)
     ▼
INITIALIZED
     │
     │ loadModel(model, loadConfig)      ── validation via LiteRtValidator,
     │                                      family resolution, tokenizer files
     ▼
MODEL_LOADING
     │
     ├── success ──► MODEL_LOADED
     └── failure ──► MODEL_ERROR
```

- **initialize** — prepares the LiteRT-LM runtime and the chosen backend
  (CPU XNNPACK or the OpenCL-based GPU delegate).
- **loadModel** — `ModelLoadConfig` drives loading; the compat layer reads the
  container, resolves the model family, and wires chat templates, special
  tokens, and stop sequences before generation can start.
- **generateChatStream** — renders the prompt from the family chat template,
  streams decoded tokens through `OutputDecoder`, and stops on family stop
  tokens or `StopSequenceTracker` hits.
- **cancel / unload** — cooperative cancellation plus `ModelResourceGuard`
  bookkeeping so a cancelled turn never leaks a loaded runtime session.

---

## The Compat Layer (`core/compat`)

`.litertlm` containers are family-agnostic: the runtime executes them, but the
app must know *how to talk to* the model. That is the compat layer's job —
everything is derived from the container's embedded `LlmMetadata` proto, never
hardcoded per file.

| Class | Responsibility |
|---|---|
| `ContainerMetadataReader` | Parses the container's `LlmMetadata` proto: family, context length, vocab, tokenizer files |
| `ModelFamily` / `ModelFamilyRegistry` | Family enum + registry mapping metadata → per-family behavior |
| `ModelFamilyConfig` | Per-family configuration (templates, tokenizers, quirks) |
| `ChatTemplateRenderer` | Renders system/user/assistant turns with the family's chat template |
| `SpecialTokens` | Per-family bos/eos/stop token ids and strings |
| `OutputDecoder` | Maps streamed token ids → text, filters control tokens |
| `StopSequenceTracker` | Detects stop sequences in the streamed output and halts generation |
| `TokenizerFiles` | Locates and loads the family's tokenizer artifacts from the container |
| `ModelCompatibilityException` | Raised when container metadata is unsupported or corrupt |

### Supported families

Detected from container metadata: **Gemma, Qwen2, Qwen2.5, Qwen3, Phi,
Llama3, DeepSeek, Mistral, SmolLM, TinyLlama**.

### Templates and tokens

- Chat templates are per-family (Gemma and Qwen use distinct roles/bos formats;
  Qwen3 adds thinking-mode handling).
- Special tokens (bos/eos/stop) come from the container metadata and are fed
  into `SpecialTokens`; the decoder and stop tracker rely on them for correct
  output boundaries.

---

## Context Detection

Real context length comes from the container metadata — the app never guesses.

| Model | Context (from metadata) |
|---|---|
| Qwen2.5-1.5B | 4096 |
| Qwen3-0.6B | 2048 |

The engine also **budgets** context for tool advertisement: `ToolPromptBuilder`
caps its advertisement at a 4500-character limit for small Qwen families so
the tool list never crowds out the conversation.

---

## Backends

| Backend | Runtime | Notes |
|---|---|---|
| CPU | LiteRT-LM on XNNPACK | Default, always available |
| GPU | OpenCL-based LiteRT GPU delegate | Faster on capable devices; automatic fallback to CPU |
| NPU | LiteRT NPU delegate (vendor dispatch) | Qualcomm Hexagon, MediaTek NeuroPilot, Google Tensor |

`BackendType` also carries **legacy values** (`QUALCOMM_QNN`,
`LLAMA_CPP_VULKAN`, `ONNX_RUNTIME`, `VULKAN`) — these are never produced by the
engine and exist only so persisted state serializers and older UI code can
still read historical values.

GPU→CPU fallback and corruption recovery are tracked in `MemoryStats`
(`gpuFree`, `gpuTotal`, `recoveryCount`). See
[Acceleration](acceleration.md) for details.

---

## Debug Prompt Logging

The engine's `RuntimeLogger` (tag prefix `AndroLLM-Engine`) can log the exact
rendered prompt — after the chat template, memory context, and tool
advertisement are applied — for debugging what the model actually sees:

```kotlin
RuntimeLogger.debug("prompt") { renderedPrompt.take(2000) }
```

Enable prompt-level debug logging from Developer settings; logs appear under
the `AndroLLM-Engine` tag in logcat.

---

## EngineException Taxonomy

| Exception | Raised When |
|---|---|
| `ModelCompatibilityException` | Container family/arch unsupported or metadata corrupt |
| `ModelLoadException` | Runtime failed to load the `.litertlm` file |
| `ContextOverflowException` | Input tokens exceed the model's context window (logcat: `Input token ids are too long`) |
| `GenerationException` | Decode failed mid-stream (backend error, OOM, corrupted state) |
| `BackendFallbackException` | GPU delegate failed and the engine fell back to CPU |
| `CancellationException` | Generation was cancelled by the user — silent by design |

All engine failures surface through `Result<StreamChunk, EngineException>` —
the engine never throws across the repository boundary.

---

## Threading and Core Allocation

- LiteRT-LM inference runs on a backend thread owned by `LiteRtLmEngine`;
  token delivery is throttled to ~60 fps (16 ms interval) for the UI.
- `DefaultEngineRepository` serializes concurrent `generate` calls with a
  `Mutex` — chat generation, background memory extraction, and benchmarks
  never interleave.
- `ThreadManager` handles runtime thread/affinity setup with **maximum safe
core allocation**: on modern 8–12 core SoCs the engine uses 6–12 inference
threads rather than the previous conservative cap of 4. The engine detects
performance vs. efficiency core ratios and adapts per device tier.
- `PrefixCache` reuses prompt prefixes across turns to avoid re-tokenizing
identical system prompts and chat template headers.
- `BufferPool` provides bounded pools of reusable StringBuilders, ByteArrays,
and CharArrays for the inference pipeline, eliminating per-token allocations
during streaming and JNI transfer.

---

## See Also

- [Model Formats](model-formats.md) — the `.litertlm` container format and catalog
- [Acceleration](acceleration.md) — CPU vs GPU, fallback, memory stats
- [Performance Guide](../PERFORMANCE.md) — tok/s, context budgeting, RAM