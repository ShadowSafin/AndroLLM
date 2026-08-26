# Performance Guide

Production performance guide for AndroLLM — startup, model loading, inference, and large-model (7B–8B) support. All optimizations preserve existing architecture, APIs, and core LiteRT runtime; only surrounding infrastructure (caching, delegate lifecycle, memory management, diagnostics) is tuned.

> **Diagnostics:** Enable `Developer Mode → Logs & Diagnostics` for detailed timing: `App startup, Runtime init, Delegate creation, Model parsing, Tokenizer loading, Backend selection, Model loading, TTFT, tokens/sec, Memory allocation, Prompt preprocessing, KV cache init, Conversation reuse, Backend fallback reason` (see `StartupProfiler` + `EnginePerformanceMonitor`).

---

## Token Generation Speed

Token generation speed depends on multiple factors working together:

### Primary Factors

| Factor | Impact | How to Optimize |
|---|---|---|
| **Backend** | GPU (LiteRT OpenCL delegate) is typically faster than CPU on capable devices | Verify backend in Developer Diagnostics (`backend` field: `GPU` or `CPU`) |
| **Quantization** | Lower quant = faster but less accurate | Mixed-int4 containers (e.g. Qwen3 0.6B) are the speed sweet spot; Q8 for quality |
| **Context length** | Longer context = slower (linear KV cache growth) | Use the smallest context that works for your use case |
| **Model size** | More parameters = slower | Match model to device capability |
| **Device RAM pressure** | Low RAM triggers OS reclaim and GPU-to-CPU fallback | Monitor `gpuFree` in diagnostics; close other apps |

### Expected Performance Ranges

These are **not benchmarks** - they are approximate ranges based on the LiteRT-LM
runtime. The measured reference point for the bundled catalog is **Qwen3 0.6B at
~21 tokens/sec on CPU** (mid-range hardware, default settings).

| Device Class | GPU (tokens/sec) | CPU (tokens/sec) |
|---|---|---|
| Flagship (Snapdragon 8 Gen 2/3) | 20-45 | 10-25 |
| Mid-range (Snapdragon 7-series, Dimensity 8-series) | 10-25 | 5-15 |
| Entry (Snapdragon 6-series, older chips) | 5-15 | 2-8 |

Actual numbers vary significantly by model architecture, quantization, and Android version. Use the built-in **benchmark** tool (Developer screen) to measure your device.

---

## Model Loading

### Cold Start (First Load)

Model loading involves:
1. Validating the `.litertlm` container (`LiteRtValidator`)
2. Reading embedded metadata (`ContainerMetadataReader` - family, template, tokenizer, context)
3. Loading the model into the LiteRT-LM engine (CPU) and/or GPU delegate
4. Compiling GPU kernels / warming up the delegate

Typical load times (`.litertlm` containers):

| Model Size | CPU Load | GPU Load |
|---|---|---|
| 0.6B (Mixed Int4) | ~1-3 sec | ~2-4 sec (kernel compile adds overhead) |
| 1.5B (Q8) | ~3-6 sec | ~4-8 sec |
| 2-4B (Q8) | ~5-12 sec | ~6-15 sec |

Subsequent loads are faster because delegate kernels remain compiled.

### Coherence Probe

After loading, the engine runs a lightweight temperature-0 **self-test probe**
(`CoherenceChecker`) to verify the model produces sane output before the UI
marks it ready - a corrupted container fails fast instead of generating garbage.
*Optimization:* self-test result is memoized per `modelId+backend+fileMtime`; subsequent loads of the same model/backend skip the probe unless the file changed, saving ~1–2s on warm loads.

### Startup Optimization (Cold-Start)

