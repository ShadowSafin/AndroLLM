# FAQ — AndroLLM

Frequently asked questions about AndroLLM.

---

## General

### What is AndroLLM?
AndroLLM is a production-grade Android application that runs large language models locally on your device. It supports GGUF models through llama.cpp with optional Vulkan GPU acceleration, connects to cloud AI providers via LiteLLM, maintains persistent memory across conversations, and includes a fully offline voice assistant.

### Does AndroLLM work offline?
Yes. All core functionality works offline:
- Local GGUF model inference (CPU or Vulkan GPU)
- Voice assistant (wake word, ASR, TTS)
- Persistent memory (embeddings and retrieval)
- Conversation history

Cloud features (provider chat, cloud embeddings) require an internet connection but are entirely optional.

### Does it require an internet connection?
Not for local use. Internet is only required for:
- Downloading models from HuggingFace
- Using cloud AI providers
- Firebase authentication (optional)
- Catalog refresh (optional; bundled catalog works offline)

### Does it require an API key?
No. Local model inference requires no API key. Cloud provider features require you to supply your own API key, which is encrypted and stored locally.

### Is my data private?
Yes. When using local mode:
- All model inference runs on your device
- No data is transmitted over the network
- Conversation history and memories are stored in your app's private sandbox
- API keys are encrypted with Android Keystore AES-256/GCM

See [PRIVACY.md](../PRIVACY.md) for full details.

---

## Models

### What model format is supported?
**GGUF** is the primary supported format. The app also recognizes these formats in the catalog metadata but only runs GGUF files locally:

| Format | Local Inference | Catalog Display |
|---|---|---|
| GGUF | ✅ Yes | ✅ Yes |
| GGML | ⚠️ Legacy (not recommended) | ✅ Yes |
| SAFETENSORS | ❌ No | ✅ Yes (informational) |
| PYTORCH | ❌ No | ✅ Yes (informational) |
| ONNX | ❌ No (voice models only) | ✅ Yes (informational) |
| QNN | ❌ No | ✅ Yes (informational) |

### What is GGUF?
GGUF (GPT-Generated Unified Format) is a binary model format created by the llama.cpp project. It stores model weights, tokenizer vocabulary, and metadata in a single file with a compact binary layout. GGUF supports various quantization levels (Q4_K_M, Q5_K_M, Q8_0, etc.) that reduce model size with varying quality trade-offs.

📖 [GGUF Documentation](ai/gguf.md)

### What is llama.cpp?
llama.cpp is an open-source C++ library for running large language models efficiently on consumer hardware. AndroLLM vendors a stock (unpatched) copy of llama.cpp and builds it into a shared library (`libandrollm_llama.so`) with Android NDK cross-compilation. The JNI bridge exposes model loading, context creation, token generation, and chat templating to the Kotlin layer.

📖 [llama.cpp Integration](ai/llama-cpp.md)

### What models can I run?
Any GGUF model compatible with the architectures supported by the vendored llama.cpp (137 architectures including llama, gemma2, qwen2, deepseek, mistral, phi3, and more). The Model Catalog screen shows RAM requirements and recommended context lengths for each model.

General guidelines:
- **< 2 GB RAM available**: 0.5B–1.5B parameter models (Q4 quantization)
- **2–4 GB RAM available**: 1.5B–3B parameter models
- **4–8 GB RAM available**: 3B–7B parameter models
- **8+ GB RAM available**: 7B–14B parameter models

These are estimates — actual requirements vary by model architecture and context length.

### How do I add a custom model?
1. Download a GGUF file from HuggingFace or another source
2. Place it in the app's model directory (Settings → Storage)
3. The app will auto-detect it; alternatively, use the Models screen → Import
4. Select the model and tap "Load"

---

## Voice Assistant

### How do I use the voice assistant?
1. Enable the voice assistant in Settings → Voice Assistant
2. Grant microphone permission when prompted
3. Say **"Hey Andro"** or **"Okay Andro"** to activate
4. Speak your question or command
5. The app will respond via text and speech

