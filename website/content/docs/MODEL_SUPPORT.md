# Model Support Guide

Comprehensive guide to model formats, architectures, and compatibility in AndroLLM.

---

## Supported Model Format

### `.litertlm` ✅ Primary Format

LiteRT-LM engine files (`.litertlm`) are the **only** format supported for local
inference. They are single-file containers executed directly by Google's
LiteRT-LM 0.16.0 runtime.

**Why .litertlm:**
- Single-file container (weights + tokenizer + embedded `LlmMetadata` proto)
- Pre-quantized by the model producer — no user-side conversion or quant picking
- Real context length, family, and quantization read from embedded metadata
- Runs on CPU (XNNPACK) and GPU (OpenCL delegate) without extra setup

**File identification:**
- Extension: `.litertlm`
- `ModelInspector` reads the container metadata at import/load time
- `LiteRtValidator` validates the container before the runtime loads it

### Other Formats (Informational Only)

| Format | Local Inference | Catalog Display |
|---|---|---|
| `.litertlm` | ✅ Yes | ✅ Yes |
| GGUF | ❌ No — metadata inspection only in the import flow | ✅ Yes (informational) |
| SAFETENSORS | ❌ No | ✅ Yes |
| PYTORCH (.pt/.pth) | ❌ No | ✅ Yes |
| ONNX | ❌ No (voice only) | ✅ Yes |
| QNN | ❌ No (planned) | ✅ Yes |

`GgufReader`/`GgufType` exist only so the import flow can inspect legacy GGUF
metadata — GGUF files can be identified but never run.

---

## Supported Architectures & Families

### Families (from container metadata)

The compat layer detects these families from the container's `LlmMetadata`
proto: **Gemma, Qwen2, Qwen2.5, Qwen3, Phi, Llama3, DeepSeek, Mistral,
SmolLM, TinyLlama**.

### The curated catalog

21 curated `.litertlm` models, `runtimeFormat:
LITERTLM`, sourced from `litert-community` (HuggingFace + ModelScope):

| Family | Architectures | Notes |
|---|---|---|
| **Qwen** | `qwen2`, `qwen3` | Qwen2.5-1.5B (4096 ctx), Qwen3-0.6B (mixed int4, 2048 ctx) |
| **Gemma** | `gemma3`, `gemma4` | Gemma 3 1B (Q4) |
| **DeepSeek** | — | DeepSeek family container |
| **Embedding** | `gemma-embedding` | Embedding model (LiteRT CompiledModel API) |

Supported backends for all catalog models: **CPU and GPU**.

---

## Quantization Guide

`.litertlm` containers are pre-quantized — quantization is chosen by the model
producer, not per-download:

| Quantization | Used By |
|---|---|
| Mixed int4/int8/fp16 | Qwen3-0.6B (mixed int4) |
| Q4 (int4) | Gemma 3 1B (Q4) |

Int4-dominant weights keep RAM low; int8/fp16 components (attention,
embeddings) preserve quality where it matters.

---

## Context Length

Real context comes from the container metadata — the engine never guesses.

| Model | Context (from metadata) |
|---|---|
| Qwen2.5-1.5B | 4096 |
| Qwen3-0.6B | 2048 |

⚠️ **Warning:** Prompting a model past its metadata context length fails with
`Input token ids are too long` (context overflow). The engine budgets context
for tool advertisement (`ToolPromptBuilder`, 4500-char cap for small Qwen
families) so the tool list never crowds the conversation.

---

## Model Metadata

Each model in the catalog carries rich metadata:

