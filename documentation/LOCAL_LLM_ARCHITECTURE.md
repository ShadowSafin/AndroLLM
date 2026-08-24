# Local LLM Architecture

This document describes the on-device LLM subsystem as it exists today. It is
the single source of truth for how local inference works, what is pinned, and
why it is structured the way it is.

---

## 1. Pinned upstream runtime

AndroLLM executes local models on **LiteRT-LM** (Google AI Edge), consumed as
Maven artifacts — **no native code, no NDK, no CMake, and no vendored C++
tree** (the former llama.cpp + JNI stack was removed in the migration).

| | |
|---|---|
| Runtime | `com.google.ai.edge.litertlm:litertlm-android:0.16.0` (LiteRT-LM) |
| Embeddings | LiteRT `2.2.0` raw runtime — `CompiledModel` API (`litert-android`) |
| Language | 100% Kotlin on the app side; the runtime is a Google AAR |
| Policy | The AAR is pinned in `engine/build.gradle.kts`; bump versions deliberately |
| Build | Plain Gradle build — no NDK/CMake/Vulkan SDK toolchain required |

The engine reports its identity at runtime (`EngineCapabilities.name` /
`version`, e.g. "LiteRT-LM 0.16.0") so a debug screen can always answer
"which runtime is running?".

---

## 2. One implementation, one path

There is exactly **one** local inference path:

```
feature/chat  ──►  EngineRepository (facade)
                        │
                        ▼
              DefaultEngineRepository   (serialization, watchdogs, state)
                        │
                        ▼
              LiteRtLmEngine            (engine impl, session lifecycle)
                        │
                        ▼
              LiteRT-LM runtime         (Conversation / Channel / Engine APIs)
                        │
                        ├── Backend.GPU  (OpenCL-based LiteRT GPU delegate)
                        └── Backend.CPU  (XNNPACK)
```

Supporting layers in `engine/src/main/java/io/androllm/engine/`:

- `api/` — `InferenceEngine`, `EngineRepository`, `DefaultEngineRepository`
  (facade + state machine + serialization), `EngineState`.
- `core/` — `LiteRtLmEngine`: load, conversation, streaming, cancellation.
- `compat/` — the compatibility layer that maps containers and models onto
  families: `ModelFamily`, `ModelFamilyRegistry`, `ModelFamilyConfig`,
  `ContainerMetadataReader`, `ChatTemplateRenderer`, `SpecialTokens`,
  `OutputDecoder`, `StopSequenceTracker`, `TokenizerFiles`,
  `ModelCompatibilityException`.
- `models/` — `EngineModelInfo`, `EngineConfig`, `GenerationConfig`,
  `EngineCapabilities`, `EngineDebugInfo`, `EngineException`, `EngineStats`,
  `MemoryStats`, `BackendType`, `ModelLoadConfig`, `ChatPromptMessage`,
  `StreamChunk`.
- `backend/` — `BackendSelector`, `HardwareBackendProbe`, `InferenceBackend`
  (NPU/GPU/CPU selection with silent fallback chain), `BackendCapabilities`,
  `NpuVendor`, `PerformanceProfiles` (device-class-specific presets).
- `diagnostics/` — `RuntimeLogger` (tagged debug logging),
  `EnginePerformanceMonitor` (lock-free pipeline-stage profiler),
  `EngineCrashGuard` (crash recording, backend auto-disable, telemetry),
  `EngineDiagnostics` (aggregated diagnostics model + collector).
- `embedding/` — `LiteRtEmbeddingEngine` + `SentencePieceTokenizer` (local
  embedding path for the memory system, via the LiteRT `CompiledModel` API).
- `memory/` — `ContextManager` (adaptive context sizing, KV-cache estimation).
- `utils/` — `MemoryEstimator`, `ModelResourceGuard` (RAM gate before load),
  `LiteRtValidator` (container sniffing before the runtime spends time),
  `CoherenceChecker` (garbage-output detection), `ThreadManager`
  (device-class-adaptive threading with cached hardware info).

---

## 3. Model format: `.litertlm` containers

A `.litertlm` file is a **LiteRT-LM engine file**: weights, tokenizer, and
metadata packaged in one self-describing container. The embedded `LlmMetadata`
proto is read by `ContainerMetadataReader` before anything is loaded.

Resolution order (per `ModelFamilyRegistry`):

1. **Container metadata first** — `container_model_type` etc. decide the
   family (this is the authoritative source and is what logcat shows as
   `family resolved: Qwen2.5 (source container_model_type)`).
2. Fallback to template / stop-token signatures.
3. Model-name aliases only when no metadata exists.

Families: `GEMMA`, `QWEN2`, `QWEN2P5` (Qwen2.5), `QWEN3`, `PHI`, `LLAMA3`,
`DEEPSEEK`, `MISTRAL`, `SMOL`, `TINYLLAMA`. Each family carries:

