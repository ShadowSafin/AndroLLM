# Model Support Guide

Comprehensive guide to model formats, architectures, and compatibility in AndroLLM.

---

## Supported Model Formats

### GGUF ✅ Primary Format

GGUF (GPT-Generated Unified Format) is the only format fully supported for local inference.

**Why GGUF:**
- Single-file format (weights + tokenizer + metadata)
- Compact binary layout, fast memory-mapped loading
- Supports all llama.cpp architectures
- Rich metadata (architecture, context length, license, quantization)
- Multiple quantization options for different RAM budgets

**File identification:**
- Magic bytes: `47 47 55 46` ("GGUF" in ASCII) at offset 0
- Version: 2 or 3 (byte offset 4)
- Tensor count: uint64 (byte offset 8)

### Other Formats (Informational Only)

| Format | Local Inference | Catalog Display |
|---|---|---|
| GGUF | ✅ Yes | ✅ Yes |
| GGML | ⚠️ Legacy, deprecated | ✅ Yes |
| SAFETENSORS | ❌ No | ✅ Yes |
| PYTORCH (.pt/.pth) | ❌ No | ✅ Yes |
| ONNX | ❌ No (voice only) | ✅ Yes |
| QNN | ❌ No (planned) | ✅ Yes |

---

## Supported Architectures

The vendored llama.cpp supports **137 architectures**. The most commonly encountered:

| Family | Architectures | Notes |
|---|---|---|
| **llama** | `llama`, `llama-bert` | Original LLaMA family; widest quant support |
| **gemma** | `gemma`, `gemma2` | Google's efficient open models; gemma2 benefits from Vulkan |
| **qwen2** | `qwen2`, `qwen2_5` | Alibaba's models; strong multilingual support |
| **deepseek** | `deepseek`, `deepseek2`, `deepseek3` | Dual-tank MoE architecture; larger models |
| **mistral** | `mistral` | Mistral AI's efficient models |
| **phi3** | `phi3`, `phi3_small` | Microsoft's compact models; excellent for mobile |
| **starcoder2** | `starcoder2` | Code-focused models |
| **command-r** | `command-r` | Command AI's retrieval-augmented models |
| **internlm2** | `internlm2` | Shanghai AI Lab's models |
| **ollama** | `ollama` | Ollama-compatible wrapper architectures |

Full list in [`core/models/src/main/java/io/androllm/core/models/catalog/SupportedArchitectures.kt`](core/models/src/main/java/io/androllm/core/models/catalog/SupportedArchitectures.kt).

---

## Quantization Guide

Quantization reduces model size and RAM usage with varying quality impact.

### Quantization Levels

| Label | Bits/Element | Size Reduction | Quality Impact | Recommendation |
|---|---|---|---|---|
| **BF16** | 16 | 1× (full) | None | Development only; too large for mobile |
| **F16** | 16 | 1× (full) | None | Same as BF16 |
| **Q8_0** | ~8 | 2× | Minimal | Best quality/size balance for 7B+ models |
| **Q5_K_M** | ~5.5 | 3× | Small | **Recommended for most use cases** |
| **Q4_K_M** | ~4.5 | 3.5× | Low-Medium | **Sweet spot for mobile** |
| **Q4_K_S** | ~4.0 | 4× | Medium | Tight RAM situations |
| **IQ4_XS** | ~4.0 | 4× | Medium | Alternative to Q4_K_S |
| **IQ3_XS** | ~3.25 | 5× | Noticeable | For very constrained devices |
| **IQ2_XS** | ~2.5 | 6.5× | Significant | Last resort |
| **IQ1_M** | ~1.5 | 10× | Severe | Research only; quality severely degraded |
| **MXFP4** | ~4 (mixed) | 4× | Low | Emerging format; limited model support |
| **NVFP4** | ~4 (NVIDIA) | 4× | Low | NVIDIA-specific; not widely available |

### Choosing Quantization

```
Available RAM ≥ 2× model BF16 size  →  Q8_0 (best quality)
Available RAM ≥ 1.5× model BF16 size →  Q5_K_M (recommended)
Available RAM ≥ 1.2× model BF16 size →  Q4_K_M (good balance)
Available RAM < 1.2× model BF16 size →  Q4_K_S or IQ3_XS
```

**Quick reference for common model sizes:**

| Model | BF16 Size | Q8_0 | Q5_K_M | Q4_K_M | IQ3_XS |
|---|---|---|---|---|---|
| 0.5B | 1.0 GB | 0.5 GB | 0.3 GB | 0.25 GB | 0.2 GB |
| 1.5B | 3.0 GB | 1.5 GB | 0.9 GB | 0.7 GB | 0.5 GB |
| 3B | 6.0 GB | 3.0 GB | 1.8 GB | 1.4 GB | 1.0 GB |
| 7B | 14.0 GB | 7.0 GB | 4.2 GB | 3.3 GB | 2.4 GB |
| 8B | 16.0 GB | 8.0 GB | 4.8 GB | 3.8 GB | 2.8 GB |
| 14B | 28.0 GB | 14.0 GB | 8.4 GB | 6.6 GB | 4.8 GB |

---

## Context Length

Context length determines how many tokens the model can consider at once.

### Typical Values by Model Family

| Model Family | Standard Context | Extended Context |
|---|---|---|
| llama | 4096 | 8192 (some variants) |
| gemma2 | 8192 | 受模型文件限制 |
| qwen2 | 4096–131072 | 部分模型支持超长上下文 |
| deepseek | 16384 | 受模型文件限制 |
| mistral | 8192 | 部分变体支持更长 |
| phi3 | 4096 |  mini 支持 128K |