| Field | Type | Description |
|---|---|---|
| `id` | String | Unique identifier |
| `name` | String | Human-readable name |
| `family` | String | Model family (Qwen, Gemma, DeepSeek, etc.) |
| `architecture` | String | LiteRT architecture (`gemma3`, `gemma4`, `gemma-embedding`, `qwen2`, `qwen3`) |
| `parameters` | String | Parameter count ("1.5B", "0.6B", etc.) |
| `quantization` | String | Quantization label ("mixed int4", "Q4", etc.) |
| `contextLength` | Int | Maximum context length in tokens (from container metadata) |
| `fileSize` | Long | File size in bytes (~475 MB–1.3 GB) |
| `minRamGb` | Float | Minimum device RAM required |
| `recommendedRamGb` | Float | Recommended device RAM |
| `category` | Enum | RECOMMENDED, CHAT, REASONING, MOBILE_OPTIMIZED |
| `tags` | List<String> | Capability tags (code, math, multilingual, etc.) |
| `license` | String | Model license (MIT, Apache 2.0, Gemma, etc.) |
| `supportedBackends` | List | CPU / GPU |
| `runtimeFormat` | String | `LITERTLM` |
| `source` | String | `litert-community` (HuggingFace / ModelScope) |

---

## Model Compatibility Analyzer

The `CompatibilityAnalyzer` evaluates whether a model will run on a specific device:

```kotlin
data class CompatibilityResult(
    val canRun: Boolean,
    val willFitInRam: Boolean,
    val ramRequiredGb: Float,
    val ramAvailableGb: Float,
    val gpuAccelerated: Boolean,
    val warnings: List<String>
)
```

Access from the Models screen → Diagnostics tab.

---

## Finding Models

### Official Model Catalog

Built into the app. Shows the curated catalog — 21 models from the
`litert-community` organization (Qwen, Gemma, DeepSeek families), filtered by
your device's RAM and preferences.

### Repository Browser

Browse `litert-community` repositories on HuggingFace or ModelScope:
1. Models screen → HuggingFace tab
2. Search by author/repository name
3. Download `.litertlm` files directly

### Manual Import

Import a `.litertlm` file from local storage:
1. Use a file manager to navigate to the file
2. The system share sheet will show AndroLLM as a target
3. Or: Models screen → Import → select file

The file is validated (`LiteRtValidator` + SHA-256) and its metadata is read by
`ModelInspector` before it can be loaded.

---

## Model Recommendations by Use Case

### General Chat (catalog, ~2–4 GB RAM guidance)

| Model | Parameters | Quantization | Notes |
|---|---|---|---|
| Qwen2.5-1.5B | 1.5B | mixed | 4096 context, strong multilingual |
| Qwen3-0.6B | 0.6B | mixed int4 | Smallest footprint; native tool-call markers |
| Gemma 3 1B | 1B | Q4 | Compact, strong instruction following |

### RAM Guidelines

| Device RAM | Recommended |
|---|---|
| 2 GB | Qwen3-0.6B class models |
| 3–4 GB | Qwen2.5-1.5B / Gemma 3 1B class models |

These are estimates — actual requirements vary by model architecture and context length. Use the `MemoryEstimator` and the catalog's `minRamGb`/`recommendedRamGb` fields.

---

## Model Licensing

Models have individual licenses. Always check before commercial use:

| License | Commercial Use | Modifications | Attribution |
|---|---|---|---|
| MIT | ✅ Yes | ✅ Yes | Required |
| Apache 2.0 | ✅ Yes | ✅ Yes | Required |
| Gemma | ✅ Yes (limited) | ✅ Yes | Required |
| Qwen | ✅ Yes | ✅ Yes | Required |
| DeepSeek | ✅ Yes | ✅ Yes | Required |

The catalog stores the license string in each model's metadata. The Model detail screen displays it.

---

## Planned Model Support

| Feature | Status | Notes |
|---|---|---|
| NPU acceleration | 🚧 Planned | Next planned backend (not implemented) |
| Multi-modal (vision) models | 🔮 Future | Requires vision preprocessing pipeline |
| More catalog families | 🔮 Future | As `litert-community` publishes new containers |
| GGUF runtime support | ❌ Not planned | GGUF stays inspection-only; no llama.cpp runtime exists |