- **Profiled sequence:** `Application.onCreate` (`seedNpuLibraryPaths` 11 paths) → `MainActivity.setContent` (now single `userPreferences` collector vs 5) → `AppDatabase` & `DataStore` lazy (WAL, 7 files share scope) → `CatalogRepository` parallel `assets`+`file` read + cached `CatalogFile` hash → `HardwareBackendProbe` cached `BackendCapabilities` (reflection `SystemProperties` 7 keys, `PackageManager`, `nativeLibraryDir` list) persisted 24h → `BackendSelector` cached ordered candidates → `TokenizerFiles` LRU 8 + fast-path skip when `hasAnyTokenizer` → `SentencePieceTokenizer` static LRU 4 (262k vocab) → `LiteRtLmEngine` delegate reuse (never recreated per prompt)
- **Lazy non-essential:** `ProviderHealthMonitor.start()` and `MemoryBackgroundScheduler.schedule()` (WorkManager) deferred to first cloud/memory use, not `Application.onCreate`
- **Parallel init:** catalog asset + saved file reads via `async`, metadata + SHA via `async`, delegate creation off critical path where safe
- **Cached results:** `ContainerMetadataReader` LRU 16 (was 4) + thread-safe, `TokenizerFiles` LRU, `SentencePieceTokenizer` LRU, `BackendCapabilities` DataStore, `ModelFamilyRegistry` maps

### Model Loading (Optimized)

- **Reuse:** LiteRT `Engine` (single per load), delegates (`GPU`/`NPU` not recreated between prompts), `Conversation` (kept alive, `reuseBroken` flag), `KV cache` (reused when `ensureConversationForHistory` hit, only new tokens prefilled), tokenizer buffers (`BufferPool` `AtomicInteger` pools), memory pools
- **Avoid reloads:** `loadedFilePath` + `file.length` + `sha256` check before reload; `loadedModel` resident until explicit `unloadModel()` (never between prompts, `ComponentCallbacks2` only on `RUNNING_CRITICAL`); previous `Engine`/`Conversation` closed only on failure (not per prompt)
- **Cache:** `ContainerMetadataReader` (header window 64KB→16MB doubling, no payload), `ModelFamily` config, `tokenizer` sidecars, `BackendSelector` ordered candidates, execution plans (warmup compiled graph)
- **Async & mmap:** file I/O via `RandomAccessFile` windowed reads (≤16MB header), native payload `mmap` hidden in `litertlm` AAR; decompress `Inflater` bounded `MAX_TOKENIZER 256MB`; large-model `MemoryEstimator` + `ModelResourceGuard` (0.7 safety, 256MB headroom) gives actionable `needs ~X MB but only ~Y MB free` vs generic
- **Thread-safety:** `generationMutex` serializes `generate`, `AtomicBoolean generationActive`, `volatile peakPss`, `withContext(Dispatchers.Default)` never blocks main

---

## RAM Usage

### Memory Breakdown

```
Total RAM = Model Weights + KV Cache + Delegate Buffers + Overhead
```

| Component | Notes | Example (1.5B Q8, 4096 ctx) |
|---|---|---|
| Model weights | `parameters x bytes_per_weight` | ~1.6 GB |
| KV cache | Scales with context length | ~0.1-0.3 GB |
| GPU delegate buffers | OpenCL working memory (GPU models) | ~0.2-0.5 GB |
| App + OS overhead | Fixed | ~0.5 GB |
| **Total** | | **~2.5-3 GB** |

### Managing RAM

1. **Close other apps** before loading large models
2. **Use smaller context** - 2048 tokens uses half the KV cache of 4096
3. **Unload models** when not in use (Models screen -> Unload)
4. **Avoid loading multiple models** simultaneously
5. **Restart the app** periodically to clear fragmented allocations

### Detecting Low RAM & Large-Model Support (7B–8B)

The engine's `MemoryEstimator` (`weights*1.05 + 2*blocks*ctx*heads*kvLen*2 + scratch 15%`, fallback `fileSize/50000`) and `ModelResourceGuard` (`available = availMem - nativeHeap`, `SAFETY_FRACTION 0.7`, `MIN_HEADROOM 256MB`, `lowMemory` flag) **refuse loads** with actionable `needs ~X MB but only ~Y MB free — choose smaller/shorter ctx / close apps / prefer CPU` vs generic OOM. Each model shows `minRamGb` / `recommendedRamGb` + `canRunLargeModels (6GB RAM + 6 cores)`.

