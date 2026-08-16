# Acceleration Guide

How AndroLLM executes `.litertlm` models on device hardware.

---

## Backends

The engine supports three execution backends behind a proper backend layer
(`engine/backend/`): `CpuBackend`, `GpuBackend` and `NpuBackend`, all
implementing `InferenceBackend`. The chat engine, tool system, memory system,
embeddings, voice pipeline and streaming layer never care which backend is
executing — only the backend implementation changes. A future LiteRT delegate
is added by implementing another `InferenceBackend`; no engine/UI change.

The enum `BackendType` also keeps legacy values (`QUALCOMM_QNN`,
`LLAMA_CPP_VULKAN`, `ONNX_RUNTIME`, `VULKAN`) for serializer/UI
compatibility — the engine **never produces them**.

| Backend | Accelerator | Runtime | Notes |
|---|---|---|---|
| `NPU` | SoC NPU (Qualcomm Hexagon HTP, MediaTek NeuroPilot, Google Tensor) | LiteRT NPU delegate (vendor dispatch) | Only when the startup probe finds a usable vendor dispatch library |
| `GPU` | GPU (OpenCL) | LiteRT GPU delegate | **Default** on devices without a usable NPU |
| `CPU` | CPU (XNNPACK) | LiteRT CPU kernels | Always available; automatic fallback target |

`AUTO` (the default preference) resolves to NPU → GPU → CPU at model load.
The user-selectable options are persisted in Preferences (`AUTO` / `NPU` /
`GPU` / `CPU`).

---

## Automatic Selection & Silent Fallback

At engine initialization the `HardwareBackendProbe` runs once and detects:

- SoC vendor (`ro.soc.*` / `ro.board.*` / `ro.hardware*` properties)
- Android Neural Networks API availability (package-manager feature)
- GPU identity (`ro.hardware.egl` + board heuristics)
- LiteRT vendor dispatch libraries (`libLiteRtDispatch_*.so`) reachable from
  the app's native library dir — the ONLY gate that makes the NPU delegate
  selectable

The result (`BackendCapabilities`) drives the adaptive settings UI and the
load-time chain. At load, `BackendSelector` produces an ordered candidate
list pruned by model compatibility (`supportsCpu` / `supportsGpu` /
`supportsNpu` on the catalog entry) and the engine attempts each in turn:

```
NPU → GPU → CPU
```

If a backend's `Engine.initialize()` throws, the engine closes it, logs the
failure internally and tries the next — the user never notices. If every
candidate fails, the load fails with the last error (inference never silently
disappears). No crashes, no hangs, no app restart required.

**Model compatibility:** catalog entries declare `supportsNpu` (default
false — LiteRT-LM NPU execution requires SoC-specific model builds). A model
with `supportsNpu = false` skips NPU and falls back to GPU → CPU without user
interaction.

---

## NPU Path

- Vendor dispatch libraries must be present in the app's native library dir
  (`libLiteRtDispatch_<vendor>.so` + the vendor driver, e.g. QNN/QAIRT for
  Qualcomm); Google Tensor uses the dedicated LiteRT-LM `GOOGLE_TENSOR`
  backend
- **Silent support:** on devices without an NPU driver the app behaves
  exactly as before — no NPU option, no disabled buttons, no banners, no
  toasts
- The NPU delegate is initialized at model load; initialization failure falls
  back to GPU → CPU silently
- Engine Status shows `Execution Backend: NPU`, Vendor, Accelerator and
  Delegate rows when NPU is active

### Deploying NPU vendor libraries (Qualcomm QNN)

The NPU backend needs five libraries in `app/src/main/jniLibs/arm64-v8a/`:

| Library | Purpose |
|---|---|
| `libLiteRtDispatch_Qualcomm.so` | LiteRT → QNN bridge (dispatch delegate) |
| `libQnnSystem.so` | QNN system context / versioning |
| `libQnnHtp.so` | Hexagon HTP driver (aarch64) |
| `libQnnHtpV81Stub.so` | FastRPC stub for the DSP skel |
| `libQnnHtpV81Skel.so` | Hexagon V81 skeleton (runs on the DSP) |

