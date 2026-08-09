# GGUF Format Guide

Understanding the GGUF model format used by AndroLLM's local inference engine.

---

## What Is GGUF?

**GGUF** (GPT-Generated Unified Format) is a binary model format created by the llama.cpp project. It replaces the older GGML format with a more flexible, self-describing binary layout.

### Key Properties

- **Single-file format**: Weights, tokenizer vocabulary, and metadata are all in one file
- **Binary layout**: Compact, efficient, supports memory-mapped file I/O
- **Self-describing**: Metadata keys provide architecture, context length, license, and more
- **Versioned**: Supports GGUF v2 (current standard) and v3 (future extensions)
- **Quantization-aware**: Encodes quantization scheme in the file type field

### Why GGUF for Mobile?

1. **Fast loading**: Memory-mapped file access avoids full file read into RAM
2. **Small footprint**: Efficient binary encoding minimizes disk and memory usage
3. **Rich metadata**: Architecture and parameters are readable without loading weights
4. **Broad support**: Most open-weight models are available in GGUF from HuggingFace
5. **Streaming-friendly**: Weights can be loaded progressively

---

## GGUF File Structure

### Header (First 32 bytes)

```
Offset  Size     Content
------  -------  ------------------------------------------
0x00    4 bytes  Magic: 0x46554747 ("GGUF" in ASCII, little-endian)
0x04    4 bytes  Version: 2 (current) or 3 (future)
0x08    8 bytes  Tensor count (uint64)
0x10    8 bytes  Metadata key count (uint64)
0x18    variable Metadata pairs (key-value)
0xx     variable Tensor data (raw weight arrays)
```

### Metadata Keys

Common metadata keys found in GGUF files:

| Key | Type | Description |
|---|---|---|
| `general.architecture` | string | Model architecture (e.g., `llama`, `gemma2`, `qwen2`) |
| `general.name` | string | Human-readable model name |
| `general.license` | string | Model license |
| `general.file_type` | uint32 | Quantization type (see table below) |
| `general.quantization_version` | uint32 | Quantization version |
| `<arch>.context_length` | uint32 | Maximum training context length |
| `<arch>.embedding_length` | uint32 | Hidden dimension size |
| `<arch>.block_count` | uint32 | Number of transformer blocks |
| `<arch>.feed_forward_length` | uint32 | FFN intermediate dimension |
| `<arch>.rope.dimension_count` | uint32 | RoPE embedding dimension |
| `<arch>.attention.head_count` | uint32 | Number of attention heads |
| `<arch>.attention.head_count_kv` | uint32 | Number of KV heads |
| `tokenizer.ggml.tokens` | string array | Token vocabulary |
| `tokenizer.ggml.scores` | float32 array | Token scores (for BPE) |
| `tokenizer.ggml.token_type` | int32 array | Token types |

The `<arch>` placeholder is replaced with the architecture name (e.g., `llama.context_length`, `gemma2.attention.head_count`).

---

## Quantization Types (file_type)

The `general.file_type` field uses numeric codes defined by llama.cpp:

| Code | Name | Bits/Element | Approx. Size Reduction |
|---|---|---|---|
| 0 | F32 | 32 | 1× (full precision) |
| 1 | F16 | 16 | 2× |
| 2 | Q4_0 | 4 | 8× |
| 3 | Q4_1 | 4 | 8× (with scaling) |
| 6 | Q5_0 | 5 | 6.4× |
| 7 | Q5_1 | 5 | 6.4× (with scaling) |
| 8 | Q8_0 | 8 | 4× |
| 9 | Q8_0 | 8 | 4× (alternative format) |
| 10 | Q2_K | ~2 | 16× |
| 11 | Q3_K | ~3 | 10.7× |
| 12 | Q4_K | ~4.5 | 7.1× |
| 13 | Q5_K | ~5.5 | 5.8× |
| 14 | Q6_K | ~6.5 | 4.9× |
| 15 | **Q4_K_M** | ~4.5 | 7.1× ← **Recommended** |
| 16 | Q8_0 | 8 | 4× |
| 17 | **Q5_K_M** | ~5.5 | 5.8× ← **Recommended** |
| 18 | Q5_K_S | ~5.0 | 6.4× |
| 19 | Q4_K_S | ~4.0 | 8× |
| 20 | Q3_K_L | ~3.3 | 9.7× |
| 21 | Q3_K_S | ~3.0 | 10.7× |
| 22–31 | (reserved) | — | — |
| 32 | IQ4_XS | ~4.0 | 8× |
| 33 | I8 | 8 | 4× |
| 34 | F16 | 16 | 2× |
| 35 | Q4_0 (repeat) | 4 | 8× |
| 36 | Q4_1 (repeat) | 4 | 8× |
| 37 | IQ4_NL | ~4.0 | 8× |
| 38 | B_F16 | 16 | 2× |
| 39 | Q4_K | ~4.5 | 7.1× |
| 40 | Q5_K | ~5.5 | 5.8× |
| 41 | Q6_K | ~6.5 | 4.9× |
| 42 | IQ2_XS | ~2.5 | 12.8× |