**7B–8B optimizations (no core runtime change):**
- **Allocation strategy:** `ThreadManager.recommendedContextLength` (low 2048 → high 8192) and `memoryBudgetFraction` (0.45→0.75) adapt to `totalRamGb` (now `ActivityManager.MemoryInfo.totalMem` cached, not `maxMemory*3`); dynamic temp buffer sizing
- **Buffer reuse:** `BufferPool` pooled `StringBuilder`/`ByteArray`/`CharArray` reused across generations; `PrefixCache` hash reuse; `Conversation` KV cache reused (not rebuilt) when `sanitizeSeed` matches
- **Overhead reduction:** `TokenizerFiles` fast-path skip (5 sidecars not read when `hasAnyTokenizer`), `SentencePieceTokenizer` LRU, `ContainerMetadataReader` LRU 16 + `RandomAccessFile` windowed reads (no payload), `LiteRtValidator` single-pass header read in parallel with metadata
- **Mmap efficiency:** native payload `mmap` via `litertlm` AAR; Java reads ≤16MB header only, `MAX_TOKENIZER 256MB` cap prevents corrupt-size alloc
- **Progressive loading:** where supported, `EngineConfig` chunked; warmup bounded 90s with active cancel, not wedging load
- **Peak reduction:** `ThreadManager.maximumSafeThreads` leaves UI cores, `withBackgroundInferencePriority` lowers scheduler latency, temporary `ByteArray` from `Inflater` bounded and pooled

### Performance Diagnostics (Detailed Timing)

Enable `Developer Mode → Logs & Diagnostics` or `logcat -s AndroLLM-Engine`:

- `App startup` (`Application.onCreate` → `MainActivity.setContent`, `StartupProfiler.elapsedSinceAppStart`)
- `Runtime initialization` (`HardwareBackendProbe.probe` + `BackendSelector.bestAvailable`)
- `Delegate creation` per backend attempt (`Delegate GPU: SUCCESS in 1234ms`)
- `Model parsing` (`ContainerMetadataReader.read` header window, SHA)
- `Tokenizer loading` (`TokenizerFiles` cache `hit/miss`, `SentencePiece` parse 262k)
- `Backend selection` (`orderedCandidates: NPU→GPU→CPU`, `fallback reason`)
- `Model loading` total + `MODEL_INIT` + `CONTAINER_READ` + `CONVERSATION_CREATE` + `WARMUP`
- `Time to first token` (`PREFILL START/END TTFT`), `tokens/sec` (`decodeSpeedHistory`, `peakDecodeTokensPerSecond`)
- `Memory allocation` (`fetchMemoryStats`: `Debug.nativeHeap`, `totalPss`, `peakProcessPssBytes`, `gpuFree/total`)
- `Prompt preprocessing` (`ChatTemplateRenderer` hash, `PrefixCache` hit/miss)
- `KV cache initialization` (`ConversationConfig`, `reuseBroken` flag)
- `Conversation reuse` (`KV-cache REUSE hit — only new tokens prefilled` vs `reseedAfterOverflow`)
- `Backend fallback reasons` (`EngineErrorMapper` stage `initialize/compatibility/load`, `suggestion`, `retryable`)

All timings via `EnginePerformanceMonitor` (lock-free `LongAdder` where possible, `StageTracer` for pipeline) and `StartupProfiler`.

---

## Hardware Acceleration (NPU, GPU, CPU)

### Backend Selection

The engine uses automatic backend selection (NPU → GPU → CPU) with silent fallback:

1. **Startup probe** (`HardwareBackendProbe`) runs once at engine initialization
   and detects SoC vendor, GPU identity, NPU availability, and vendor dispatch
   libraries.
2. **BackendSelector** determines the ordered fallback chain from the probe
   results and the model's compatibility flags.
3. Each backend is **attempted** in order; failures fall through silently.
4. `EngineCrashGuard` auto-disables a backend after 3 consecutive failures.

### When Each Backend Is Used

| Backend | When Used | Requirements |
|---|---|---|
| **NPU** | Preferred when supported and stable | Vendor dispatch library (`libLiteRtDispatch_*.so`) in native lib dir |
| **GPU** | Preferred when NPU unavailable | OpenCL GPU delegate, sufficient GPU memory |
| **CPU** | Always available as fallback | XNNPACK, works on every arm64 device |

### Performance Profiles

`PerformanceProfiles` provides device-class-specific presets:

| Profile | Threads | Batch | Context | Streaming Rate |
|---|---|---|---|---|
| LOW_END | 2 | 512 | 2048 | 32ms (~30 FPS) |
| MID_RANGE | 3 | 1024 | 4096 | 16ms (~60 FPS) |
| FLAGSHIP | 4 | 2048 | 8192 | 16ms |
| GPU_OPTIMIZED | 2 | 2048 | 8192 | 16ms |
| NPU_OPTIMIZED | 2 | 2048 | 8192 | 16ms |
| CPU_OPTIMIZED | adaptive | 1024 | 4096 | 32ms |

