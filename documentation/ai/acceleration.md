# Acceleration Guide

How AndroLLM executes `.litertlm` models on device hardware.

---

## Backends

The engine supports two execution backends. The enum `BackendType` also keeps
legacy values (`QUALCOMM_QNN`, `LLAMA_CPP_VULKAN`, `ONNX_RUNTIME`, `VULKAN`)
for serializer/UI compatibility — the engine **never produces them**.

| Backend | Accelerator | Runtime | Notes |
|---|---|---|---|
| `GPU` | GPU (OpenCL) | LiteRT GPU delegate | **Default** (`EngineConfig.backend`) |
| `CPU` | CPU (XNNPACK) | LiteRT CPU kernels | Always available; automatic fallback target |

`EngineConfig.backend` defaults to `GPU`. All catalog models carry
`supportedBackends: [CPU, GPU]` — the same `.litertlm` file runs on both.

---

## GPU Path

- Enabled by default; the OpenCL delegate is initialized at model load
- GPU kernel compilation adds to cold-start load time; subsequent loads reuse
  compiled kernels
- `MemoryStats` exposes `gpuFree` / `gpuTotal` in Developer Diagnostics

### Fallback to CPU

The engine falls back to CPU when:

1. GPU delegate initialization fails (old GPUs, missing/buggy OpenCL drivers)
2. GPU memory is insufficient for the model
3. The GPU delegate crashes during generation

### Corruption Recovery

Generation output is monitored for a memory-corruption signature. On detection,
a three-level recovery runs:

1. **Level 1** — re-arm the model on the same backend
2. **Level 2** — reload the model on CPU
3. **Level 3** — surface the error to the user

Recovery cycles increment `MemoryStats.recoveryCount` (visible in Developer
screen; logcat tag `AndroLLM-Engine`).

---

## CPU Path

- XNNPACK-optimized kernels (single-threaded decode, no JNI)
- `ThreadManager` recommends thread counts from device specs
- Reference speed: **Qwen3 0.6B ≈ 21 tokens/sec on CPU** (mid-range hardware)
- The coherence probe and all safety features work identically on CPU

---

## Performance Notes

| Factor | Impact |
|---|---|
| Quantization | Mixed-int4 (e.g. Qwen3 0.6B) fastest; Q8 higher quality, slower |
| Context length | KV cache grows linearly; real context comes from container metadata |
| Model size | Catalog RAM guidance per model (2–6 GB free RAM) |
| Device RAM pressure | Can trigger GPU→CPU fallback; `ModelResourceGuard` refuses over-budget loads |

See [Performance](../PERFORMANCE.md) for ranges and optimization tips.

---

## NPU (Future)

Qualcomm NPU (QNN) acceleration is the **next milestone** on the roadmap. The
same `.litertlm` containers will run on the NPU — no model re-downloads. The
legacy `QUALCOMM_QNN` enum value already reserves the slot.

---

## Related

- [LiteRT-LM Integration](litert-lm.md) — the runtime behind both backends
- [Model Formats](model-formats.md) — what the backends can execute
- [Performance](../PERFORMANCE.md) — expected speeds, RAM, battery