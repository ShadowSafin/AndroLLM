# Model Support Guide

Comprehensive guide to model formats, architectures, and compatibility in AndroLLM.

---

## Supported Model Formats

### `.litertlm` ✅ Primary Format

`.litertlm` container files are the **only format the engine can run**. They are
the engine file format of Google's LiteRT-LM runtime: a single file bundling the
model weights, the tokenizer, and the chat template, with a LiteRT `LlmMetadata`
proto embedded for self-describing metadata (family, context length, tokenizer
kind, stop tokens).

**Why `.litertlm`:**
- Single-file container (weights + tokenizer + template)
- Runtime-optimized layout — loaded directly by LiteRT-LM with no conversion
- Self-describing: the compat layer resolves the model family from the embedded
  `LlmMetadata` proto at load time
- Official format of the `litert-community` catalog on HuggingFace / ModelScope

**File identification:** magic/header inspected by the engine's `LiteRtValidator`
before load; `ContainerMetadataReader` parses the embedded metadata proto.

### Other Formats

| Format | Local Inference | Catalog Display | Notes |
|---|---|---|---|
| `.litertlm` | ✅ Yes | ✅ Yes | Primary format |
| `.tflite` | ✅ Yes (embeddings) | ✅ Yes | EmbeddingGemma 300M via raw LiteRT `CompiledModel` API |
| GGUF | ❌ No (not runnable) | ⚠️ Import inspection only | `GgufReader`/`GgufType` (pure-JVM header parser) read metadata of GGUF files in the import flow; the LiteRT runtime cannot execute them |
| SAFETENSORS / PYTORCH / ONNX | ❌ No | ℹ️ Informational | Not runnable |

> **Note:** The pre-migration GGUF catalog (101 models) is gone. The catalog
> validator rejects any entry whose file is not a LiteRT artifact
> (`.litertlm` / `.tflite`).

---

## Supported Families & Architectures

The compat layer (`ModelFamily` / `ModelFamilyRegistry`) resolves families from
container metadata (`LlmMetadata` proto), falling back to template/stop-token
signatures and then the model name. Families resolved by the runtime:

| Family | Architectures | Tokenizer | Native `<\|tool_call\|>` markers | Tool-ad advertisement cap |
|---|---|---|---|---|
| **Gemma** | `gemma3`, `gemma4`, `function_gemma` | SentencePiece (Gemma 3 unigram, 262k) | ✅ | — |
| **Qwen2** | `qwen2`, `qwen1.5`, `qwen1.6`, `qwen-vl` | BPE | ✅ | 4500 chars (small repacks) |
| **Qwen2.5** | `qwen2.5`, `qwen2_5` | BPE | ✅ | 4500 chars (small repacks) |
| **Qwen3** | `qwen3`, `qwen3-vl` | BPE | ✅ | 4500 chars (small repacks) |
| **Phi** | `phi`, `phi-2`, `phi-3`, `phi-4`, `phimoe` | BPE | ❌ | — |
| **Llama 3** | `llama-3`, `llama-3.1`, `llama-3.2`, `llama-3.3` | BPE | ❌ | — |
| **DeepSeek** | `deepseek` | BPE | ❌ | — |
| **Mistral** | `mistral` | BPE | ❌ | — |
| **SmolLM** | `smollm`, `smol` | BPE | ❌ | — |
| **TinyLlama** | `tinyllama`, `tinylama` | BPE | ❌ | — |

`qwen2.5` / `qwen3` / `gemma3` / `gemma3n` / `gemma4` / `function_gemma` are
mapped directly from the `llm_model_type` field of the container metadata.

---

## Catalog

The bundled catalog (`core/models/src/main/assets/catalog_v1.json`) ships
**7 models** — 6 `.litertlm` chat models + 1 `.tflite` embedding model — across
5 architectures (`gemma3`, `gemma4`, `qwen2`, `qwen3`, `gemma-embedding`) and
3 families (Qwen, Gemma, DeepSeek). All sources are the official
`litert-community` organization on HuggingFace and ModelScope.

