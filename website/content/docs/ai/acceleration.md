# Acceleration

How AndroLLM accelerates `.litertlm` inference — CPU XNNPACK vs the OpenCL GPU
delegate, fallback and recovery, and what `MemoryStats` tells you.

---

## Two Backends

| Backend | Implementation | Notes |
|---|---|---|
| **CPU** | LiteRT-LM on **XNNPACK** | Default, works everywhere, no driver requirements |
| **GPU** | **OpenCL-based LiteRT GPU delegate** | Faster on capable devices; requires a working OpenCL driver |
| **NPU** | **LiteRT NPU delegate** (vendor dispatch) | Qualcomm Hexagon, MediaTek NeuroPilot, Google Tensor |
| **AUTO** | **Automatic selection** | NPU → GPU → CPU, resolved at model load |

Backend selection happens at engine initialization (`LiteRtLmEngine`) and is
reported through `EngineDebugInfo`/`EngineStats`. `HardwareBackendProbe` detects
SoC vendor and NPU availability at startup. `EngineCrashGuard` auto-disables
a backend after 3 consecutive failures and falls back to the next in the chain.

---

## `BackendType` and Legacy Values

`BackendType` in the engine's `models` package enumerates backends:

| Value | Produced? | Purpose |
|---|---|---|
| `CPU` | ✅ | Active CPU backend (XNNPACK) |
| `GPU` | ✅ | Active GPU delegate backend (OpenCL) |
| `NPU` | ✅ | Active NPU delegate (vendor dispatch) |
| `AUTO` | ✅ | Automatic selection: NPU → GPU → CPU |
| `QUALCOMM_QNN` | ❌ | Legacy — never produced; kept for persisted-state/UI compat |
| `LLAMA_CPP_VULKAN` | ❌ | Legacy — never produced; kept for persisted-state/UI compat |
| `ONNX_RUNTIME` | ❌ | Legacy — never produced; kept for persisted-state/UI compat |
| `VULKAN` | ❌ | Legacy — never produced; kept for persisted-state/UI compat |

The legacy values exist **only** so old persisted state serializers and UI code
can still read historical records. New sessions always report `CPU`, `GPU`,
`NPU`, or `AUTO`.

---

## GPU → CPU Fallback

GPU acceleration is automatic and safe:

1. The engine initializes the OpenCL delegate for the loaded container.
2. If initialization fails, or the delegate errors during a session
   (driver crash, memory pressure, thermal state), the engine falls back to
   the CPU backend **in the same session** — no reload needed from the user.
3. The fallback is recorded in `MemoryStats` so it is visible in diagnostics
   and telemetry.

This mirrors the old corruption-recovery philosophy, minus the native layer:
instead of recreating a native context, the engine re-arms the runtime on a
different backend.

---

## Corruption Recovery

`MemoryStats` tracks recovery activity:

| Field | Meaning |
|---|---|
| `gpuFree` | Free GPU memory as reported by the delegate |
| `gpuTotal` | Total GPU memory available to the delegate |
| `recoveryCount` | Number of GPU→CPU fallback/recovery events this session |

A rising `recoveryCount` with a low `gpuFree` usually means the GPU cannot fit
the working set — close other GPU-heavy apps or use a smaller model.

---

## NPU Acceleration

NPU inference is supported via the LiteRT NPU delegate with vendor-specific
dispatch:

| Vendor | NPU Block | Supported |
|---|---|---|
| Qualcomm | Hexagon HTP (HTPv73/75) | ✅ |
| MediaTek | NeuroPilot (APU) | ✅ |
| Google | Tensor TPU | ✅ |

`HardwareBackendProbe` detects the SoC vendor and NPU availability at engine
initialization. NPU is preferred over GPU when available and stable. If NPU
initialization fails, the engine falls back to GPU, then CPU.

`EngineCrashGuard` tracks per-backend failures and auto-disables NPU after
3 consecutive failures, falling back to GPU or CPU.

Note: `BackendType.QUALCOMM_QNN` is a legacy compat value, not a working
NPU backend. The actual NPU backend is `BackendType.NPU`.

---

## Measuring Acceleration

- **Developer screen → Benchmark** — tokens/sec per backend
- **`EngineStats` / `MemoryStats`** — backend in use, GPU memory, recovery
  count
- Logcat tag `AndroLLM-Engine` — backend selection and fallback events

---

## See Also

- [LiteRT-LM Engine](litert-lm.md) — runtime lifecycle and compat layer
- [Performance Guide](../PERFORMANCE.md) — context budgeting, RAM, tok/s