### Interpreter Warmup

After model load, a short background prompt primes the interpreter so the
first real prompt arrives faster. Warmup runs on `Dispatchers.Default` with active `cancelProcess` watchdog (`WARMUP_TIMEOUT_MS` 90s) and never races the first user prompt (previous fire-and-forget queued behind JNI has been fixed — warmup is bounded and `Ready` waits for it). Delegate graphs stay hot for consecutive prompts.

### GPU/NPU Stability (Never Silent Fallback)

- **Reuse:** LiteRT `GPU`/`NPU` delegates are created once in `createEngineWithFallback` and reused for every generation until `unloadModel` (log `DELEGATE DESTROY` only on unload/failure, never per prompt)
- **No recreation:** `Conversation` is lightweight session; delegate is *not* recreated between prompts (`tokenStream` isolated throwaways use separate `Conversation`, chat reuses resident `conversation`)
- **Failure detection before inference:** `HardwareBackendProbe` + `BackendSelector` probe once, `ModelResourceGuard` pre-checks RAM before `Engine.initialize`; delegate init failure is caught, logged with `fallback reason` (`EngineErrorMapper` stage `initialize` + `suggestion`), retried once, then next backend in `orderedCandidates` (NPU→GPU→CPU)
- **No silent fallback:** every fallback logs `Backend fallback NPU→GPU: reason` via `StartupProfiler` + `EnginePerformanceMonitor`; `EngineCrashGuard` auto-disables backend after 3 failures
- **State preserved:** `activeBackendInfo`, `loadedModel.backend`, `conversation` KV cache, `executionContext` survive across prompts and conversations until explicit unload; `resetChat` waits bounded for `generationActive` before recreating `Conversation`

### First Token & Consecutive Prompt Latency

- **TTFT profiled:** `PREFILL START` → `PREFILL END / DECODER START — TTFT=Xms` via `EnginePerformanceMonitor.Stages.FIRST_TOKEN` (prompt length, tokens/sec)
- **Preprocessing:** `ChatTemplateRenderer` hash-gated behind `debugTokenLogging`, `PrefixCache` SHA-256 `MessageDigest` thread-local, `StopSequenceTracker` holdback avoids re-scan
- **Tokenizer:** `SentencePieceTokenizer` LRU, `BufferPool` (`StringBuilder` 8, `ByteArray` 8, `CharArray` 4, `AtomicInteger` for race-free pooling)
- **Runtime sync:** `generationActive` + `conversation.cancelProcess` off native callback thread; timeout timers start *after* `sendMessageAsync` (not during init)
- **Streaming:** tokens emitted as soon as available via `callbackFlow`, `awaitClose` never blocks main, `delay(16ms)` throttling only for UI
- **Consecutive prompts:** `ensureConversationForHistory` hit → only new tokens prefilled; `BufferPool` reused; no delegate/graph rebuild

### Monitoring Acceleration

Check the Developer screen → Hardware Info for:
- `backend`: Current backend type (`NPU` / `GPU` / `CPU`)
- `vendor`: Accelerator vendor (Qualcomm, ARM, etc.)
- `accelerator`: Accelerator block (Adreno, Mali, Hexagon HTP)
- `delegate`: Runtime delegate label (XNNPACK, LiteRT GPU, LiteRT Delegate)
- `gpuFree` / `gpuTotal`: GPU memory (when on GPU)
- `recoveryCount`: Corruption-recovery cycles

### Corruption Recovery

When the engine detects corrupted generation output (memory-corruption signature), it performs a three-level recovery:
1. **Level 1**: Re-arms the model on the same backend
2. **Level 2**: Reloads the model on CPU
3. **Level 3**: Reports the error to the user

Track recovery count in logs: `recoveryCount=N`, `backend=gpu` -> `backend=cpu`.

---

## Context Length

### Impact on Performance