### Recommended Quantizations for Mobile

| Scenario | Recommendation | Why |
|---|---|---|
| Best quality (8GB+ RAM) | Q8_0 | Minimal quality loss |
| Balanced (4–8 GB RAM) | **Q5_K_M** | Best quality/size ratio |
| Constrained (2–4 GB RAM) | **Q4_K_M** | Good quality, widely available |
| Very constrained (< 2 GB) | IQ3_XS or IQ2_XS | Smallest files; quality degrades noticeably |

---

## GGUF Validation in AndroLLM

The [`GgufValidator`](../../engine/src/main/java/io/androllm/engine/utils/GgufValidator.kt) reads the GGUF header in pure Kotlin before passing the model to the native engine:

```kotlin
object GgufValidator {
    private const val GGUF_MAGIC = 0x46554747  // "GGUF"
    
    data class GgufValidationResult(
        val isValid: Boolean,
        val version: Int,
        val tensorCount: Long,
        val metadataCount: Long,
        val architecture: String?,
        val contextLength: Int?,
        val fileType: Int?,
        val license: String?,
        val error: String? = null
    )
    
    fun validateHeader(filePath: String): GgufValidationResult {
        // Opens file, reads 32-byte header
        // Validates magic == GGUF_MAGIC
        // Validates version ∈ {2, 3}
        // Parses metadata pairs looking for general.architecture
        // Validates architecture against SupportedArchitectures whitelist
        // Returns result with extracted metadata
    }
}
```

This prevents crashes from corrupted or non-GGUF files reaching the native layer.

---

## Common GGUF Sources

### HuggingFace

The majority of GGUF models are hosted on HuggingFace. Search patterns:

```
# Search by architecture
https://huggingface.co/models?search=<architecture>+gguf
# Example: https://huggingface.co/models?search=qwen2.5+gguf

# Popular model repositories
https://huggingface.co/bartowski/        # High-quality quantizations
https://huggingface.co/MaziyarPanahi/    # Wide variety of models
https://huggingface.co/QuantFactory/     # Official quantizations
https://huggingface.co/PowerInnovator/   # Mobile-optimized models
```

### Model Cards

Always check the model card for:
- Intended use case (chat, code, reasoning, multilingual)
- Recommended quantization
- Known limitations
- License restrictions

---

## GGUF vs Other Formats

| Property | GGUF | SAFETENSORS | PYTORCH (.pt) | GGML (legacy) |
|---|---|---|---|---|
| Single file | ✅ | ✅ | ✅ | ✅ |
| Self-describing metadata | ✅ | Partial | ❌ | ❌ |
| Memory-mapped loading | ✅ | ✅ | ❌ | ⚠️ Limited |
| Quantization support | ✅ Native | ❌ (raw weights) | ❌ (raw weights) | ⚠️ Limited |
| tokenizer included | ✅ Yes | ❌ No | ❌ No | ⚠️ Sometimes |
| Mobile optimized | ✅ Yes | ❌ No | ❌ No | ⚠️ Legacy |
| llama.cpp native | ✅ Yes | ❌ No | ❌ No | ✅ Legacy |

**Bottom line:** For local mobile inference, GGUF is the only practical format. Other formats require conversion tools (llama.cpp's `convert.py`) before they can be used.

---

## Converting Models to GGUF

If you have a model in SAFETENSORS or PyTorch format:

```bash
# Using llama.cpp's conversion script
python convert.py \
    --model-path /path/to/hf-model \
    --outfile model.gguf \
    --outtype q5_k_m

# Available output types: f32, f16, q8_0, q4_k_m, q5_k_m, etc.
```

Many model authors already provide pre-converted GGUF files on HuggingFace — check there first.

---

## Model File Naming Conventions

HuggingFace GGUF uploads typically follow this naming pattern:

```
[model-name]-[architecture]-[size]-[quantization].gguf
```

Examples:
- `gemma-2-9b-it-Q5_K_M.gguf`
- `qwen2.5-7b-instruct-Q4_K_M.gguf`
- `deepseek-coder-v2-lite-Q4_K_M.gguf`

The AndroLLM catalog parser extracts these components to populate model metadata fields.
