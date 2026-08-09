# Performance Guide

Performance considerations for running AndroLLM on Android devices.

---

## Token Generation Speed

Token generation speed depends on multiple factors working together:

### Primary Factors

| Factor | Impact | How to Optimize |
|---|---|---|
| **Backend** | Vulkan is 5–20× faster than CPU on capable devices | Ensure Vulkan is active (check Developer Diagnostics) |
| **Quantization** | Lower quant = faster but less accurate | Q4_K_M is the sweet spot; Q8_0 for quality, IQ1 for speed |
| **Context length** | Longer context = slower (linear KV cache growth) | Use the smallest context that works for your use case |
| **Model size** | More parameters = slower | Match model to device capability |
| **GPU memory** | Out-of-GPU-memory causes CPU fallback | Monitor `gpuFree` in diagnostics; close other GPU apps |

### Expected Performance Ranges

These are **not benchmarks** — they are approximate ranges based on architecture:

| Device Class | Vulkan (tokens/sec) | CPU (tokens/sec) |
|---|---|---|
| Flagship (Snapdragon 8 Gen 2/3) | 15–40 | 5–15 |
| Mid-range (Snapdragon 7系, Dimensity 8系) | 8–20 | 3–8 |
| Entry (Snapdragon 6系, older chips) | N/A (CPU only) | 2–5 |

Actual numbers vary significantly by model architecture, quantization, and Android version. Use the built-in **benchmark** tool (Developer screen) to measure your device.

---

## Model Loading

### Cold Start (First Load)

Model loading involves:
1. Memory mapping the GGUF file (fast, OS-level)
2. Allocating tensors in RAM
3. Optionally copying tensors to GPU memory (Vulkan)
4. Compiling Vulkan shaders (warm-up)

Typical load times:

| Model Size | CPU Load | Vulkan Load |
|---|---|---|
| 1.5B (Q4) | ~3–5 sec | ~4–6 sec (shader compile adds overhead) |
| 3B (Q4) | ~5–8 sec | ~6–10 sec |
| 7B (Q4) | ~10–20 sec | ~8–15 sec |
| 7B (Q8) | ~15–30 sec | ~12–20 sec |

Subsequent loads are faster because the GPU shaders remain compiled.

### Warm-Up

Call `nativeWarmUp()` after loading a model on Vulkan. This pre-compiles compute shaders so the first generation doesn't stall. The engine does this automatically.

---

## RAM Usage

### Memory Breakdown

```
Total RAM = Model Weights + KV Cache + Overhead
```

| Component | Formula | Example (7B Q4, 4096 ctx) |
|---|---|---|
| Model weights | `parameters × bytes_per_token` | ~4.5 GB |
| KV cache | `2 × n_layers × n_heads × head_dim × ctx_len × 2 bytes` | ~0.5 GB |
| CUDA/Vulkan buffers | Implementation-dependent | ~0.3 GB |
| App + OS overhead | Fixed | ~0.5 GB |
| **Total** | | **~5.8 GB** |

### Managing RAM

1. **Close other apps** before loading large models
2. **Use smaller context** — 2048 tokens uses half the KV cache of 4096
3. **Unload models** when not in use (Models screen → Unload)
4. **Avoid loading multiple models** simultaneously
5. **Restart the app** periodically to clear fragmented allocations

### Detecting Low RAM

The engine's `MemoryEstimator` predicts RAM requirements from model metadata before loading. The Model Catalog shows `minRamGb` and `recommendedRamGb` for each model.

---

## GPU Acceleration (Vulkan)

### When Vulkan Is Used

Vulkan acceleration is automatic when:
1. The device reports Vulkan support (`nativeVulkanAvailable() == true`)
2. The model has layers that can be offloaded (`n_gpu_layers > 0`)
3. The Vulkan backend initializes successfully

### When CPU Fallback Occurs

CPU fallback happens when:
1. Device has no Vulkan support (rare on Android 9+)
2. Vulkan initialization fails during warm-up
3. GPU memory is insufficient for the model
4. `VK_ERROR_DEVICE_LOST` occurs and recovery fails

### Monitoring GPU Memory

Check the Developer screen → Hardware Info for:
- `gpuFree`: Available VRAM
- `gpuTotal`: Total VRAM
- `gpuLayersUsed`: Number of model layers offloaded to GPU
- `backend`: Current backend type (`VULKAN` or `CPU`)

### Vulkan Device-Lost Recovery

The engine has a three-level recovery system:
1. **Level 1**: Recreate context on same GPU backend
2. **Level 2**: Reload context on CPU
3. **Level 3**: Report error to user

Track recovery count in logs: `recoveryCount=N`, `devLostRecovered=M`.

---

## Context Length

### Impact on Performance

| Context Length | RAM Impact | Speed Impact | Use Case |
|---|---|---|---|
| 512 | Minimal | Fastest | Short Q&A, commands |
| 1024 | Low | Fast | General chat |
| 2048 | Moderate | Good | Most conversations |
| 4096 | High | Slower | Long context, reasoning |
| 8192+ | Very high | Slow | Document analysis |

### Optimizing Context Usage

1. **Start with 2048** and increase only if needed
2. **Use conversation summaries** (when available) to compress history
3. **Clear old conversations** to free KV cache memory
4. **Avoid re-loading** models with different context lengths without unloading first

### Context Shift Behavior

When the conversation approaches the context limit (`pos_check >= nCtx - 4`), older tokens after the system prompt are discarded and remaining tokens are shifted left in-place. This preserves the system prompt and recent conversation while dropping older turns.

---

## Battery Considerations

### Power Consumption by Feature

| Feature | Power Impact | Notes |
|---|---|---|
| Local inference (CPU) | Medium | Sustained CPU usage |
| Local inference (Vulkan) | Medium-High | GPU is efficient per token |
| Voice assistant (listening) | Low-Medium | Continuous mic + wake word model |
| Voice assistant (TTS) | Medium | Brief bursts during response |
| Cloud API calls | Low | Short network bursts |
| Memory embedding | Low | Periodic background work |

### Battery Saver Mode

The voice assistant has a battery saver mode (Settings → Voice Assistant):
- Runs wake word detection on a single thread
- Disables continuous conversation mode
- Reduces TTS quality/speed slightly
- Extends battery life by ~30% during voice usage

### Thermal Throttling

Prolonged generation can cause thermal throttling, reducing clock speeds:
- Flagship devices: throttle after ~10–15 minutes of continuous generation
- Mid-range devices: throttle after ~5–10 minutes
- The engine does not currently detect or respond to thermal states
- 🚧 Planned: thermal-aware generation pacing

---

## Optimization Tips

### For Users

1. **Use Vulkan-capable devices** for best performance
2. **Choose Q4_K_M or Q5_K_M** quantizations for the best quality/speed balance
3. **Keep context length at 2048** unless you need more
4. **Close unused apps** before loading large models
5. **Enable battery saver** for voice assistant on the go
6. **Use the benchmark tool** to find your device's optimal settings

### For Developers

1. Profile with Android Studio's CPU/Memory profilers
2. Use `StageTracer` to measure pipeline stage timings
3. Check `nativeGetDebugInfo()` for detailed engine stats
4. Monitor `CosineVectorIndex` memory (grows with each memory)
5. Test on real devices, not just emulators (emulators lack Vulkan)

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
   - Backend type (CPU/Vulkan)

Compare results across different models and quantizations to find the optimal configuration for your device.
