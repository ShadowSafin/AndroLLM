# LLAMA_CPP Quick Reference

Quick reference for the llama.cpp integration.

---

## What's Included

AndroLLM vendors a **stock (unpatched) copy of llama.cpp** at `engine/src/main/cpp/llama.cpp/`. It is built into `libandrollm_llama.so` alongside a custom JNI bridge.

---

## Key Capabilities

| Feature | Status |
|---|---|
| GGUF model loading | ✅ |
| CPU inference (ARM64 NEON) | ✅ |
| Vulkan GPU offloading | ✅ |
| Multi-turn chat (KV cache) | ✅ |
| Streaming token output | ✅ |
| JSON/constrained decoding | ✅ |
| Embedding models | ✅ (separate handle) |
| Benchmarking | ✅ |
| Corruption recovery | ✅ |

---

## Supported Architectures

137 architectures including: llama, gemma2, qwen2, deepseek, mistral, phi3, starcoder2, command-r, internlm2, and more.

Full list: [`core/models/src/main/java/io/androllm/core/models/catalog/SupportedArchitectures.kt`](core/models/src/main/java/io/androllm/core/models/catalog/SupportedArchitectures.kt)

---

## Generation Parameters

| Parameter | Default | Range | Description |
|---|---|---|---|
| temperature | 0.8 | 0.0–2.0 | Sampling randomness |
| top_k | 40 | 1–100 | Top-k sampling |
| top_p | 0.9 | 0.0–1.0 | Nucleus sampling |
| min_p | 0.0 | 0.0–1.0 | Min-p sampling |
| typical_p | 1.0 | 0.0–2.0 | Locally typical sampling |
| seed | -1 | -1 to 2^31-1 | Reproducibility (-1 = random) |

Adjusted per-conversation via the Model Parameter Sheet in chat.

---

## Context Management

- Default context: 2048 tokens
- Maximum: depends on model metadata (`general.context_length`)
- Automatic context shift when near limit (preserves system prompt)
- Manual reset: Chat drawer → New conversation

---

## See Also

- [llama.cpp Integration](ai/llama-cpp.md) — Full technical deep dive
- [GGUF Format](ai/gguf.md) — Model format specification
