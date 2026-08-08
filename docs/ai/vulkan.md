# Vulkan GPU Acceleration Guide

Deep dive into the Vulkan GPU backend for local LLM inference.

---

## Overview

AndroLLM uses the **ggml Vulkan backend** (built into vendored llama.cpp) to accelerate model inference on Android GPUs. When available, Vulkan offloads matrix operations to the GPU, providing significant speed improvements over CPU-only inference.

**Important:** Vulkan support varies by device. Not all Android devices have compatible GPUs or drivers. The engine always falls back to CPU if Vulkan is unavailable or fails.

---

## Vulkan Architecture

### Build-Time vs Runtime

| Stage | What Happens | Where |
|---|---|---|
| **Build time** | GLSL compute shaders are compiled to SPIR-V using `glslc` from the host Vulkan SDK | Host machine (your development PC) |
| **Build time** | SPIR-V binaries are embedded into `libandrollm_llama.so` | CMake build process |
| **Runtime** | Vulkan instance and physical device are enumerated on the device | Device at app startup |
| **Runtime** | GPU memory is queried; layer offloading decision is made | Device at model load time |
| **Runtime** | If Vulkan unavailable → `n_gpu_layers = 0` (CPU fallback) | Device at model load time |

### Shader Compilation

The CMake configuration locates `glslc` (the GLSL compiler) from the host Vulkan SDK:

```cmake
find_program(ANDROLLM_GLSLC_EXECUTABLE
    NAMES glslc glslc.exe
    HINTS "$ENV{VULKAN_SDK}/Bin" "$ENV{VULKAN_SDK}/bin"
    NO_CMAKE_FIND_ROOT_PATH)
```

A host toolchain is generated to ensure the shader generator uses the host compiler (not the Android NDK cross-compiler):

```cmake
if(CMAKE_CROSSCOMPILING)
    find_program(ANDROLLM_HOST_C_COMPILER   NAMES gcc cc clang cl)
    find_program(ANDROLLM_HOST_CXX_COMPILER NAMES g++ c++ clang++ cl)
    # Generate toolchain for vulkan-shaders-gen ExternalProject
endif()
```

---

## Vulkan Detection

Detection happens in two phases:

### Phase 1: Build-Time Check

`native_api.cpp` calls `ggml_backend_vk_get_device_count()` and `llama_supports_gpu_offload()`:

```cpp
#ifdef GGML_USE_VULKAN
uint32_t deviceCount = ggml_backend_vk_get_device_count();
bool supported = llama_supports_gpu_offload();
#endif
```

### Phase 2: Runtime Check

```kotlin
// LlamaCppEngine.initialize()
private var vulkanSupported: Boolean = false
private var vulkanFallbackReason: String? = null

override suspend fun initialize(config: EngineConfig): Result<Unit> {
    vulkanSupported = LlamaJniBridge.nativeVulkanAvailable()
    if (vulkanSupported) {
        config.useVulkan = true  // Force Vulkan even if user prefers CPU
    }
    // ... create native engine handle
}
```

```kotlin
// JNI bridge
external fun nativeVulkanAvailable(): Boolean
```

### VulkanInfo Structure (returned from native code)

```cpp
struct VulkanInfo {
    bool ok;                              // Vulkan available
    std::string name;                     // GPU name (e.g., "Adreno (TM) 750")
    std::string driver;                   // Driver version
    std::string apiVersion;               // Vulkan API version
    size_t freeBytes;                     // Available GPU memory
    size_t totalBytes;                    // Total GPU memory
    std::string reason;                   // Why Vulkan failed (if ok=false)
};
```

---

## Layer Offloading

### Decision Logic

```cpp
int selectedGpuLayers = gpuLayers;  // -1 means "all layers" from config
if (!vk.ok && gpuLayers != 0) {
    params.n_gpu_layers = 0;         // Force CPU fallback
    eng->backendReason = "Vulkan unavailable: " + vk.reason;
}
// During context creation:
eng->gpuLayersUsed = selectedGpuLayers < 0 ? nL : std::min(selectedGpuLayers, nL);
```

| `n_gpu_layers` Value | Meaning |
|---|---|
| `-1` | Offload ALL layers to GPU (maximum acceleration) |
| `0` | No GPU offloading (pure CPU) |
| `1` to `n_layers-1` | Offload specified number of layers |

### GPU Memory Tracking

```cpp
eng->gpuMemoryAllocatedBytes = mSize * eng->gpuLayersUsed / totalLayers + cSize;
#ifdef GGML_USE_VULKAN
ggml_backend_vk_get_device_memory(0, &free, &total);
eng->gpuMemoryFreeBytes = free;
eng->gpuMemoryTotalBytes = total;
#endif
```

Reported to Kotlin as part of `MemoryStats` JSON.

---

## GPU-vs-CPU Correctness Validation

After loading a model with GPU offloading, the engine runs a validation suite to ensure Vulkan produces identical results to CPU:

```cpp
// nativeLoadModel validation phase
1. Greedy test (temp=0) on 5 prompts
   → Compare every sampled token between GPU and CPU
   
2. Long-context test (forces KV shifts)
   → Ensures context shift works correctly on GPU
   
3. Sampling tests (standard, typical_p, mirostat)
   → Verify different sampling strategies produce same output
```

Validation result is reported as: `"passed"` / `"failed"` / `"skipped"` in `EngineDebugInfo.vulkanValidationStatus`.