### Context Length Recommendations

| Use Case | Recommended Context | Notes |
|---|---|---|
| Short Q&A | 1024 | Fastest, minimal RAM |
| General conversation | 2048–4096 | Best balance |
| Document analysis | 4096–8192 | More context = better comprehension |
| Code generation | 4096 | Usually sufficient |
| Long-form reasoning | 8192+ | Requires large RAM model |

⚠️ **Warning:** Setting context length higher than the model was trained for causes degradation. The GGUF metadata contains the model's `general.context_length` — respect this limit.

---

## Model Metadata

Each model in the catalog carries rich metadata:

| Field | Type | Description |
|---|---|---|
| `id` | String | Unique identifier |
| `name` | String | Human-readable name |
| `family` | String | Model family (gemma, qwen2, etc.) |
| `architecture` | String | Exact llama.cpp architecture string |
| `parameters` | String | Parameter count ("1.5B", "7B", etc.) |
| `quantization` | String | Quantization label ("Q4_K_M", "Q8_0", etc.) |
| `contextLength` | Int | Maximum context length in tokens |
| `fileSize` | Long | File size in bytes |
| `minRamGb` | Float | Minimum device RAM required |
| `recommendedRamGb` | Float | Recommended device RAM |
| `category` | Enum | RECOMMENDED, CHAT, REASONING, MOBILE_OPTIMIZED |
| `tags` | List<String> | Capability tags (code, math, multilingual, etc.) |
| `license` | String | Model license (MIT, Apache 2.0, Llama 3, etc.) |
| `chatTemplate` | String? | Jinja chat template (if embedded in GGUF) |
| `isGated` | Boolean | Whether HuggingFace access requires approval |

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
    val gpuLayersAvailable: Int,
    val totalLayers: Int,
    val warnings: List<String>
)
```

Access from the Models screen → Diagnostics tab.

---

## Finding Models

### Official Model Catalog

Built into the app. Shows curated models from:
- Google Gemma family
- Alibaba Qwen family
- DeepSeek family
- Meta Llama family

Filtered by your device's RAM, architecture, and preferences.

### HuggingFace Browser

Search any HuggingFace repository for GGUF models:
1. Models screen → HuggingFace tab
2. Search by author/repository name
3. Browse available quantizations
4. Download directly

### Manual Import

Import a GGUF file from local storage:
1. Use a file manager to navigate to the GGUF file
2. The system share sheet will show AndroLLM as a target
3. Or: Models screen → Import → select file

---

## Model Recommendations by Use Case

### General Chat

| Rank | Model | Parameters | Recommended Quant | Why |
|---|---|---|---|---|
| 1 | Qwen2.5-7B | 7B | Q5_K_M | Excellent all-rounder, strong instruction following |
| 2 | Gemma-2-9B | 9B | Q5_K_M | Fast, efficient, great for mid-range devices |
| 3 | Llama-3.2-3B | 3B | Q4_K_M | Compact, good for phones with 4–6 GB RAM |

### Code Generation

| Rank | Model | Parameters | Recommended Quant | Why |
|---|---|---|---|---|
| 1 | DeepSeek-Coder-V2-Lite | 16B | Q4_K_M | State-of-the-art code generation |
| 2 | StarCoder2-7B | 7B | Q5_K_M | Strong multi-language support |
| 3 | Phi-3.5-mini | 3.8B | Q4_K_M | Excellent for compact devices |

### Reasoning / Math

| Rank | Model | Parameters | Recommended Quant | Why |
|---|---|---|---|---|
| 1 | DeepSeek-R1-Distill-Qwen-7B | 7B | Q5_K_M | Strong reasoning capabilities |
| 2 | Gemma-2-9B-it | 9B | Q5_K_M | Good general reasoning |
| 3 | Qwen2.5-Math-7B | 7B | Q5_K_M | Mathematics-focused |

### Multilingual

| Rank | Model | Parameters | Recommended Quant | Why |
|---|---|---|---|---|
| 1 | Qwen2.5-7B | 7B | Q5_K_M | 29 languages well-supported |
| 2 | Gemma-3-4B | 4B | Q4_K_M | Strong multilingual, compact |
| 3 | Llama-3.1-8B-Instruct | 8B | Q5_K_M | Broad language coverage |

---

## Model Licensing

Models have individual licenses. Always check before commercial use:

| License | Commercial Use | Modifications | Attribution |
|---|---|---|---|
| MIT | ✅ Yes | ✅ Yes | Required |
| Apache 2.0 | ✅ Yes | ✅ Yes | Required |
| Llama 3 Community | ✅ Yes (limited) | ✅ Yes | Required |
| Llama 3 Research | ❌ No | ❌ No | Required |
| Gemma | ✅ Yes (limited) | ✅ Yes | Required |
| Qwen | ✅ Yes | ✅ Yes | Required |

The catalog stores the license string in each model's metadata. The Model detail screen displays it.

---

## Planned Model Support

| Feature | Status | Notes |
|---|---|---|
| SAFETENSORS direct loading | 🔮 Future | Would require GGUF conversion step |
| Multi-modal (vision) models | 🚧 Planned | Needs image preprocessing pipeline |
| Diffusion models (image gen) | 🔮 Future | Research stage; GPU-intensive |
| Speaker diarization via sherpa-onnx | 🚧 Planned | Library support exists; UI pending |
| Function calling / tool use | 🚧 Planned | Model-dependent; prompt engineering required |