| Context Length | RAM Impact | Speed Impact | Use Case |
|---|---|---|---|
| 512 | Minimal | Fastest | Short Q&A, commands, embeddings |
| 1024 | Low | Fast | General chat |
| 2048 | Moderate | Good | Most conversations (Qwen3-0.6B real window) |
| 4096 | High | Slower | Long context, reasoning (Qwen2.5-1.5B real window) |
| 8192+ | Very high | Slow | Document analysis (Gemma 4) |

### Optimizing Context Usage

1. **Respect the container-detected context** - the engine trusts `LlmMetadata`, not the catalog claim
2. **Use conversation summaries** to compress history
3. **Clear old conversations** to free KV cache memory
4. **Avoid re-loading** models with different context lengths without unloading first

### Context Overflow Behavior

When a conversation fills the KV cache, LiteRT-LM raises
`INVALID_ARGUMENT: Input token ids are too long`. The engine responds
automatically: it trims the oldest turns (keeping system prompt + recent
messages) and reseeds the conversation. Generation continues seamlessly.

---

## Battery Considerations

### Power Consumption by Feature

| Feature | Power Impact | Notes |
|---|---|---|
| Local inference (CPU) | Medium | Sustained CPU usage |
| Local inference (GPU) | Medium-High | GPU is efficient per token |
| Voice assistant (listening) | Low-Medium | Continuous mic + wake word model |
| Voice assistant (TTS) | Medium | Brief bursts during response |
| Cloud API calls | Low | Short network bursts |
| Memory embedding | Low | Periodic background work |

### Battery Saver Mode

The voice assistant has a battery saver mode (Settings -> Voice Assistant):
- Runs wake word detection on a single thread
- Disables continuous conversation mode
- Reduces TTS quality/speed slightly
- Extends battery life by ~30% during voice usage

### Thermal Throttling

Prolonged generation can cause thermal throttling, reducing clock speeds:
- Flagship devices: throttle after ~10-15 minutes of continuous generation
- Mid-range devices: throttle after ~5-10 minutes
- The engine does not currently detect or respond to thermal states
- Planned: thermal-aware generation pacing

---

## Optimization Tips

### For Users

1. **Prefer GPU acceleration** (default) - check the backend in Developer Diagnostics
2. **Choose Mixed-Int4 containers** (e.g. Qwen3 0.6B) for the best quality/speed balance
3. **Keep context at the detected window** - smaller is faster
4. **Close unused apps** before loading large models
5. **Enable battery saver** for voice assistant on the go
6. **Use the benchmark tool** to find your device's optimal settings

### For Developers

1. Profile with Android Studio's CPU/Memory profilers
2. Use `EnginePerformanceMonitor` to measure pipeline stage timings (model init, container read, conversation create, first-token latency)
3. Check engine debug info (logcat tag `AndroLLM-Engine`) for engine stats
4. Monitor `CosineVectorIndex` memory (grows with each memory)
5. Test on real devices, not just emulators (emulators lack a working OpenCL GPU delegate)
6. Use `EngineDiagnostics` + `EngineDiagnosticsCollector` for the developer diagnostics panel
7. Check `EngineCrashGuard.crashSummary()` for crash telemetry
8. Use `ThreadManager.threadingProfile()` to verify device-class-adaptive settings

---

## Benchmarking

To benchmark your device:

1. Enable Developer Mode: Settings -> Developer Options
2. Open Developer screen
3. Load a model
4. Tap "Benchmark"
5. Review results showing:
   - Tokens per second
   - Time to first token
   - Memory usage
   - Backend type (CPU/GPU/NPU)

Compare results across different models and quantizations to find the optimal configuration for your device.

### Automated Benchmark Suite (`engine/src/test/java/io/androllm/engine/benchmark/EngineBenchmark.kt`)

Covers cold/warm start, first prompt latency, consecutive prompt latency, large prompt handling (4K/8K), 7B/8B loading, GPU/NPU/CPU execution, memory consumption, backend switching, long conversations (20 turns), multiple consecutive generations (5× no delegate recreation). Preserves APIs, runs on-device via `connectedAndroidTest`.

```bash
./gradlew :engine:connectedAndroidTest -Pbenchmark
# or unit benchmark
./gradlew :engine:test --tests "*EngineBenchmark*"
```

Results are logged via `StartupProfiler` + `EnginePerformanceMonitor` for `Tokens/sec`, `TTFT`, `Memory allocation`, `KV cache init`, `Conversation reuse`.