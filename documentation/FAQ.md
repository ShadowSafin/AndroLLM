# FAQ — AndroLLM

Frequently asked questions about AndroLLM.

---

## General

### What is AndroLLM?
AndroLLM is a production-grade Android application that runs large language models locally on your device using the **LiteRT-LM** runtime (Google's on-device inference engine, the successor to TensorFlow Lite for LLMs), connects to cloud AI providers via LiteLLM, maintains persistent memory across conversations, and includes a fully offline voice assistant.

### Does AndroLLM work offline?
Yes. All core functionality works offline:
- Local `.litertlm` model inference (CPU via XNNPACK, or GPU via the LiteRT GPU delegate)
- Voice assistant (wake word, ASR, TTS)
- Persistent memory (embeddings and retrieval)
- Conversation history

Cloud features (provider chat, cloud embeddings) require an internet connection but are entirely optional.

### Does it require an internet connection?
Not for local use. Internet is only required for:
- Downloading models from HuggingFace / ModelScope
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
**`.litertlm`** (LiteRT-LM engine file format) is the primary and only runnable local inference format. The app also handles these formats for display/import purposes:

| Format | Local Inference | Catalog Display |
|---|---|---|
| `.litertlm` | ✅ Yes | ✅ Yes |
| `.tflite` | ✅ Yes (embedding models) | ✅ Yes |
| GGUF | ❌ No (metadata inspection only) | ❌ No |

### What is LiteRT-LM?
LiteRT-LM is Google's on-device LLM inference runtime (the LLM successor of TensorFlow Lite). It loads `.litertlm` container files — a single file bundling weights, tokenizer, and chat template — and executes them on CPU (XNNPACK) or GPU (OpenCL delegate). AndroLLM integrates it as a pure Kotlin/Java module; there is no native code in the inference path.

📖 [LiteRT-LM Integration](ai/litert-lm.md)

### What is the `.litertlm` format?
A self-describing container with a LiteRT `LlmMetadata` proto embedded. The engine reads the metadata at load time to resolve the model family, chat template, tokenizer, and context length. No conversion is needed — download and run.

📖 [Model Formats](ai/model-formats.md)

### What models can I run?
Any `.litertlm` model from the official **`litert-community`** catalog on HuggingFace / ModelScope. The bundled catalog ships 6 chat models (Qwen, Gemma, DeepSeek families) plus an embedding model, across architectures `qwen2`, `qwen3`, `gemma3`, `gemma4`.

General guidelines by available RAM:

| Available RAM | Recommended |
|---|---|
| ~2 GB | Qwen3 0.6B Mixed Int4 (~475 MB) |
| 2–4 GB | Gemma 3 1B Q4 (~557 MB) |
| 3–6 GB | Qwen2.5 1.5B Q8 / DeepSeek R1 Distill 1.5B Q8 |
| 4–8 GB | Gemma 4 E2B (~2.4 GB) |
| 6 GB+ | Gemma 4 E4B (~3.4 GB) |

These are estimates — actual requirements vary by context length. `ModelResourceGuard` refuses loads that exceed the device's available RAM.

### How do I add a custom model?
1. Download a `.litertlm` file from `litert-community` on HuggingFace (or browse it in-app from the Models screen)
2. Use the system share sheet → AndroLLM, or Models screen → Import
3. The app validates the container (`LiteRtValidator`), inspects its metadata, and lists it
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
2. Extracted memories are embedded (converted to vectors) — locally via the LiteRT **EmbeddingGemma 300M** model, or optionally via cloud providers
3. Vectors are stored in an in-memory cosine similarity index
4. At conversation start, the system searches for relevant memories and injects them

### Do I need internet for memory?
No, if you use the local embedding model (EmbeddingGemma 300M, ~171 MB, bundled in the catalog). The system falls back to keyword matching and recency-based sorting if embeddings are unavailable. Cloud embedding is optional.

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
AndroLLM requires **Android 9 (API 28)** or higher on an **arm64-v8a** device (the APK ships arm64-v8a only). No Vulkan dependency — acceleration runs on CPU (XNNPACK) or GPU (LiteRT OpenCL delegate) with automatic fallback.

### How much RAM do I need?
Minimum: 4 GB total device RAM recommended; the smallest catalog model (Qwen3 0.6B) needs ~2 GB available. The catalog reports per-model RAM guidance and the app enforces it at load time.

### How much storage do I need?
- App itself: ~150 MB (includes voice models)
- Each `.litertlm` model: ~475 MB – 3.5 GB depending on model
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