---

## Corruption Recovery System

The `decode_safe()` wrapper catches exceptions during GPU decoding and applies an escalation ladder:

### Recovery Levels

| Level | Action | Trigger |
|---|---|---|
| **0** | Normal operation | No error |
| **1** | Recreate context on same GPU backend | `VK_ERROR_DEVICE_LOST`, NaN/INF logits, invalid tokens |
| **2** | Reload context on CPU | GPU recreation fails |
| **3** | Report error to user | CPU reload also fails |

### Implementation

```cpp
void decode_safe(LlamaEngine* eng, const std::vector<common_token>& prompt, ...) {
    try {
        // Normal Vulkan decode
        llama_decode(eng->ctxOwner.get(), batch);
    } catch (const std::system_error& e) {
        if (strstr(e.what(), "device lost")) {
            eng->vulkanDeviceLost = true;
            eng->vulkanDeviceLostRecoveries++;
            // Fall through to context recreation
        }
    } catch (...) {
        eng->recoveryCount++;
        // NaN/INF detected — escalate
    }
    
    // Check for corruption
    if (hasNaNorInf(logits)) {
        eng->recoveryCount++;
        // Recreate context
    }
}
```

### Logging

After every generation, diagnostics are logged:

```
[VulkanDiag] backend=VULKAN ctxCreate=234ms cleanup=12ms decodeCalls=47 decodeAvg=23ms 
             gpuFree=1.2GB gpuTotal=8.0GB recovery=0 devLostRecovered=0
```

---

## Common Vulkan Issues

### Device Lost

**Symptoms:**
- Generation aborts mid-token
- Log shows `VK_ERROR_DEVICE_LOST`
- `devLostRecovered` counter increments

**Causes:**
- GPU driver crash
- Thermal throttling (long generation sessions)
- Insufficient VRAM (model too large for GPU)
- Competing GPU applications

**Resolution:**
- The engine automatically recreates the context
- If recovery fails, falls back to CPU transparently
- Reduce context length or model size if recurring
- Close other GPU-intensive apps

### Shader Compilation Failure

**Symptoms:**
- App crashes on model load
- Log shows GLSL compilation error

**Causes:**
- Outdated GPU driver
- GPU doesn't support required Vulkan extensions
- Malformed shader (very rare — upstream llama.cpp shaders are well-tested)

**Resolution:**
- Update device OS/driver
- The engine will fall back to CPU if shader compilation fails

### Insufficient GPU Memory

**Symptoms:**
- Only some layers offloaded (`gpuLayersUsed < n_layers`)
- Log shows low `gpuFree`

**Causes:**
- Large model with limited VRAM
- Other apps using GPU memory

**Resolution:**
- Reduce `n_gpu_layers` (e.g., set to half the total layers)
- Use a smaller model
- Close other GPU applications

---

## Diagnostics

Access Vulkan diagnostics from the Developer screen:

1. Enable Developer Mode: Settings → Developer Options
2. Navigate to Developer screen
3. View Hardware Info section:
   - `backend`: Current backend type
   - `gpuFree`: Available VRAM
   - `gpuTotal`: Total VRAM
   - `gpuLayersUsed`: Layers on GPU
   - `n_layers`: Total model layers
   - `vulkanValidationStatus`: passed/failed/skipped
   - `recoveryCount`: Number of corruption recoveries
   - `devLostRecovered`: Number of device-lost recoveries

---

## Supported Devices

Vulkan support on Android depends on the GPU vendor and driver quality:

| GPU Vendor | Vulkan Support | Notes |
|---|---|---|
| **Qualcomm Adreno** (Snapdragon 8Gen series) | ✅ Excellent | Best performance; KleidiAI microkernels enabled |
| **ARM Mali** (Galaxy S series) | ✅ Good | Varying driver quality by manufacturer |
| **PowerVR** (older devices) | ⚠️ Limited | Some models lack full Vulkan support |
| **Mali** (Dimensity) | ✅ Good | Generally well-supported |
| **IMGTE PowerVR** | ❌ Poor | Many PowerVR devices lack Vulkan or have buggy drivers |
| **Intel HD/UHD** (emulators) | ⚠️ Variable | Emulator GPU emulation varies |

**Note:** Vulkan is required at **build time** (for shader compilation) but the resulting APK can run on devices without Vulkan (with CPU fallback).

---

## Performance Tips

1. **Use flagship devices** for best Vulkan performance (Snapdragon 8 Gen 2/3)
2. **Keep the device cool** — thermal throttling reduces GPU clock speeds
3. **Close other GPU apps** before loading large models
4. **Monitor `gpuFree`** — if below 500 MB, consider reducing model size or context length
5. **Enable battery saver** during voice assistant use to reduce thermal load
6. **Benchmark different quantizations** — Q5_K_M often provides the best quality/speed tradeoff on GPU

---

## Planned Vulkan Features

| Feature | Status | Notes |
|---|---|---|
| GPU memory usage visualization in UI | 🚧 Planned | Real-time VRAM usage graph |
| Automatic optimal layer count selection | 🚧 Planned | ML-based heuristic for best layers/offload ratio |
| Vulkan validation layer support in debug builds | 🚧 Planned | Enhanced debugging output |
| Multi-GPU support (if future devices support) | 🔮 Future | Unlikely on mobile but planned in architecture |