| Model | Family / Architecture | Quantization | Real context¹ | Download | Min RAM | Recommended RAM |
|---|---|---|---|---|---|---|
| Qwen3 0.6B Mixed Int4 | Qwen / `qwen3` | Mixed int4 | 2048 | ~475 MB | 2 GB | 4 GB |
| Gemma 3 1B IT Q4 | Gemma / `gemma3` | Q4 | 4096 | ~557 MB | 2 GB | 4 GB |
| Qwen2.5 1.5B Instruct Q8 | Qwen / `qwen2` | Q8 | 4096 | ~1.5 GB | 3 GB | 6 GB |
| DeepSeek R1 Distill Qwen 1.5B Q8 | DeepSeek / `qwen2` | Q8 | 4096 | ~1.7 GB | 3.5 GB | 6 GB |
| Gemma 4 E2B IT LiteRT | Gemma / `gemma4` | Q8 | 8192 | ~2.4 GB | 4 GB | 8 GB |
| Gemma 4 E4B IT LiteRT | Gemma / `gemma4` | Q8 | 8192 | ~3.4 GB | 6 GB | 12 GB |
| EmbeddingGemma 300M | Gemma / `gemma-embedding` | Mixed (tflite) | 512 | ~171 MB | 1.5 GB | 3 GB |

¹ **Real context detected from container metadata at load time** — the engine
trusts the container, not the catalog claim. Measured on-device: Qwen2.5-1.5B
runs at 4096; Qwen3-0.6B runs at **2048** (its full 2.3K-token tool
advertisement overflows this window — see context budgeting below).

Every entry carries `supportedBackends: [CPU, GPU]` — the same `.litertlm`
file runs on both XNNPACK CPU and the OpenCL LiteRT GPU delegate.

---

## Quantization Guide

LiteRT-LM containers use **mixed int4 / int8 / fp16** quantization rather than
the GGUF K-quant family:

| Label | Meaning | Notes |
|---|---|---|
| `MIXED` | Mixed int4/int8/fp16 layers | E.g. Qwen3 0.6B ("Mixed Int4") — the recommended small chat model |
| `Q4` | 4-bit weights | E.g. Gemma 3 1B Q4 |
| `Q8` | 8-bit weights | E.g. Qwen2.5 1.5B Q8; higher quality, larger files |

There is no quantization picker in the catalog — each model ships one
runtime-tuned quantization from `litert-community`.

### Choosing a Model by RAM

```
Device RAM 2 GB        →  Qwen3 0.6B Mixed Int4 (~475 MB)
Device RAM 3–4 GB      →  Gemma 3 1B Q4 or Qwen2.5 1.5B Q8
Device RAM 4–6 GB      →  DeepSeek R1 Distill Qwen 1.5B Q8 / Gemma 4 E2B
Device RAM 6 GB+       →  Gemma 4 E4B
```

Guidance is a starting point — actual footprint depends on context length and
the OS baseline. `MemoryEstimator` + `ModelResourceGuard` predict and enforce
the RAM budget at load time.

---

## Context Length

Context is **detected from the container's `LlmMetadata` at load** and shown in
the model detail screen. Two budgets matter:

| Budget | Source | Purpose |
|---|---|---|
| Model context (`nCtx`) | Container metadata at load | Hard window for prompt + KV cache |
| Recommended context | Catalog entry | Displayed suggestion; overrides may degrade output |

**Context budgeting rules enforced by the app:**

1. **Tool advertisement cap** — families whose small repacks degrade with a
   long tool list (Qwen2/2.5/3) cap the tool-advertisement system message at
   **4500 chars** (~1100 tokens). Measured breakpoint: Qwen2.5-1.5B degrades
   between ~5.7K and ~9.4K chars; Qwen3-0.6B overflows its 2048-token window
   with the full ~2.3K-token list. Gemma 4B handles the full list.
2. **Planner budget** — the local `ToolPlanner` sizes its system prompt to the
   real detected context (`(nCtx − 512 − 256) × 4` chars), leaving room for the
   user content and JSON output.