### Can the voice assistant work without internet?
Yes. The entire voice pipeline is offline:
- Wake word detection: sherpa-onnx KWS model (~3 MB)
- Speech recognition: sherpa-onnx streaming ASR (~8 MB)
- Text-to-speech: Piper VITS model (~114 MB, lazily loaded)

### Why isn't the wake word detecting?
Check these common causes:
- **Distance**: Speak the wake phrase clearly, about 1–2 meters from the device
- **Background noise**: Loud environments can mask the wake phrase
- **Sensitivity**: Adjust sensitivity in Settings → Voice Assistant
- **Battery saver**: Battery saver mode limits wake word detection to save power
- **Permissions**: Ensure `RECORD_AUDIO` is granted
- **Overlay permission**: The floating UI requires `SYSTEM_ALERT_WINDOW` but the service still functions without it

### What voice commands are available?
| Command | Action |
|---|---|
| "mute" / "unmute" | Toggle microphone |
| "stop speaking" | Interrupt TTS playback |
| "new chat" | Start a fresh conversation |
| "open settings" | Navigate to settings |
| "open models" | Navigate to models |
| "switch theme" | Cycle light/dark theme |
| "delete conversation" | Remove current conversation |
| "summarize chat" | Generate conversation summary |
| "enable offline mode" | Disable cloud providers |
| "disable offline mode" | Re-enable cloud providers |
| "enable voice" | Turn on voice assistant |
| "disable voice" | Turn off voice assistant |

---

## Memory System

### What is the memory system?
The memory system extracts facts, preferences, and context from your conversations and stores them for future retrieval. When you start a new conversation, relevant memories are injected into the system prompt so the model "remembers" what it learned previously.

### How does memory work?
1. After each exchange, the system extracts memorable facts (names, dates, preferences)
2. Extracted memories are embedded (converted to vectors)
3. Vectors are stored in an in-memory cosine similarity index
4. At conversation start, the system searches for relevant memories and injects them

### Do I need internet for memory?
No, if you use a local embedding model. The system falls back to keyword matching and recency-based sorting if embeddings are unavailable. Cloud embedding is optional.

### How do I delete my memory data?
Go to Settings → On-device Memory → Delete all memories.

---

## Cloud Providers

### What cloud providers are supported?
Any provider compatible with the **LiteLLM proxy protocol** (OpenAI-compatible API):
- Google Gemini
- Anthropic Claude
- OpenAI GPT
- xAI Grok
- Meta Llama
- Mistral
- Custom self-hosted LiteLLM instances

### How do I add a provider?
Go to Settings → Cloud Providers → Add Provider. Enter:
- Provider name (e.g., "My LiteLLM")
- Base URL (e.g., `https://litellm.example.com`)
- API key (encrypted and stored locally)

### Can I use multiple providers?
Yes. You can configure multiple providers and switch between them. The app monitors health and can fall back to an alternative provider on failure.

---

## Technical

### What Android versions are supported?
AndroLLM requires **Android 9 (API 28)** or higher. This corresponds to devices with at least:
- ARM64 (arm64-v8a) or x86_64 architecture
- Vulkan 1.1+ support (for GPU acceleration; falls back to CPU if unavailable)

### How much RAM do I need?
Minimum: 4 GB total device RAM. Recommended: 8 GB for models above 3B parameters. The app reports estimated requirements per model in the catalog.

### How much storage do I need?
- App itself: ~150 MB (includes voice models)
- Each GGUF model: varies by size (500 MB – 10+ GB)
- Voice models bundled: ~125 MB total
- Memory system overhead: minimal (< 10 MB typically)

### Can I use AndroLLM without Firebase?
Yes. Firebase authentication is entirely optional. You can use the app as a guest without signing in. Cloud sync features require Firebase but are not part of core functionality.

---

## Development

### How do I build from source?
See [BUILDING.md](BUILDING.md).

### How do I contribute?
See [CONTRIBUTING.md](../CONTRIBUTING.md).

### Where do I report bugs?
File an issue on [GitHub](https://github.com/your-org/androllm/issues) using the bug report template. Include device info, logs, and repro steps.