**The dispatch library must match the `litertlm-android` AAR's runtime ABI —
this is the #1 deployment trap.** The AAR (built from Google-internal LiteRT
sources) hard-rejects a mismatched lib with `litert_dispatch.cc: …:
Unsupported dispatch runtime version` and silently falls back to GPU. Two
independent constraints must both hold:

1. **Struct layout.** `LiteRtDispatchApi` must match the AAR's runtime: for
   `litertlm-android` **0.16.0** that is the post-`LiteRtAbiHeader` layout
   (56-byte struct; `abi_header` at offset 0, `version` at offset 8) — i.e.
   the dispatch lib from LiteRT **v2.2.0** era, **not** v2.1.6 (48-byte,
   pre-`abi_header`).
2. **QNN API version.** The dispatch lib requires `libQnnSystem.so` ≥ 1.11.0
   (QAIRT **2.47**). OEM `/odm` QNN libs (e.g. v1.4.0 on SM8845) fail with
   `Qnn System library version … is mismatched. The minimum supported
   version is 1.11.0`.

**Where the working set comes from** (verified on SM8845 / Hexagon V81):

- Dispatch lib: LiteRT v2.2.0 release asset
  `npu_runtime_libraries.zip` → `qualcomm_runtime_v81/…/
  libLiteRtDispatch_Qualcomm.so` (Google-prebuilt, v2.2.0-era)
- QNN libs: the same zip's `fetch_qualcomm_library.sh` downloads QAIRT 2.47
  from softwarecenter.qualcomm.com (account-gated). Prefer a device/build
  that already bundles the QAIRT-2.47 set — e.g. the Google AI Edge gallery
  app's per-SoC release APK (`ai-edge-gallery-sm8850.apk` ships all four
  `libQnn*` libs for V81).

Three app-side requirements make the above actually load:

1. **`useLegacyPackaging = true`** in `app/build.gradle.kts` `jniLibs {}` —
   the dispatch delegate finds its vendor libs via a filesystem `readdir` of
   `applicationInfo.nativeLibraryDir`; without legacy packaging that dir is
   empty (libs stay compressed in the APK) and NPU can never initialize.
2. **`<uses-native-library android:name="libcdsprpc.so"
   android:required="false" />`** in the manifest — FastRPC transport the
   HTP stub needs from the vendor partition.
3. **Seed `ADSP_LIBRARY_PATH`** (app + `/odm`/`/vendor` skel dirs) in
   `Application.onCreate` — `libQnnHtp.so` reads it **once** at dlopen, so it
   must be set before any LiteRT/QNN lib loads.

Verify on-device: the logcat sequence `NPU accelerator registered` →
`Loading shared library: …/libLiteRtDispatch_Qualcomm.so` → `Loading qnn
system shared library` → `QnnBackend_create done` → FastRPC
`remote_handle64_open … for file:///libQnnHtpV81Skel.so` →
`QnnDevice_create done` ends with the engine log
`model loaded: … backend=NPU delegate=LiteRT Delegate`.

---

## GPU Path

- Enabled by default (or when NPU is unavailable); the OpenCL delegate is
  initialized at model load
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

## Benchmarking

Developer Mode → **Benchmark Backends** runs the identical prompt through every
usable backend (NPU → GPU → CPU) and reports throughput (tok/s), first-token
latency, initialization time and peak RAM per backend, then restores the
original backend. Useful for comparing delegate performance on a given device.

---

## Performance Notes

| Factor | Impact |
|---|---|
| Quantization | Mixed-int4 (e.g. Qwen3 0.6B) fastest; Q8 higher quality, slower |
| Context length | KV cache grows linearly; real context comes from container metadata |
| Model size | Catalog RAM guidance per model (2–6 GB free RAM) |
| Device RAM pressure | Can trigger GPU→CPU fallback; `ModelResourceGuard` refuses over-budget loads |
| NPU delegate | Only when the device bundles the vendor dispatch library; per-SoC model builds required |

See [Performance](../PERFORMANCE.md) for ranges and optimization tips.

---

## Related

- [LiteRT-LM Integration](litert-lm.md) — the runtime behind all three backends
- [Model Formats](model-formats.md) — what the backends can execute
- [Performance](../PERFORMANCE.md) — expected speeds, RAM, battery
