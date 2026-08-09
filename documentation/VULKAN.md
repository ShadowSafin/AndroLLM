# Vulkan Quick Reference

Quick reference for Vulkan GPU acceleration.

---

## How It Works

When a model is loaded, AndroLLM automatically detects Vulkan support:

1. **Build time**: GLSL compute shaders are compiled to SPIR-V and embedded in the native library
2. **Runtime**: Device Vulkan capabilities are checked; if available, model layers are offloaded to GPU
3. **Fallback**: If Vulkan fails at any point, inference continues on CPU transparently

---

## Checking Vulkan Status

Go to **Settings → Developer Options → Hardware Info**:

| Field | Meaning |
|---|---|
| `backend` | `VULKAN` or `CPU` — current backend in use |
| `gpuFree` | Available VRAM in MB |
| `gpuTotal` | Total VRAM in MB |
| `gpuLayersUsed` | Number of model layers on GPU |
| `n_layers` | Total model layers |
| `vulkanValidationStatus` | `passed` / `failed` / `skipped` |

---

## Device Compatibility

| GPU Vendor | Support Level |
|---|---|
| Qualcomm Adreno (Snapdragon 8 Gen 2/3) | Excellent — KleidiAI microkernels enabled |
| ARM Mali (Galaxy S series) | Good |
| MediaTek Dimensity | Good |
| Older PowerVR | Limited or none |

---

## Common Issues

| Symptom | Cause | Fix |
|---|---|---|
| App uses CPU despite capable GPU | Driver issue | Update OS; check `gpuLayersUsed` |
| Generation crashes mid-stream | Device lost | Auto-recovery; reduce context length |
| Very slow first generation | Shader compilation | Normal — warm-up completes in ~3 sec |
| GPU memory exhausted | Model too large | Use smaller model or reduce context |

---

## See Also

- [Vulkan Architecture](ai/vulkan.md) — Full technical deep dive