- its official **chat template** (rendered by `ChatTemplateRenderer`, or the
  container's own template when present — `templateSource` reports which),
- **special tokens** (`SpecialTokens`: bos, eos, stop sequences),
- **native tool-call markers** (`nativeToolMarkers`, e.g. Qwen's
  `<|tool_call|>`),
- a **tool-advertisement cap** (`toolAdvertisementCapChars`).

**Quantization:** the runtime ships repacked models in mixed int4 / int8 /
fp16 flavors (e.g. `qwen3_0_6b_mixed_int4.litertlm`, `Gemma3-1B-IT_*_q4_*.litertlm`).
The catalog (`core/models` `catalog_v1.json`) currently curates **7 models**
(6 `.litertlm` + 1 embedding model) across Qwen, Gemma and DeepSeek, sourced
from the `litert-community` repos on Hugging Face and ModelScope.

---

## 4. Context length and the tool-advertisement budget

The **real** context length is detected from container metadata at load time
(`max context detected: 4096 tokens (container limit)`). Two observed cases:

| Model | Detected context |
|---|---|
| Qwen2.5 1.5B Instruct Q8 | 4096 |
| Qwen3 0.6B Mixed Int4 | **2048** (nominal catalog value 8192 is wrong) |

The chat layer budgets every prompt against that real window:

```
reservedOutputTokens = clamp(maxTokens, 256, 4096)
                       .coerceAtMost(max(contextLength / 3, 256))
systemCharsBudget    = max(contextLength - reservedOutputTokens - 128, 0) * 4
advertisementChars   = min(systemCharsBudget, family.toolAdvertisementCapChars)
```

Why the cap exists — measured on-device (see the engine instrumented test
`toolAdvertisementDoesNotDegradeOutput`):

- The full 47-tool advertisement is ~9.4K chars (~2.3K tokens). On a
  1.5B-class Qwen with the default sampler this produced **empty or
  code-fragment garbage**; plain prose of the same size was fine, and tool-list
  content under ~5.7K chars was fine → the degradation is *content-specific*
  (tool-list syntax) and size-dependent.
- Qwen3-0.6B (2048 real tokens) **hard-overflowed** with the full list:
  `EngineException: Status Code: 3 — Input token ids are too long …
  2376 >= 2048`.
- Small Qwen families therefore cap the advertisement at **4500 chars**
  (≈1100 tokens ≈ 23 tools), comfortably below the breakpoint. Gemma 4B-class
  models handle the full list and keep `Int.MAX_VALUE` (no family cap; the
  context budget still applies).

---

## 5. Generation pipeline

`LiteRtLmEngine.generateChatStream`:

1. **Conversation creation** with family-aware flags
   (`createConversationWithFamilyFlags`; `ExperimentalFlags` overrides such as
   `overwritePromptTemplate` are family-driven). LiteRT renders the chat
   template internally; the app's `ChatTemplateRenderer` mirrors the official
   template for debugging.
2. **Sampler mapping**: `GenerationConfig` → LiteRT `SamplerConfig`
   (temperature, top-k, top-p, min-p, repetition penalty; `reuseKvCache` for
   multi-turn speed).
3. **Streaming**: token chunks via a `Channel`; `StopSequenceTracker` +
   `OutputDecoder` cut at family stop tokens (e.g. `<|im_end|>`);
   `firstTokenMs` / `tokensPerSecond` telemetry.
4. **Tool loop**: for families with `nativeToolMarkers`, the chat layer runs a
   native loop (up to 3 rounds) scanning for `<|tool_call|>` markers; otherwise
   the JSON-compat `ToolPlanner` (`planLocal`) plans tool calls, and results
   feed back for up to 6 re-plan rounds behind the permission + confirmation
   gates.
5. **Debug logging** (diagnostic): every request logs a compact prompt summary
   — model, family, template, template source, tokenizer, message count,
   `nCtx`, sampler values; when `config.debugTokenLogging` is set, the full
   rendered prompt is also logged (chunked) via `ChatTemplateRenderer`.

---

## 6. Backends: NPU, GPU, and CPU

`BackendType` semantics after the migration:

| Value | Meaning |
|---|---|
| `CPU` | XNNPACK, always available — the engine's floor |
| `GPU` | OpenCL-based LiteRT GPU delegate — preferred when available |
| `NPU` | LiteRT NPU delegate (vendor dispatch — Qualcomm Hexagon, MediaTek NeuroPilot, Google Tensor) |
| `AUTO` | Automatic selection: NPU → GPU → CPU, resolved at model load |
| `QUALCOMM_QNN`, `LLAMA_CPP_VULKAN`, `ONNX_RUNTIME`, `VULKAN` | **Legacy enum values kept only for serializer/UI compatibility** — never produced by the engine |

Backend selection and health:

- The **startup hardware probe** (`HardwareBackendProbe`) runs once at engine
  initialization and detects SoC vendor, GPU identity, NPU availability, and
  vendor dispatch libraries.
- `BackendSelector` determines the ordered fallback chain (NPU → GPU → CPU)
  from the probe results and the model's compatibility flags.
- Each backend is **attempted** in order; failures fall through silently — a
  backend that cannot initialize on this device/driver never crashes the app.
- `EngineCrashGuard` tracks per-backend failure counts and auto-disables a
  backend after 3 consecutive failures.
- `MemoryStats` tracks `backend`, `gpuFree`, `gpuTotal`, `recoveryCount`;
  a "GPU init failed: …" reason is kept stable for the UI warning text.

---

## 7. Embeddings (memory system)

Local embeddings run on the **LiteRT `CompiledModel` API** (LiteRT 2.2.0),
because LiteRT-LM's `EmbeddingEngine` is unreleased as of 0.16.0:

- `engine/embedding/LiteRtEmbeddingEngine.kt` + `SentencePieceTokenizer.kt`
- `core/memory/embedding/`: `RoutingEmbeddingProvider` picks between
  `CloudEmbeddingProvider` (LiteLLM embeddings API) and the local LiteRT
  provider; `CosineVectorIndex` does in-memory brute-force retrieval over the
  SQLite-backed memory store.

---

## 8. Validation gates before load

- `LiteRtValidator` — sniffs the file before the runtime spends minutes on it:
  correct container/`LlmMetadata` structure, rejects renamed GGUF /
  safetensors / corrupt transfers with an actionable message.
- `MemoryEstimator` + `ModelResourceGuard` — RAM budget check
  (weights + KV cache + compute scratch + runtime overhead) before load and
  before generation; `ThreadManager` caps compute threads (mobile guidance:
  2–4).
- `CoherenceChecker` — flags garbage/degenerate output (repeated tokens,
  template-marker leakage) so the chat layer can react instead of shipping it.

---

## 9. Performance optimization

The engine is optimized for speed, memory efficiency, and crash resilience:

### Pipeline profiling

`EnginePerformanceMonitor` tracks wall-clock time for every stage of the
inference pipeline: model init, container read, conversation creation, first-
token latency, warmup. All operations are lock-free and allocation-free in
the hot path. Stats are exposed via `EngineDiagnostics` for the developer
panel.

### Interpreter warmup

After model load, a short background prompt ("Hi", 1 token) primes the
interpreter — JIT-compiling compute graphs, allocating buffers, warming the
KV cache — so the first real prompt arrives faster. Warmup runs on
`Dispatchers.Default` and does not delay the Ready state.

### Device-class-adaptive threading

`ThreadManager` classifies devices into 5 tiers (low → high) based on core
count and RAM. Each tier tunes:
- Thread count (1–4, capped at 4 for mobile P-core sweet spot)
- Context length defaults (2048–8192)
- Batch size (512–2048)
- Memory budget fraction (45–75%)
- Streaming update rate (16–32ms)

### Performance profiles

`PerformanceProfiles` provides presets for LOW_END, MID_RANGE, FLAGSHIP,
GPU_OPTIMIZED, NPU_OPTIMIZED, and CPU_OPTIMIZED, each tuning thread count,
batch size, context length, and streaming rate for maximum throughput on
that device class.

### Metadata caching

`ContainerMetadataReader` caches parsed container metadata (LRU, max 4
entries) to avoid re-parsing headers when switching backends or retrying loads
of the same file. Cache is evicted on model unload.

### Allocation reduction

- Pre-sized `StringBuilder(2048)` in all streaming paths
- Pre-compiled `Array<String>` for strip tokens and stop sequences in
  `OutputDecoder`
- Pre-compiled regex for `stripControlTokens` (single-pass replacement)
- Pre-computed `holdbackLength` in `StopSequenceTracker`
- Cached `ActivityManager` and PID array for memory stats (avoids
  `getSystemService()` every second)
- Conditional verbose/debug logging (`Log.isLoggable` guard)

### Crash hardening

`EngineCrashGuard` records every exception in the inference pipeline,
auto-disables backends after 3 consecutive failures, and ensures clean state
transitions on any error path. The cancel path uses atomic reference capture
to prevent race conditions.

---

## 10. Error taxonomy

| Failure | Layer | User-visible |
|---|---|---|
| Not a valid `.litertlm` container | `LiteRtValidator` | Clear message before load |
| Container incompatible with runtime | `ModelCompatibilityException` | Compatibility explanation |
| Input too long for real context | LiteRT-LM (`EngineException` code 3) | "Input token ids are too long" — prompts are budgeted to avoid it |
| GPU init failure | GPU delegate | Automatic NPU/GPU→CPU fallback |
| Backend auto-disabled | `EngineCrashGuard` | Fallback to next backend in chain |
| Degenerate/garbage output | `CoherenceChecker` | Detection + diagnostics |
| Generation failure | `EngineException` | Streamed `Result.Error` |

---

## 11. Tests

- Unit: engine `compat/`, `utils/`, `models/`, `diagnostics/` tests (family
  resolution, template rendering, stop sequences, memory estimation, validator,
  coherence checker, crash guard, output decoder, sanitizer crash safety).
- Instrumented (`EngineStressInstrumentedTest`, device + `modelPath`):
  clean-prompt fidelity, tool-advertisement degradation probes, native
  tool-call scanning — run against real `.litertlm` files
  (`/data/user/0/io.androllm.engine.test/files/models/…`).