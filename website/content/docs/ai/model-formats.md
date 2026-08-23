# Model Formats

The `.litertlm` container format, catalog sources, quantizations, and how
GGUF files fit (inspection only).

---

## The Primary Format: `.litertlm`

AndroLLM's local inference runs **LiteRT-LM engine files** — single-file
containers with the `.litertlm` extension. They are executed directly by the
LiteRT-LM 0.16.0 runtime on-device; no conversion step exists in the app.

### What's inside

A `.litertlm` container packages everything the runtime needs in one file:

- **Model weights** (quantized)
- **Tokenizer** files (per-family, located via `TokenizerFiles`)
- **`LlmMetadata` proto** — the embedded metadata record the compat layer
  reads: family, architecture, context length, tokenizer info

Because the metadata is embedded, the app can validate and classify a model
**before** loading it:

| Metadata | Used For |
|---|---|
| Family | `ModelFamilyRegistry` → chat template, special tokens, stop sequences |
| Context length | Real context detection (`Qwen2.5-1.5B` → 4096, `Qwen3-0.6B` → 2048) |
| Quantization info | RAM estimates, catalog display |

### File identification

- Extension: `.litertlm`
- `ModelInspector` reads the container metadata at import/load time and
  surfaces it in the Models screen (family, context, quantization, size).
- `LiteRtValidator` rejects files that fail container/metadata validation
  before the runtime ever sees them (paired with SHA-256 verification of
  downloads).

---

## Catalog Sources

Models come from the **`litert-community`** organization on two mirrors:

- **HuggingFace** — primary source
- **ModelScope** — mirror for regions where HuggingFace is slow or blocked

The catalog's `downloadUrl` points directly at `.litertlm` files in
`litert-community` repositories. The app downloads the container, verifies its
SHA-256, validates it with `LiteRtValidator`, then loads it through LiteRT-LM.

### The curated catalog

| Fact | Value |
|---|---|
| Models | **21** curated `.litertlm` models |
| Families | Qwen · Gemma · DeepSeek |
| Architectures | `gemma3` · `gemma4` · `gemma-embedding` · `qwen2` · `qwen3` |
| File sizes | ~475 MB – 1.3 GB |
| RAM guidance | 2–4 GB device RAM |
| Backends | CPU and GPU (supportedBackends) |

Catalog metadata is validated by `CatalogValidator` (ID uniqueness, required
fields, supported architectures, SHA-256 format, HTTPS URLs).

---

## Quantizations

`.litertlm` containers ship pre-quantized from `litert-community`. Quantization
is decided by the model producer, not the user — there are no per-file quant
download options like GGUF had.

| Quantization | Used By |
|---|---|
| Mixed int4/int8/fp16 | Qwen3-0.6B (mixed int4) |
| Q4 (int4) | Gemma 3 1B (Q4) |

In practice: int4-dominant containers for the smallest RAM footprint, with
int8/fp16 components where precision matters (attention, embeddings).

---

## GGUF: Inspection Only

The app still understands GGUF files — but only for **metadata inspection in
the import flow**. `GgufReader`/`GgufType` parse GGUF headers so the app can
tell the user what a legacy `.gguf` file *is* and why it cannot be run.

> **GGUF is NOT runnable.** The app has no llama.cpp runtime, no native code,
> and no conversion path. A GGUF file can be inspected and identified, but it
> will never load for inference.

---

## Manual Import

1. Place a `.litertlm` file in the app's model directory (Settings → Storage),
   or use the share sheet / Models screen → Import.
2. `ModelInspector` reads the container metadata (family, context,
   quantization).
3. `LiteRtValidator` validates the container; SHA-256 is computed for
   integrity tracking.
4. Select the model and tap **Load** — LiteRT-LM takes over from there.

---

## See Also

- [LiteRT-LM Engine](litert-lm.md) — the runtime and compat layer
- [Model Support](../MODEL_SUPPORT.md) — supported models and requirements
- [Acceleration](acceleration.md) — CPU/GPU backends