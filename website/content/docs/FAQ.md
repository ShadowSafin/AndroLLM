# FAQ — AndroLLM

Frequently asked questions about AndroLLM.

---

## General

### What is AndroLLM?
AndroLLM is a production-grade Android application that runs large language models locally on your device. It runs `.litertlm` models through Google's LiteRT-LM runtime with CPU and GPU acceleration, connects to cloud AI providers via LiteLLM, maintains persistent memory across conversations, and includes a fully offline voice assistant.

### Does AndroLLM work offline?
Yes. All core functionality works offline:
- Local `.litertlm` model inference (CPU or GPU)
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
**`.litertlm`** is the primary supported format — LiteRT-LM engine files executed by Google's LiteRT-LM runtime.

| Format | Local Inference | Catalog Display |
|---|---|---|
| `.litertlm` | ✅ Yes | ✅ Yes |
| GGUF | ❌ No (metadata inspection only in the import flow) | ✅ Yes (informational) |
| SAFETENSORS | ❌ No | ✅ Yes (informational) |
| PYTORCH | ❌ No | ✅ Yes (informational) |
| ONNX | ❌ No (voice models only) | ✅ Yes (informational) |
| QNN | ❌ No | ✅ Yes (informational) |

### What is a .litertlm file?
A `.litertlm` file is a LiteRT-LM engine file — a single-file container with the model weights, tokenizer, and an embedded `LlmMetadata` proto (family, architecture, context length, quantization). The app reads that metadata to configure chat templates, special tokens, and stop sequences automatically.

📖 [Model Formats Documentation](ai/model-formats.md)

### What is LiteRT-LM?
LiteRT-LM is Google's on-device LLM inference runtime (formerly the Gemini Nano-era engine lineage). AndroLLM embeds it as a Maven artifact (`com.google.ai.edge.litertlm:litertlm-android:0.16.0`) — the engine module is 100% Kotlin/Java with no native code.

📖 [LiteRT-LM Engine Documentation](ai/litert-lm.md)

### What models can I run?
The curated catalog ships **21 `.litertlm` models** from the `litert-community` organization, across Qwen, Gemma, DeepSeek, and other supported families. The Models screen shows RAM requirements and metadata context lengths for each model.

General guidelines (catalog models are ~475 MB–1.3 GB):
- **2 GB RAM**: Qwen3-0.6B class models
- **3–4 GB RAM**: Qwen2.5-1.5B / Gemma 3 1B class models

These are estimates — actual requirements vary by model architecture and context length.

### How do I add a custom model?
1. Download a `.litertlm` file from a `litert-community` repository (HuggingFace or ModelScope)
2. Place it in the app's model directory (Settings → Storage)
3. The app will validate it (`LiteRtValidator` + SHA-256) and read its metadata via `ModelInspector`
4. Select the model and tap "Load"

### Can I run GGUF files?
No. GGUF files can only be **inspected** — the app identifies them via `GgufReader` in the import flow and explains that they cannot be run. There is no llama.cpp runtime and no conversion path.

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

The LLM reply comes from LiteRT-LM (local) or a cloud provider if you've configured one.

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
2. Extracted memories are embedded (converted to vectors) — locally via the LiteRT `CompiledModel` API or via cloud
3. Vectors are stored in an in-memory cosine similarity index
4. At conversation start, the system searches for relevant memories and injects them

### Do I need internet for memory?
No, if you use the local embedding model (LiteRT `CompiledModel` API). The system falls back to keyword matching and recency-based sorting if embeddings are unavailable. Cloud embedding is optional.

### How do I delete my memory data?
Go to Settings → On-device Memory → Delete all memories.

---

## Chat Attachments

### Can I upload PDFs?
Yes — when using a **cloud model**. Attach a PDF (or DOCX, PPTX, XLSX, TXT, Markdown, CSV, JSON, HTML, images or screenshots) with the paperclip button and ask about it. Local models do not support attachments.

### Can local models analyze files?
Not at this time. Attachments require cloud-scale document understanding, so the feature is available only for cloud models. The attachment button and settings are hidden while a local model is selected.

### Are my files permanently stored?
No. Attachments are **conversation-scoped** and are not indexed into a persistent library. Files are processed temporarily on-device; the temporary cache is removed when the conversation is deleted or the attachment cache is cleared.

### Why can't I see the attachment button?
The currently selected model does not support attachments. Switch to a compatible cloud model (cloud chat mode with a configured provider) to attach files.

### What happens to attachments when I switch to a local model?
You are asked to confirm first: switching removes the pending attachments. Attachments already in a conversation stay visible but become read-only until you switch back to a cloud model.

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
Yes. You can configure multiple providers and switch between them. The app monitors provider health, and if the active provider fails **before producing any output** (timeout, rate limit, server error), the request is automatically retried on your other enabled providers. Failures that happen mid-response are shown as-is so partial output is preserved.

---

## Cloud Tools, Usage & Caching

### Can cloud models run tools on my device?
Yes. Cloud models can call the same built-in tools as local models — web search, weather, contacts, calculator, SMS, email, calls, calendar, notes, navigation, and more. Tool arguments are validated before execution, and **sensitive actions (SMS, email, calls, calendar, device operations) always require your confirmation** before they run.

### What are conditional tool workflows?
You can give instructions like *"check the weather, and if it rains, message Mom"* or *"search for X and if you find anything, email it to me"*. The app evaluates the condition against the actual tool result and skips the follow-up action when the condition clearly isn't met — then tells you what happened instead of silently doing the wrong thing.

### Why did my cloud request use a different provider?
If your primary provider failed before returning any output (rate limit, timeout, outage), the app automatically fell back to another enabled provider. Every fallback is recorded and visible in the usage dashboard.

### What is the Cloud Usage Dashboard?
A built-in view of your cloud activity: **Settings → Cloud Usage Dashboard** (also via the chart icon on the Cloud Providers screen). It shows requests, tokens, estimated cost, latency, success/error rates, rate-limit hits, cache performance, provider health and quotas, alerts, and a detailed request history — with date/provider/model filters and CSV export.

### Are the cost figures exact?
No — they are **estimates** computed from a built-in per-model price table. Unknown models fall back to a family-based heuristic. Treat them as relative guidance, not billing.

### Does AndroLLM cache my prompts?
Only the **stable, non-private parts**: system prompts and tool schemas. The cache is used to (a) let providers reuse their server-side prompt caches (Anthropic-style `cache_control` or automatic prefix caching) and (b) report savings. Your personal messages and dynamic content are never cached. The cache invalidates automatically when the system prompt, tool set, model, or provider changes, and its hit/miss/savings stats appear in the usage dashboard.

---

## Technical

### What Android versions are supported?
AndroLLM requires **Android 9 (API 28)** or higher, with **arm64-v8a** architecture (the app ships arm64-only builds).

### How much RAM do I need?
The curated catalog targets **2–4 GB device RAM**. The app reports estimated requirements per model in the catalog (`minRamGb` / `recommendedRamGb`).

### How much storage do I need?
- App itself: ~150 MB (includes voice models)
- Each `.litertlm` model: ~475 MB – 1.3 GB
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
File an issue on [GitHub](https://github.com/ShadowSafin/AndroLLM/issues) using the bug report template. Include device info, logs, and repro steps.