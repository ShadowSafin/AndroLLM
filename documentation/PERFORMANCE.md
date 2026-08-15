# Performance Guide

Performance considerations for running AndroLLM on Android devices.

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

### Detecting Low RAM

The engine's `MemoryEstimator` predicts RAM requirements from container metadata before loading, and `ModelResourceGuard` **refuses loads** that exceed the device's available RAM. The Model Catalog shows `minRamGb` and `recommendedRamGb` for each model.

---

## GPU Acceleration (LiteRT GPU Delegate)

### When GPU Is Used

GPU acceleration is automatic when:
1. `EngineConfig.backend == GPU` (the default)
2. The device's OpenCL GPU delegate initializes successfully
3. The model supports GPU execution (all catalog models carry `supportedBackends: [CPU, GPU]`)

### When CPU Fallback Occurs

The engine falls back to CPU when:
1. GPU delegate initialization fails (old GPUs, missing drivers)
2. GPU memory is insufficient for the model
3. The GPU delegate crashes during generation (recovery path re-arms the model on CPU)

### Monitoring GPU Memory

Check the Developer screen -> Hardware Info for:
- `gpuFree`: Available GPU memory
- `gpuTotal`: Total GPU memory
- `recoveryCount`: Number of corruption-recovery cycles performed
- `backend`: Current backend type (`GPU` or `CPU`)

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
2. Use `StageTracer` to measure pipeline stage timings
3. Check engine debug info (logcat tag `AndroLLM-Engine`) for engine stats
4. Monitor `CosineVectorIndex` memory (grows with each memory)
5. Test on real devices, not just emulators (emulators lack a working OpenCL GPU delegate)

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
   - Backend type (CPU/GPU)

Compare results across different models and quantizations to find the optimal configuration for your device.