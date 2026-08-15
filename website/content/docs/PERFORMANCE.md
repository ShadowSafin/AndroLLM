# Performance Guide

Performance considerations for running AndroLLM on Android devices.

---

## Token Generation Speed

Token generation speed depends on multiple factors working together:

### Primary Factors

| Factor | Impact | How to Optimize |
|---|---|---|
| **Backend** | GPU (OpenCL delegate) is typically faster than CPU on capable devices | Check the active backend in Developer Diagnostics; keep GPU enabled |
| **Quantization** | Pre-quantized containers — mixed int4 is fastest, fp16 components are slower | Match model to device capability |
| **Context length** | Longer context = slower (linear KV growth in the runtime) | Use the smallest context that works; respect metadata limits |
| **Model size** | More parameters = slower | Match model to device capability (catalog: ~475 MB–1.3 GB files, 2–4 GB RAM guidance) |
| **GPU memory** | Out-of-GPU-memory triggers CPU fallback | Monitor `gpuFree` in diagnostics; close other GPU apps |

### Expected Performance Ranges

Exact numbers vary by device, model, and Android version — the LiteRT-LM
runtime's real throughput should be measured with the built-in **benchmark**
tool (Developer screen). As a rule of thumb: flagships with the GPU delegate
outperform CPU-only sessions by a comfortable margin on generation-heavy
models, while small models (0.6B–1.5B) are comfortable on CPU (XNNPACK) alone.

---

## Model Loading

### Cold Start (First Load)

Model loading involves:
1. Validating the `.litertlm` container (`LiteRtValidator` + SHA-256)
2. Reading container metadata (`ModelInspector` / `ContainerMetadataReader`)
3. Loading weights and tokenizer through the LiteRT-LM runtime
4. Initializing the chosen backend (GPU delegate init when enabled)

Load times depend on file size (~475 MB–1.3 GB), device storage speed, and
backend. GPU delegate initialization adds a small one-time overhead on first
load; subsequent loads reuse the initialized runtime.

### Warm-Up

The engine initializes the backend at load time so the first generation
doesn't stall on delegate setup. This happens automatically.

---

## RAM Usage

### Memory Breakdown

```
Total RAM = Model Weights + Runtime Working Set + KV Cache + App Overhead
```

| Component | Notes |
|---|---|
| Model weights | Pre-quantized container weights (int4-dominant for small models) |
| Runtime working set | LiteRT-LM session state, delegate buffers |
| KV cache | Grows with context length — metadata context (4096 for Qwen2.5-1.5B, 2048 for Qwen3-0.6B) bounds it |
| App + OS overhead | Fixed |

### Managing RAM

1. **Close other apps** before loading larger models
2. **Use smaller context** — respects the model's metadata limit
3. **Unload models** when not in use (Models screen → Unload)
4. **Avoid loading multiple models** simultaneously
5. **Restart the app** periodically to clear fragmented allocations

### Detecting Low RAM

The engine's `MemoryEstimator` predicts RAM requirements from container
metadata before loading. The Model Catalog shows `minRamGb` and
`recommendedRamGb` for each model (catalog guidance: 2–4 GB device RAM).

---

## GPU Acceleration

### When the GPU Delegate Is Used

The OpenCL-based LiteRT GPU delegate is used when:
1. The device supports OpenCL (common on Android 9+ devices)
2. GPU acceleration is enabled in settings
3. The delegate initializes successfully

### When CPU Fallback Occurs

Automatic fallback to CPU (XNNPACK) happens when:
1. The device has no working OpenCL driver
2. GPU delegate initialization fails
3. GPU memory is insufficient for the model
4. A GPU session error occurs and recovery fails

Fallback is seamless — the session continues on CPU without user action.

### Monitoring GPU Memory

Check the Developer screen → Hardware Info for:
- `gpuFree`: Available GPU memory
- `gpuTotal`: Total GPU memory
- `recoveryCount`: Number of GPU→CPU fallback/recovery events
- `backend`: Current backend type (`GPU` or `CPU`)

### Recovery

The engine tracks corruption recovery in `MemoryStats`. A rising
`recoveryCount` with low `gpuFree` usually means the GPU cannot fit the working
set — close other GPU-heavy apps or use a smaller model.

`BackendType` legacy values (`QUALCOMM_QNN`, `LLAMA_CPP_VULKAN`, `ONNX_RUNTIME`,
`VULKAN`) are compat-only — never produced, kept for persisted-state
serializers and older UI code.

---

## Context Length

### Impact on Performance

| Context Length | RAM Impact | Speed Impact | Use Case |
|---|---|---|---|
| 512 | Minimal | Fastest | Short Q&A, commands |
| 1024 | Low | Fast | General chat |
| 2048 | Moderate | Good | Most conversations (Qwen3-0.6B max) |
| 4096 | High | Slower | Long context (Qwen2.5-1.5B max) |

### Optimizing Context Usage

1. **Respect the metadata context limit** — prompting past it fails with
   `Input token ids are too long`
2. **Use conversation summaries** to compress history
3. **Clear old conversations** to free KV memory
4. **Budget tool advertisement** — `ToolPromptBuilder` caps its advertisement
   at 4500 characters for small Qwen families so tools don't crowd the
   conversation

---

## Battery Considerations

### Power Consumption by Feature

| Feature | Power Impact | Notes |
|---|---|---|
| Local inference (CPU) | Medium | Sustained CPU usage (XNNPACK) |
| Local inference (GPU) | Medium | Delegate is efficient per token |
| Voice assistant (listening) | Low-Medium | Continuous mic + wake word model |
| Voice assistant (TTS) | Medium | Brief bursts during response |
| Cloud API calls | Low | Short network bursts |
| Memory embedding | Low | Periodic background work |

### Battery Saver Mode

The voice assistant has a battery saver mode (Settings → Voice Assistant):
- Runs wake word detection on a single thread
- Disables continuous conversation mode
- Reduces TTS quality/speed slightly
- Extends battery life during voice usage

### Thermal Throttling

Prolonged generation can cause thermal throttling, reducing clock speeds:
- Flagship devices: throttle after ~10–15 minutes of continuous generation
- Mid-range devices: throttle after ~5–10 minutes
- 🚧 Planned: thermal-aware generation pacing

---

## Optimization Tips

### For Users

1. **Enable GPU acceleration** on OpenCL-capable devices
2. **Choose small models** (0.6B–1.5B) for daily driving — the catalog is
   tuned for 2–4 GB RAM devices
3. **Keep context at or below the metadata limit**
4. **Close unused apps** before loading larger models
5. **Enable battery saver** for voice assistant on the go
6. **Use the benchmark tool** to find your device's optimal settings

### For Developers

1. Profile with Android Studio's CPU/Memory profilers
2. Use `StageTracer` to measure pipeline stage timings
3. Check `EngineDebugInfo` / `EngineStats` for detailed engine stats
4. Monitor `CosineVectorIndex` memory (grows with each memory)
5. Test on real devices, not just emulators (GPU delegate behavior is device-specific)

---

## Benchmarking

To benchmark your device:

1. Enable Developer Mode: Settings → Developer Options
2. Open Developer screen
3. Load a model
4. Tap "Benchmark"
5. Review results showing:
   - Tokens per second
   - Time to first token
   - Memory usage
   - Backend type (CPU/GPU)

Compare results across different models to find the optimal configuration for
your device.