3. **Overflow recovery** — when a conversation fills the KV cache, LiteRT-LM
   throws `INVALID_ARGUMENT: Input token ids are too long`; the engine trims the
   oldest turns and reseeds the conversation automatically.

⚠️ **Warning:** generating beyond the model's trained context degrades quality.
Respect the container-detected `nCtx`.

---

## Model Metadata

Each model in the catalog carries rich metadata:

| Field | Type | Description |
|---|---|---|
| `id` | String | Unique identifier |
| `name` | String | Human-readable name |
| `family` | String | Model family (Qwen, Gemma, DeepSeek) |
| `architecture` | String | Architecture id (`gemma3`, `gemma4`, `qwen2`, `qwen3`, `gemma-embedding`) |
| `parameters` | String | Parameter count ("0.6B", "1.5B", etc.) |
| `quantization` | String | Quantization label (`MIXED`, `Q4`, `Q8`) |
| `contextLength` | Int | Catalog context claim (overridden by container-detected value) |
| `recommendedContext` | Int | Suggested context window |
| `fileSize` | Long | File size in bytes |
| `minRamGb` / `recommendedRamGb` | Float | Device RAM guidance |
| `runtimeFormat` | String | `LITERTLM` / `TFLITE` |
| `supportedBackends` | List | `["CPU", "GPU"]` |
| `repoId` | String | `litert-community/<model>` on HuggingFace / ModelScope |
| `license` | String | Model license (Apache 2.0, Gemma terms, etc.) |
| `stopSequences` | List | Model-specific stop tokens merged into the family defaults |
| `status` | String | `STABLE` etc. |
| `badges` / `strengths` / `weaknesses` | List | Curated UX copy |

`ModelInspector` (core/models) reads live metadata from downloaded containers
for the model detail screen.

---

## Model Compatibility

`CompatibilityAnalyzer` evaluates whether a model will run on a specific device
(RAM fit via `MemoryEstimator`, backend availability, storage).

Access from the Models screen → Diagnostics tab.

---

**Attachments (cloud-only):** file attachments are a cloud-model feature —
local `.litertlm` models do not parse or process attached files. See
[Chat Attachments](features/chat-attachments.md).

## Finding Models

### Official Model Catalog (bundled)

Curated `litert-community` models (Qwen, Gemma, DeepSeek), filtered by your
device's RAM and storage.

### HuggingFace Browser

Search any HuggingFace repository for LiteRT artifacts:
1. Models screen → HuggingFace tab
2. Search by author/repository name (the API filters to `litertlm` artifacts)
3. Browse available models and download directly

### Manual Import

Import a model file from local storage:
1. Use a file manager to navigate to a `.litertlm` file
2. The system share sheet will show AndroLLM as a target
3. Or: Models screen → Import → select file

GGUF files can also be imported for **metadata inspection only** (the pure-JVM
`GgufReader`/`GgufType` parse the header so the UI can describe the file) — but
the LiteRT runtime cannot execute them, and such files are rejected at load.

---

## Model Licensing

Models have individual licenses. Always check before commercial use:

| License | Commercial Use | Notes |
|---|---|---|
| Apache 2.0 | ✅ Yes | Qwen family, most litert-community repacks |
| Gemma Terms | ✅ Yes (limited) | Gemma 3 / Gemma 4 / EmbeddingGemma |

The catalog stores the license string in each model's metadata. The Model detail screen displays it.

---

## Planned Model Support

| Feature | Status | Notes |
|---|---|---|
| NPU (QNN) backend | 🚧 Planned | Next milestone — same `.litertlm` files |
| Multi-modal (vision) models | 🔮 Future | Needs LiteRT-LM multi-modal support + preprocessing pipeline |
| Speaker diarization via sherpa-onnx | 🚧 Planned | Library support exists; UI pending |
| More `litert-community` families | 🚧 Ongoing | Adding a family = one `ModelFamily` enum + one registry entry |