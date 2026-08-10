# On-Device Image Generation

Generate images locally on supported Android devices — no cloud, no prompt or image
ever leaves the phone. The feature is powered by **stable-diffusion.cpp** (the ggml
backend, the same inference stack already used by the llama.cpp engine) with **Vulkan
GPU acceleration** and automatic CPU fallback.

> **Hardware rule (runtime-driven):** support is decided by **actual runtime
> initialization**, never by chipset names or product allowlists — the same device
> that runs Local Dream reports the same tiers here. The engine is a **universal,
> pluggable backend framework**: every accelerator (Qualcomm Hexagon, Android NNAPI,
> MediaTek APU, Samsung Exynos, Google Tensor/EdgeTPU, Rockchip RKNN, Huawei HiAI,
> Vulkan GPU, optional OpenCL, CPU) is an independent plugin implementing the same
> [ImageBackend](../core/imagegen/src/main/java/io/androllm/core/imagegen/backend/ImageBackend.kt)
> contract — `initialize() / supportsModel() / generate() / shutdown() /
> getCapabilities()`. Backends are probed **at startup** in preference order
> (vendor NPU → NNAPI → Vulkan GPU → CPU) and the first that initialized is used;
> a failed tier never stops the chain. "Unsupported Device" appears **only** when
> every backend fails to initialize.

---

## Quick Start

1. Open the **Images** tab (bottom navigation, or Home → **AI Images** card).
2. If the device is supported, the capability badge reads **GPU + NPU**, **GPU Only**,
   **NPU**, **CPU Only** etc. — derived from which runtimes actually initialized.
   Unsupported devices see **Unsupported** with the exact init failure for each tier.
   During generation the UI announces each fallback live
   ("NPU unavailable — trying Vulkan GPU…").
3. Download a model in **Models** (SD 1.5 ≈ 2.5 GB fp16, or the Q4_K_M quant ≈ 800 MB).
4. Type a prompt — *"an astronaut standing on a cloud at sunset"* — pick a style
   preset, aspect ratio and resolution, then tap **Generate**.
5. Watch the state machine (Preparing → Loading Model → Generating → Finalizing),
   cancel anytime, then **Save** / **Share** / **Regenerate**.

The agent can also generate images for you: enable Tool Calling and say
*"generate an image of a cyberpunk city at night"* — the `generate_image` tool runs
the same pipeline.

---

## Device Capability Detection

`core:imagegen` ships a robust compatibility checker
([`DeviceCapabilityChecker.kt`](../core/imagegen/src/main/java/io/androllm/core/imagegen/DeviceCapabilityChecker.kt))
that evaluates every axis of the hardware rule:

Every axis is a **runtime fact** — no chipset-name allowlists, product whitelists or
Hexagon-generation inference from SoC platform strings:

| Axis | Runtime evidence |
|---|---|
| **CPU ready** | The vendored native library actually loaded (symbols resolved, correct ABI — `NativeRuntimeValidator`) + API 29+ + 6 GB+ RAM. Never decided by the SoC. |
| **GPU ready** | Vulkan driver initialized through the real runtime (`nativeVulkanAvailable` + `nativeVulkanInfo` version ≥ 1.1, no non-CPU device needed) |
| **NPU ready** | QNN daemon (`libstable_diffusion_core.so`) bundled AND Qualcomm AI Engine libraries present (`libQnnSystem.so` / `libQnnHtp*.so` on the device or extracted). Whether a model executes is proven at daemon-init time — a failure is cached and falls back to GPU/CPU |
| **Hexagon generation** | `libQnnHtpV{68,69,73,75,78}Stub.so` found on disk — *reported for diagnostics only, never a gate* |
| **NNAPI** | Real NDK enumeration — `NeuralNetworks_getDeviceCount` + `ANeuralNetworksDevice_getName` via dlopen'd `libneuralnetworks.so` (API 29+), reflection fallback for API 28. Feeds the universal NNAPI backend + vendor plugins |
| **Vendor NPUs** | NNAPI device names (`mediatek-apu`, `samsung-npu`, `google-edgetpu`, `rknn`, `hiai`…) and vendor runtime libs on disk (`libNeuroPilot.so`, `libedgetpu.so`, `librknnrt.so`, `libhiai.so`…) — runtime evidence for the MediaTek/Samsung/Google/Rockchip/Huawei plugins |
| **OpenCL** | `libOpenCL.so` present on the device (optional GPU tier, runtime not bundled yet) |
| **Android version** | `Build.VERSION.SDK_INT` — the feature requires **API 29+** (Android 10) |
| **Available RAM** | `ActivityManager.getMemoryInfo().totalMem` vs. model requirement |
| **Storage** | `StatFs` on the external files dir vs. model file size |
| **SoC name** | `Build.SOC_MODEL` etc. — a **label for diagnostics only**, never a gate |

