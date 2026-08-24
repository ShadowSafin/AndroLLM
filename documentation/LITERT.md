# LiteRT / LiteRT-LM Quick Reference

Cheat sheet for the Google on-device runtimes used by the engine module.

---

## Versions (libs.versions.toml)

| Artifact | Version | Used for |
|---|---|---|
| `com.google.ai.edge.litertlm:litertlm-android` | **0.16.0** | Chat/generation — `.litertlm` containers |
| `com.google.ai.edge.litert:litert` | **2.2.0** | Raw `CompiledModel` API — EmbeddingGemma 300M |
| `org.tensorflow.lite:interpreter` | classic | Classic `Interpreter` surface (compat) |

All resolved from **Google Maven** — no NDK/CMake required.

---

## Key LiteRT-LM API Surface

| Type | Role |
|---|---|
| `Engine` | Loads a `.litertlm` container; executes generation |
| `Conversation` | Chat session: `appendMessage`, reseeding, context tracking |
| `ConversationConfig` | Context length, warmup settings |
| `SamplerConfig` | temperature, top-k, top-p, random seed (ULong) |
| `ExperimentalFlags` | `overwritePromptTemplate` — force the family template |
| `EngineConfig` | Backend selection (`GPU` default / `CPU` / `NPU`) |
| `Backend.NPU(nativeLibraryDir)` | NPU delegate via vendor dispatch libraries |
| `Backend.GOOGLE_TENSOR()` | Google Tensor NPU backend |
| `BenchmarkInfo` | Prefill/decode token counts, tokens/sec, time-to-first-token |

## Key LiteRT (raw) API Surface

| Type | Role |
|---|---|
| `CompiledModel` | Executes `.tflite` models (embedding path) |
| `SignatureRunner` / `Tensor` | Input/output tensors (768-dim embedding vector) |

---

## Where It Lives in the Codebase

| Layer | Package |
|---|---|
| Chat engine | `engine/.../core/LiteRtLmEngine.kt` |
| Embedding engine | `engine/.../embedding/LiteRtEmbeddingEngine.kt` |
| Family compat | `engine/.../compat/` |
| Hilt bindings | `engine/.../di/EngineModule.kt` |
| Diagnostics | `engine/.../diagnostics/RuntimeLogger.kt`, `EnginePerformanceMonitor.kt`, `EngineCrashGuard.kt`, `EngineDiagnostics.kt` (logcat tag `AndroLLM-Engine`) |

---

## Formats

| Format | Runtime | Notes |
|---|---|---|
| `.litertlm` | LiteRT-LM `Engine` | Weights + tokenizer + template + `LlmMetadata` proto |
| `.tflite` | LiteRT `CompiledModel` | Embeddings (EmbeddingGemma 300M, 768-dim) |
| GGUF | none | Legacy llama.cpp format — metadata inspection only |

---

## Docs

- [LiteRT-LM Integration](ai/litert-lm.md) — deep dive
- [Model Formats](ai/model-formats.md) — format handling
- [Acceleration](ai/acceleration.md) — CPU/GPU backends
- [Model Support](MODEL_SUPPORT.md) — catalog and families