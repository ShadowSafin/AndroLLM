# Model Formats Guide

The model formats AndroLLM understands, and what happens to each of them.

---

## `.litertlm` — the runnable format ✅

The LiteRT-LM engine file format. A **single file** bundling:

- Model weights (runtime-optimized layout)
- Tokenizer vocabulary + configuration
- Chat template
- A LiteRT **`LlmMetadata` proto** embedded for self-describing metadata:
  model type (`llm_model_type`), context length, tokenizer kind, stop tokens

**How AndroLLM handles it:**

| Stage | Component |
|---|---|
| Download validation | `LiteRtValidator.validateHeader()` + SHA-256 (in `ModelDownloadWorker`) |
| Load validation | `LiteRtValidator` header check before the runtime touches the file |
| Metadata parsing | `ContainerMetadataReader` (engine compat) reads the `LlmMetadata` proto |
| Family resolution | `ModelFamilyRegistry` maps `llm_model_type` → family + template |
| Execution | LiteRT-LM `Engine` + `Conversation` (CPU or GPU delegate) |

**Sources:** official `litert-community` repos on HuggingFace and ModelScope.
The bundled catalog ships 6 chat containers (Qwen3 0.6B, Gemma 3 1B, Qwen2.5
1.5B, DeepSeek R1 Distill 1.5B, Gemma 4 E2B/E4B).

**Custom models:** import any `.litertlm` file via share sheet or the Models
screen — the app inspects the container, resolves the family, and lists it.

---

## GGUF — legacy, inspection only ⚠️

GGUF (the llama.cpp-era format) is **no longer runnable** by AndroLLM. The
LiteRT runtime cannot execute GGUF files.

What remains:

- `GgufReader` / `GgufType` (core/models, pure-JVM) parse the GGUF v2/v3 header
  **for metadata inspection only** — the import flow can describe a GGUF file
  (architecture, quantization, parameters) in the UI before rejecting it.
- The `ModelFormat` enum still carries `GGUF` as a legacy value, and
  `ModelEntity.format` defaults to `"GGUF"` for backwards compatibility —
  catalog models are `LITERTLM`.

Anything else (safetensors, PyTorch, ONNX, QNN) is informational only and is
rejected by `CatalogValidator` (the catalog accepts `.litertlm` / `.tflite`
artifacts only).

---

## `.tflite` — embeddings ✅

Standard LiteRT/TFLite models run through the **raw LiteRT `CompiledModel`
API** — used for the local embedding model:

| Model | File | Dims | Tokenizer | Seq |
|---|---|---|---|---|
| EmbeddingGemma 300M | `embeddinggemma-300M_seq512_mixed-precision.tflite` (~171 MB) | 768 | SentencePiece (Gemma 3 unigram, 262k) | 512 |

The catalog's `runtimeFormat` field distinguishes `LITERTLM` (chat) from
`TFLITE` (embeddings) so each model is routed to the correct runtime.

---

## Format Summary

| Format | Chat inference | Embeddings | Catalog display | Notes |
|---|---|---|---|---|
| `.litertlm` | ✅ | — | ✅ | Primary format; family + context from embedded `LlmMetadata` |
| `.tflite` | — | ✅ | ✅ | EmbeddingGemma via raw LiteRT `CompiledModel` |
| GGUF | ❌ | ❌ | ❌ | `GgufReader` parses headers for import inspection only |
| SAFETENSORS / PYTORCH / ONNX / QNN | ❌ | ❌ | ℹ️ | Informational only; rejected by the catalog validator |

---

## Related

- [LiteRT-LM Integration](litert-lm.md) — the runtime that executes `.litertlm`
- [Acceleration](acceleration.md) — CPU/GPU execution of these formats
- [Model Support](../MODEL_SUPPORT.md) — the bundled catalog and families