### Capability badges

| Badge | Meaning |
|---|---|
| **GPU + NPU** | Vulkan GPU and the QNN NPU runtime both initialized |
| **GPU Only** | Vulkan GPU initialized; NPU runtime absent — CPU + GPU execution |
| **NPU** | Daemon-only device (QNN works, no Vulkan) — generation via the daemon |
| **CPU Only** | CPU runtime initialized (any arm64 device with a loading runtime) |
| **Unsupported** | CPU **and** NPU both failed runtime initialization — feature disabled with the exact reasons |

The check runs lazily on first screen open and is cached by the `RuntimeSelector`;
results are exposed through `RuntimeSelector.probe()` so the UI, settings, the agent
tool and Diagnostics all read the same verdict.

---

## Architecture

The Kotlin side is split into focused packages (mirroring the Local Dream tiers):

```
core:imagegen/src/main/java/io/androllm/core/imagegen/
├── backend/          BackendType (10 plugins + BackendFamily), ImageBackend contract
│   ├── gpu/          VulkanBackend (sd.cpp "vulkan0") · OpenClBackend (optional)
│   ├── cpu/          CpuBackend    (sd.cpp "cpu")
│   ├── npu/          NpuBackend    (Qualcomm QNN/MNN daemon — the bundled vendor NPU)
│   ├── nnapi/        NnapiBackend  (universal Android NNAPI accelerator path)
│   └── vendor/       VendorNpuBackend base + MediaTek/Samsung/Google/Rockchip/Huawei
├── runtime/          RuntimeSelector — probes ALL backends, vendor NPU → NNAPI → GPU → CPU
├── models/           ModelRuntimeSupport (model-declared runtimes), ModelSupportMatrix,
│                     ModelDetection (GGUF/safetensors metadata)
├── pipeline/         GenerationPipeline — fallback chain, progress, persistence
├── downloader/       ModelDownloader — queue, resume, retry, checksum verify
├── manager/          ImageModelManager — install status, compatibility, recommendations
├── repository/       ImageGenRepository — history, favorites, gallery
├── storage/          ModelStorage — on-device layout, free space
├── cache/            PipelineCache — resident-model keep-alive
├── diagnostics/      ImageGenDiagnostics — per-backend init results + reasons
├── error/            ImageGenError + ErrorBuilder — actionable errors
└── utils/            GgufMetadata, SafetensorsHeader, Checksum, ModelFiles
```

> **Adding a backend** = implement [ImageBackend](../core/imagegen/src/main/java/io/androllm/core/imagegen/backend/ImageBackend.kt)
> (or subclass [VendorNpuBackend](../core/imagegen/src/main/java/io/androllm/core/imagegen/backend/vendor/VendorNpuBackend.kt))
> and register it with one `@Binds @IntoSet` line in
> [ImageGenModule](../core/imagegen/src/main/java/io/androllm/core/imagegen/di/ImageGenModule.kt).
> The probe, the fallback chain, the model matrix and Diagnostics all pick it up
> automatically — nothing else changes.

```
Prompt (user or agent tool)
      │
      ▼
┌─────────────────────────── core:imagegen ───────────────────────────┐
│  DeviceCapabilityChecker ──► RuntimeSelector.probe()                 │
│        (SoC / Vulkan / NPU / QNN / NNAPI / RAM / storage)            │
│                                                                      │
│  GenerationPipeline:                                                  │
│    resolve model → validate → RuntimeSelector.selectionFor(model)     │
│      → vendor NPU (Qualcomm daemon, then MediaTek/Samsung/Google/     │
│                    Rockchip/Huawei plugins)                           │
│      → NNAPI (universal accelerator path)                            │
│      → Vulkan GPU → OpenCL → CPU                                      │
│      → all failed: ErrorBuilder → actionable ImageGenError            │
│    every tier announces itself ("trying NNAPI…")                      │
│    model keep-alive via PipelineCache (reload only on model/backend    │
│    switch) · progress · cancellation · timing                         │
│  ModelDownloader (resume/queue/retry/SHA-256 verify)                   │
└───────────────────────────────┬──────────────────────────────────────┘
                                ▼
┌─────────────────────────── imagegen (native) ───────────────────────┐
│  JNI bridge (ImageGenJniBridge)                                      │
│  stable-diffusion.cpp + ggml Vulkan (vendored, same stack as llama)  │
│  Local Dream daemon bridge (libstable_diffusion_core.so, QNN/MNN)    │
│  SD1.5 / SDXL / Flux → latent → VAE decode → RGBA bitmap             │
└───────────────────────────────┬──────────────────────────────────────┘
                                ▼
┌─────────────────────────── feature:images ──────────────────────────┐
│  ui/screens/ImagesScreen · ui/screens/DiagnosticsScreen             │
│  ui/components/ (prompt studio, model manager, gallery, error card)  │
└───────────────────────────────┬──────────────────────────────────────┘
                                ▼
                      Preview / Save / Share
```

The image engine is **completely isolated** from the chat LLM engine — it has its own
native library (`libandrollm_imagegen.so`), its own module, and its own lifecycle. No
text-generation code is mixed with image-generation code.

### Module map

| Module | Type | Responsibility |
|---|---|---|
| `:imagegen` | Android library + C++ | Vendored stable-diffusion.cpp, ggml Vulkan build, JNI bridge, Local Dream daemon source |
| `:core:imagegen` | Kotlin library | Capability check, runtime selection, backends, pipeline, downloader, model manager, repository, diagnostics, errors |
| `:feature:images` | Feature UI | Prompt studio, gallery, model manager UI, diagnostics screen, unsupported state |
| `:core:tools` | Agent | `generate_image` agent tool |

---

## Runtime Selection & Automatic Fallback

The `RuntimeSelector` is the automatic backend brain — it probes **every** registered
plugin at startup (in parallel, off the main thread) and never stops after the first
failure:

```
Device
  └─ Native Vendor NPU  (Qualcomm daemon + MediaTek/Samsung/Google/Rockchip/Huawei)
  └─ Android NNAPI      (universal accelerator path — every vendor exposes its NPU)
  └─ Vulkan GPU
  └─ CPU (last resort)
  └─ all failed ──▶ actionable error (never crash)
```

- **Auto mode** (default): vendor NPU → NNAPI → Vulkan → CPU, honoring the toggles
  (`Enable NPU` also covers the NNAPI tier).
- **Force modes** (`Force GPU` / `Force CPU` / `Force NPU` in Settings): only that
  family runs; if it is unavailable the error explains exactly why.
- **Every tier is announced live**: the UI shows "NPU unavailable — trying Vulkan
  GPU…" while the chain runs, and the final error lists each backend's reason.
- **Model-declared runtimes** ([ModelRuntimeSupport](../core/imagegen/src/main/java/io/androllm/core/imagegen/models/ModelRuntimeSupport.kt)):
  each model declares which backends it can run on (SD 1.5: CPU/Vulkan/NNAPI/
  Qualcomm; SDXL: CPU(Limited)/Vulkan/NNAPI/Qualcomm; Flux: CPU/Vulkan/NNAPI;
  Anima: NPU only; QNN/MNN bundles: daemon only). A backend is only ever selected
  when the model declares it AND its runtime actually initialized.
- **Honest unavailability**: NNAPI/vendor/OpenCL plugins detect their hardware
  (NNAPI device names, vendor libs) but report *not bundled* until their runtime
  ships — the chain falls through automatically instead of failing.
- **Model compatibility matrix** (per-backend columns, wrapped chips) is shown in
  the model manager and used for recommendations.

## Model Detection

`ModelDetection` inspects the **contents** of downloaded models instead of trusting
filenames:

| Source | What it detects |
|---|---|
| GGUF header | architecture (`sd`, `sdxl`, `sd3`, `flux`), quantization (`general.file_type`) |
| safetensors header | tensor names + `__metadata__` (`ss_*` → LoRA base model) |
| File layout | LoRA / VAE / TAESD / ControlNet / tokenizer presence |
| Marker files | Local Dream QNN bundles (`SDXL`, `ANIMA`, `finished`, `npucustom`) |

## Model Downloads

The `ModelDownloader` (queue-based, single worker) supports **resume** (byte-range
`.part` files, HTTP 416 handling), **pause**, **cancel**, **retry**, progress, a
storage guard, and **SHA-256 verification** whenever the catalog carries an expected
hash — a corrupt download is reported as *Broken* instead of failing at generation
ime.

---

## Backend: stable-diffusion.cpp + ggml Vulkan

The native backend is **stable-diffusion.cpp** vendored from upstream
(https://github.com/leejet/stable-diffusion.cpp). Rationale — verified against the
reference before implementation:

- It uses the **same ggml runtime as llama.cpp**, which the engine module already
  builds for this exact toolchain (Android NDK + Vulkan + glslc host shader compile).
- GGUF models are downloadable directly from HuggingFace — no model conversion
  tooling required on the developer's machine or the device.
- CPU and Vulkan GPU execution are both supported by the same code path; the runtime
  is selected per-generation and can fall back to CPU.
- The C API (`sd_new_context` / `sd_txt2img` / `sd_set_progress_callback` /
  `free_sd_images`) maps cleanly onto a small JNI surface.

### Model format

| | SD 1.5 | SDXL |
|---|---|---|
| **Format** | GGUF (fp16 / Q4_K_M) | GGUF |
| **fp16 size** | ≈ 2.5 GB | ≈ 6.9 GB |
| **Q4_K_M size** | ≈ 800 MB | ≈ 2.4 GB |
| **Device RAM (Q4_K_M)** | 4 GB+ recommended | 8 GB+ recommended |
| **Download source** | HuggingFace `stable-diffusion-v1-5` / `city96/SD1.5-gguf` | HuggingFace GGUF repos |

> The model catalog (`ImageGenModel.kt`) lists only verified URLs; the downloader
> checks HTTP `Content-Length` on HEAD before starting, verifies the file on
> completion, and never leaves partial files behind.

### Native build

`imagegen/src/main/cpp/CMakeLists.txt`:

- Vendored sources: `stable-diffusion/` (upstream SD.cpp) and `ggml/` (its ggml fork)
- `SD_VULKAN=ON` when a Vulkan SDK is present — shaders are compiled **at build time
  on the host** through `host-vulkan-shaders-toolchain.cmake.in`, exactly like the
  llama.cpp engine
- Outputs `libandrollm_imagegen.so` for `arm64-v8a`
- Compile pool capped to avoid clang crashes on large ggml files

Build from the repo root:

```bash
# Requires a Vulkan SDK with glslc on PATH (same requirement as the engine module)
./gradlew :imagegen:assembleDebug
```

Without a Vulkan SDK the module still builds — SD.cpp falls back to CPU-only, and the
capability checker reports GPU-only/unsupported accordingly.

---

## Generation States

The orchestrator (`ImageGenerationEngine.kt`) is a strict state machine, driven off
the main thread (a dedicated single-thread dispatcher), fully cancellable:

```
Idle ──▶ Preparing ──▶ Loading Model ──▶ Generating ──▶ Finalizing ──▶ Completed
   ▲        │               │               │              │
   └────────┴───────────────┴───────────────┴──────────────┴──────────▶ Failed
```

| State | Meaning |
|---|---|
| **Idle** | No generation in flight |
| **Preparing** | Validating prompt/settings, resolving model path, checking capability + storage |
| **Loading Model** | `sd_new_context` (or reusing a cached context — models are kept warm across generations) |
| **Generating** | Diffusion loop; real-time `progress %` from the native callback |
| **Upscaling** | *(when enabled)* post-diffusion upscale pass |
| **Finalizing** | Decoding RGBA → `Bitmap`, writing preview + history metadata |
| **Completed** | Result image ready for preview / save / share |
| **Failed** | Error surfaced with a clear reason (OOM, storage, model missing, device lost) |

**Cancellation** is supported at any time: the JNI bridge flips a native cancel flag
checked inside the diffusion loop, so a cancel takes effect promptly without killing
the process. The native context is freed safely on every run end.

---

## Prompt Experience

`feature:images` provides a full prompt studio:

- **Prompt** — the main text-to-image prompt
- **Negative prompt** — what to avoid (unconditional prompting)
- **Style presets** — Realistic · Anime · Cinematic · Fantasy · Product Shot · Poster ·
  Illustration · Wallpaper; each appends a curated quality/medium phrase to the prompt
- **Aspect ratio** — 1:1, 16:9, 9:16, 4:3, 3:4, 21:9 (mapped to the SD1.5 latent sizes)
- **Resolution** — 512, 640, 768 base sizes (with upscale option)
- **Number of images** — 1–4 per batch
- **Seed control** — random seed per run, or fixed seed for reproducible results
- **Guidance scale (CFG)** — 1.0–12.0, default 7.0
- **Steps** — 1–60, default 20
- **Prompt history & suggestions** — recent prompts are remembered per session and
  offered as quick chips

---

## Save / Share

Every generated image is available for:

- **Save to gallery** — persisted via MediaStore (`Pictures/AndroLLM`)
- **Share** — `FileProvider` (already wired in `app/res/xml/file_paths.xml`) with a
  content URI and `Intent.createChooser`
- **Regenerate** — re-run with a new random seed (or the same seed if you locked it)
- **Copy prompt** — puts the exact prompt + settings on the clipboard
- **View metadata** — prompt, negative prompt, seed, steps, CFG, model, resolution,
  elapsed time

**History** is stored locally in JSON under the app's files dir (`imagegen/history.json`)
with thumbnail paths; the gallery screen shows recent generations with their prompts.

---

## Model Management

`ImageModelManager` handles the full model lifecycle:

- **Download** — queued, resumable, pausable, retryable, storage-guarded
- **Verify** — SHA-256 checksum when known, size check, registry completeness
- **Status** — every model reports one of: Ready / Downloading / Incomplete /
  Broken (re-download) / Needs conversion / Not downloaded, with the missing files
  listed
- **Compatibility** — CPU / GPU / NPU matrix per model + RAM requirement, computed
  against the real device via `ModelSupportMatrix`
- **Recommendations** — when no compatible model is installed, the UI suggests
  SD 1.5, DreamShaper 8, Realistic Vision V5.1 and Flux Schnell with download
  buttons
- **Storage** — live `StatFs` free space reported before each download
- **Delete** — frees the files (and the downloader cancels first)
- **Select active** — the active model is used by the studio and the agent tool

Models live in `context.getExternalFilesDir()/imagegen/models/` and are never written
to shared storage.

---

## Agent Integration (`generate_image` tool)

The agent platform exposes image generation as a first-class tool:

```
ToolSpec: generate_image
Args:     prompt (required), negative_prompt, width, height, steps, cfg, seed, count
```

- Runs **only** after the same permission gate as every other tool (Settings →
  Automation → Tool Calling)
- Returns the saved image path + prompt, or a clear failure reason (unsupported
  device, no model, OOM) that the LLM reports honestly
- The tool respects the same `DeviceCapabilityChecker` verdict — on an unsupported
  device it returns an explanatory error instead of pretending to generate

---

## Safety

- **NSFW**: the SD1.5 base models used by the catalog are the pruned base releases;
  stable-diffusion.cpp does not ship an NSFW classifier, and the app does **not**
  claim one. Content filtering is exposed as a **planned** control in the Settings
  section — it is not faked.
- All generation is local; prompts and images never leave the device.
- Downloads are HTTPS-only from verified HuggingFace URLs.

---

## Settings

`Settings → Image Generation` (`ImageGenerationSection.kt`):

| Setting | Description |
|---|---|
| Enable Image Generation | Master switch (the Images tab is hidden when off) |
| Device compatibility status | Live badge + SoC / GPU / NPU / RAM / storage readout |
| **Backend** | **Auto** (NPU → GPU → CPU) or **Force GPU / Force CPU / Force NPU** |
| CPU fallback | Enable the last-resort tier in Auto mode |
| **Low memory mode** | Smaller VRAM budget + fewer threads for 6 GB devices |
| **Debug logs** | Extra backend/timing logging for Diagnostics |
| **Experimental features** | Opt-in newer samplers / schedulers |
| Default model | Active SD model (from the catalog incl. DreamShaper / Realistic Vision / Flux) |
| Default aspect ratio | Applied when opening the studio |
| Default resolution | Base size for new generations |
| Number of steps | Default steps (1–50) |
| Guidance scale | Default CFG (1.0–15.0) |
| Seed behavior | Random or fixed seed |
| Safety filter | Basic on-device prompt guard (a full classifier is not shipped) |
| Auto-save | Save generated images to the gallery automatically |
| Show metadata | Include prompt/settings in the history list |

## Diagnostics

The **Images → Diagnostics** screen (`DiagnosticsScreen.kt`) shows the full report
from `ImageGenDiagnostics`: SoC, Android/ABI, Vulkan GPU + driver, NNAPI devices,
vendor NPU evidence, NPU chip + Hexagon generation, QNN runtime + daemon status,
**every backend plugin with its real init result + failure reason** (Qualcomm,
MediaTek, Samsung, Google, Rockchip, Huawei, NNAPI, Vulkan, OpenCL, CPU), the live
probe chain, loaded model/backend, last inference timing (model · backend · ms),
model storage and recent errors — including the daemon startup log.

## Actionable Errors

Every failure surfaces as an `ImageGenError` (not a bare "Generation failed"):

```
No compatible runtime found for Stable Diffusion XL Base 1.0
Installed models: none
Why:
• Vulkan GPU: Needs at least 12 GB RAM (this device: 8.0 GB)
• CPU: Runs but is slow…

Recommended for this device:
• Stable Diffusion 1.5 (Vulkan GPU) (4.0 GB)
• DreamShaper 8 (SD 1.5) (2.0 GB)

Action: download the recommended model, then retry.
```

The error card renders the recommended models with **Download** buttons, an **Open
Model Manager** action, and a **Diagnostics** shortcut — plus a `Retry` where
appropriate.

---

## Performance Notes

- Generation runs on a **dedicated single-thread executor** — never the main thread;
  no ANRs even for 60-step runs.
- The native model context is **reused across generations** when settings (model /
  resolution) allow it, avoiding repeated load cost.
- Previews are downsampled before display; full-res bitmaps are kept only while
  needed, then recycled.
- If the device runs out of memory or storage, the failure state explains exactly
  what happened and suggests the Q4_K_M quant or a smaller resolution.
- The compile pool in the native build is capped to avoid toolchain crashes on large
  ggml translation units.

---

## Troubleshooting

| Problem | Likely cause / fix |
|---|---|
| "Unsupported" badge | CPU **and** NPU both failed runtime init — Diagnostics shows the exact failure for each tier (re-probe after re-install) |
| Empty result after long run | Out of memory — use Q4_K_M and/or 512×512; watch the failed-state message |
| Download stalls | Storage check or network interruption — retry resumes verified chunks |
| Slow first generation | Model is being loaded into the native context; subsequent runs reuse it |
| Share fails | FileProvider path — verify `imagegen` is covered in `app/res/xml/file_paths.xml` |

---

## Reference

- [stable-diffusion.cpp](https://github.com/leejet/stable-diffusion.cpp) — on-device SD
  inference (ggml backend, Vulkan)
- [Local Dream](https://github.com/xororz/local-dream) — reference Android SD app
  (CPU MNN + Snapdragon QNN NPU; the design goals this feature mirrors)
- GGUF models: `stable-diffusion-v1-5` and `city96/SD1.5-gguf` on HuggingFace
