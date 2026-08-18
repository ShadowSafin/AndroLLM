# AndroLLM

### Private AI. Native Android. Your Models. Your Choice.

A production-grade AI platform for Android that brings local `.litertlm` model inference on Google's LiteRT-LM runtime, CPU and GPU acceleration, cloud provider integration, persistent memory, and hands-free voice interaction into one unified application.

---

<p align="center">
  <img src="https://raw.githubusercontent.com/ShadowSafin/AndroLLM/main/app/src/main/res/drawable/ic_launcher_image.png" width="140" alt="AndroLLM Logo" />
</p>

<p align="center">
  <strong>Run powerful language models directly on your device.</strong><br/>
  Zero cloud dependency. Zero data leaves your phone — unless you choose otherwise.
</p>

<p align="center">
  <a href="#-features"><strong>Features</strong></a> ·
  <a href="#-ai-agent"><strong>AI Agent</strong></a> ·
  <a href="#-architecture"><strong>Architecture</strong></a> ·
  <a href="#-getting-started"><strong>Getting Started</strong></a> ·
  <a href="#-voice-assistant"><strong>Voice</strong></a> ·
  <a href="#-cloud-providers"><strong>Cloud</strong></a> ·
  <a href="#-memory"><strong>Memory</strong></a> ·
  <a href="#-documentation"><strong>Docs</strong></a> ·
  <a href="#-contributing"><strong>Contributing</strong></a>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Android-3F89D7?style=for-the-badge&logo=android&logoColor=white" alt="Android"/>
  <img src="https://img.shields.io/badge/Kotlin-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white" alt="Kotlin"/>
  <img src="https://img.shields.io/badge/Jetpack%20Compose-4285F4?style=for-the-badge&logo=jetable-compose&logoColor=white" alt="Jetpack Compose"/>
  <img src="https://img.shields.io/badge/Material%203-6750A4?style=for-the-badge&logo=material-design&logoColor=white" alt="Material 3"/>
  <img src="https://img.shields.io/badge/License-Apache%202.0-yellow?style=for-the-badge" alt="License"/>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/minSdk-28-3DDC84?style=for-the-badge&logo=android" alt="minSdk"/>
  <img src="https://img.shields.io/badge/targetSdk-35-3DDC84?style=for-the-badge&logo=android" alt="targetSdk"/>
  <img src="https://img.shields.io/badge/Kotlin-2.1.20-7F52FF?style=for-the-badge" alt="Kotlin Version"/>
  <img src="https://img.shields.io/badge/LiteRT--LM-0.16.0-34D399?style=for-the-badge" alt="LiteRT-LM"/>
  <img src="https://img.shields.io/badge/Hilt-2.57.1-EF6C00?style=for-the-badge" alt="Hilt"/>
  <img src="https://img.shields.io/badge/Room-2.8.4-4DD0E1?style=for-the-badge" alt="Room"/>
</p>

---

## 🎬 Introduction

<p align="center">
  <video controls width="560" poster="https://img.youtube.com/vi/1FScG98urkw/maxresdefault.jpg" src="https://media.githubusercontent.com/media/ShadowSafin/AndroLLM/main/videos/introduction.mp4"></video>
  <br/>
  <em>Or watch it <a href="https://www.youtube.com/watch?v=1FScG98urkw">on YouTube</a></em>
</p>

---

## ✨ What Makes AndroLLM Different

Most mobile AI apps either route everything through the cloud or ship as a lightweight demo. **AndroLLM is a complete, production-quality product**:

| | Typical Mobile AI Apps | AndroLLM |
|---|---|---|
| **Local LLMs** | None or limited experiments | LiteRT-LM runtime + `.litertlm` containers |
| **GPU Acceleration** | Rarely available | OpenCL GPU delegate with automatic CPU fallback |
| **Multi-turn Chat** | None or re-prefill every turn | KV-cache persistence, diff-based continuation |
| **Cloud Providers** | One proprietary backend | Any LiteLLM-compatible endpoint |
| **Persistent Memory** | None | Vector embeddings + hybrid retrieval |
| **Voice Assistant** | Cloud-dependent | Fully offline: wake word → ASR → LLM → TTS |
| **Your Data** | Sent to provider servers | Stays on-device by default |

---

## 🧠 Features

<table>
<tr>
<td width="50%">

### ⚡ Local Inference Engine

Run `.litertlm` language models entirely on-device through **Google's LiteRT-LM runtime** — a pure-Kotlin engine with zero native code. No internet required after model download.

- LiteRT-LM 0.16.0 runtime (`com.google.ai.edge.litertlm`)
- Container validation and memory estimation before load (`LiteRtValidator`, `MemoryEstimator`)
- Per-family chat templates, special tokens, and stop sequences from container metadata
- Streaming token output with stop-sequence tracking
- Multi-turn conversations via KV-cache persistence
- Tool-advertisement budgeted to the real context window (no more context overflow)

</td>
<td width="50%">

### 🎮 CPU + GPU Acceleration

Hardware-accelerated inference on supported devices via the OpenCL-based LiteRT GPU delegate, with CPU (XNNPACK) always available.

- CPU backend: XNNPACK, works on every arm64 device
- GPU backend: OpenCL-based LiteRT GPU delegate
- Automatic GPU → CPU fallback with corruption recovery (NaN/INF logits, device-lost)
- Real-time diagnostics: `gpuFree`, `gpuTotal`, `recoveryCount`, active backend
- NPU acceleration is next on the roadmap

</td>
</tr>
<tr>
<td width="50%">

### ☁️ Cloud Provider Integration

Connect to any OpenAI-compatible API through a unified LiteLLM proxy layer.

- Google Gemini · Anthropic Claude · OpenAI GPT · xAI Grok · Meta Llama
- Encrypted API keys via Android Keystore (AES-256/GCM)
- SSE streaming with exponential backoff retry (IOException, 408, 429, 5xx)
- Automatic health monitoring and provider failover
- Model discovery via `/v1/models` endpoint
- Custom providers with per-model overrides

</td>
<td width="50%">

### 🧩 Persistent Memory

Remember facts across conversations — locally or via cloud embeddings.

- SQLite-backed storage with in-memory vector index
- Hybrid search: cosine similarity + keyword matching
- Automatic extraction of preferences, facts, projects, opinions
- Model-independent: memories work with any loaded model
- Background indexing via WorkManager
- Full privacy: data stays on device unless cloud embedding is enabled

</td>
</tr>
<tr>
<td width="50%">

### 🎙️ Offline Voice Assistant

Hands-free interaction entirely on-device. Say **"Hey Andro"** and chat naturally.

- Wake word: sherpa-onnx KWS zipformer2 (~3 MB)
- Speech recognition: sherpa-onnx streaming ASR en-20M (~8 MB)
- Text-to-speech: Piper VITS-LJSpeech (~114 MB, lazy-loaded)
- Energy-based VAD for barge-in detection
- 12 local voice commands (mute, settings, new chat, etc.)
- Foreground service with system overlay and persistent notification

</td>
<td width="50%">

### 💎 Premium UI / UX

"The Parchment Ledger" design system — warm, editorial, calm.

- Jetpack Compose with Material 3
- Adaptive navigation: bottom bar (phone) → rail (tablet/foldable)
- Real-time markdown rendering with syntax-highlighted code blocks
- Streaming text at ~60 fps with stable item callbacks
- Light & dark themes with terracotta accent (`#D97757`)
- Conversation drawer, model parameter sheet, search overlay

</td>
</tr>
<tr>
<td width="50%">

### 🤖 AI Agent Platform

Understand, plan, and execute multi-step tasks through a capability-based tool system.

- 47+ built-in tools: weather, web search, SMS, calls, email, calendar, alarms, notes, calculator, converters, PDF/Markdown export, GitHub, QR & more
- Multi-round **plan → execute → re-plan** workflow engine with variables & conditionals
- Safety gates: per-tool permission toggles + high-risk confirmations (chat card & spoken voice)
- Contact-name resolution for messaging ("text Mom") and multipart SMS
- Effectively **unlimited answer length** — generation runs until the model finishes

</td>
<td width="50%">

### 🧩 MCP & UI Automation

Connect external MCP servers or drive third-party apps directly.

- **MCP (Streamable HTTP)** server import — remote tools become `mcp_<server>_<tool>`
- **Accessibility engine**: read screens, tap, type, scroll, drag, swipe, pinch
- Multi-step app tasks (`ui_run`) with LLM or heuristic step planning
- QR scanning, screenshot, share, and media control tools
- Strict confirmations for anything that sends, pays, books or deletes

</td>
</tr>
<tr>
<td width="50%">

### 📎 Chat Attachments (Cloud)

ChatGPT-style, **conversation-scoped** file attachments for cloud models.

- Attach PDF, DOCX, PPTX, XLSX, TXT, Markdown, CSV, JSON, HTML, images & screenshots
- Files are parsed/OCR'd **on-device**; only extracted content rides with the prompt
- Conversation-scoped: nothing is indexed, no searchable library, and the temporary cache is removed when the conversation is deleted
- Paperclip picker: **Files / Images / Camera / Gallery**, with processing chips and attachment cards in chat history
- **Cloud-only** — local models hide the feature entirely (no button, no settings)

</td>
<td width="50%">

### 🔒 Local-First Guarantee

Every capability runs on-device by default — nothing leaves the phone unless you opt in.

- **LLM inference**: LiteRT-LM, zero cloud dependency
- **Voice**: wake word → ASR → TTS, fully offline
- **Memory**: vector index in local SQLite
- **MCP / cloud**: strictly opt-in per provider
- Zero analytics, zero telemetry, zero crash reporters

</td>
</tr>
</table>

---

## 🏗️ Architecture

```mermaid
flowchart TB
    USER(["👤 User"])

    subgraph PRESENTATION["Presentation Layer"]
        COMPOSE["💎 Jetpack Compose UI<br/>Material 3 · Adaptive Nav"]
        VIEWMODELS["📐 ViewModels<br/>StateFlow · Combine"]
    end

    subgraph CHAT["Chat Layer"]
        STREAM["⚡ Streaming Engine<br/>Token flow · Markdown · Memory context"]
    end

    subgraph ROUTING["Model Router"]
        ROUTER["🧠 InferenceRouter<br/>Local ↔ Cloud selection"]
    end

    subgraph LOCAL["Local Runtime"]
        LITERT["⚡ LiteRT-LM<br/>Google runtime · pure Kotlin"]
        CONTAINER["📦 .litertlm Models<br/>Metadata validation + budget"]
        GPU["🎮 GPU Delegate<br/>OpenCL · CPU fallback"]
        MEMORY["🧠 Persistent Memory<br/>Embeddings + Retrieval"]
    end

    subgraph CLOUD["Cloud Gateway"]
        LITELLM["☁️ LiteLLM Client<br/>Retrofit + OkHttp SSE"]
        PROVIDERS["🔗 Provider Manager<br/>Health monitor + KeyCipher"]
    end

    subgraph VOICE["Voice Pipeline"]
        KWS["🎤 Wake Word<br/>sherpa-onnx KWS"]
        ASR["🗣️ ASR<br/>Streaming recognizer"]
        TTS["🔊 TTS<br/>Piper VITS-LJS"]
        VAD["📊 VAD<br/>Barge-in detection"]
    end

    subgraph PERSISTENCE["Data Layer"]
        ROOM["💾 Room Database<br/>4 entities · WAL mode"]
        DS["📝 DataStore<br/>Preferences"]
        KEYSTORE["🔐 Android Keystore<br/>AES-256/GCM encryption"]
    end

    subgraph AUTH["Authentication"]
        FIREBASE["☁️ Firebase Auth<br/>Google + GitHub OAuth"]
    end

    USER --> COMPOSE
    COMPOSE --> VIEWMODELS
    VIEWMODELS --> STREAM
    STREAM --> ROUTER
    ROUTER --> LOCAL
    ROUTER --> CLOUD
    LOCAL --> LITERT
    LITERT --> CONTAINER
    LITERT --> GPU
    STREAM --> MEMORY
    CLOUD --> LITELLM
    LITELLM --> PROVIDERS
    USER --- VOICE
    VOICE --> KWS
    KWS --> ASR
    ASR --> ROUTER
    ROUTER --> TTS
    TTS --> VAD
    VAD --> KWS
    MEMORY --> ROOM
    VIEWMODELS --> DS
    PROVIDERS --> KEYSTORE
    VIEWMODELS --> AUTH
    AUTH --> FIREBASE
```

---

## 📦 Technology Stack

<p align="center">
  <img src="https://skillicons.dev/icons?i=kotlin,android,gradle,firebase,sqlite,timber&perline=6" width="420" alt="Core Technologies"/>
</p>

<p align="center">
  <img src="https://skillicons.dev/icons?i=ktor,okhttp,coilmcp&perline=3" width="280" alt="Networking & Images"/>
</p>

<p align="center">
  <img src="https://skillicons.dev/icons?i=cpp,vulkan,onnx&perline=3" width="280" alt="Native & ML Runtimes"/>
</p>

| Layer | Technology | Purpose |
|---|---|---|
| **Language** | Kotlin 2.1.20 | Primary development language |
| **UI Framework** | Jetpack Compose 1.7.2 + Material 3 | Declarative, modern Android UI |
| **Architecture** | MVVM + Clean Architecture + Repository Pattern | Separation of concerns |
| **DI Container** | Hilt (Dagger) 2.57.1 | Compile-time dependency injection |
| **Navigation** | Navigation Compose 2.8.4 | Type-safe screen routing |
| **Async** | Kotlin Coroutines 1.8.0 + Flow | Structured concurrency |
| **Database** | Room 2.8.4 (WAL mode, v5 schema) | Local SQL persistence |
| **Preferences** | DataStore Preferences 1.1.1 | Reactive key-value storage |
| **Inference Engine** | LiteRT-LM 0.16.0 (`com.google.ai.edge.litertlm`) | Local `.litertlm` model execution |
| **GPU Backend** | OpenCL-based LiteRT GPU delegate (+ XNNPACK CPU) | Hardware-accelerated inference |
| **Voice Stack** | sherpa-onnx 1.13.4 (ONNX Runtime Mobile) | ASR, TTS, KWS, VAD |
| **Networking** | Ktor 3.0.3 + Retrofit + OkHttp 4.12.0 | HTTP client for downloads & APIs |
| **Auth** | Firebase Auth 34.12.0 | Google Sign-In + GitHub OAuth |
| **Secrets** | Android Keystore (AES-256/GCM) | API key encryption |
| **Logging** | Timber 5.0.1 | Structured logging |
| **Testing** | JUnit 4 · mockk · Turbine · Espresso | 51 test classes across 19 modules |
| **Code Quality** | Spotless · Detekt | Formatting + static analysis |

---

## 🔧 Getting Started

### Prerequisites

| Requirement | Minimum | Recommended |
|---|---|---|
| Android Studio | Hedgehog (2023.1.1) | Latest stable |
| JDK | 17 | 17 (auto-managed by Gradle) |
| Android SDK | API 35 | API 35 |
| LiteRT-LM | AARs from Maven Central (no NDK/CMake needed) | Latest |

### Build & Install

```bash
# Clone the repository
git clone https://github.com/ShadowSafin/AndroLLM.git
cd AndroLLM

# Build debug APK (pure Kotlin — no NDK, no CMake, no Vulkan SDK)
./gradlew assembleDebug

# Install on connected device
adb install app/build/outputs/apk/debug/app-debug.apk
```

📖 [Full Building Guide](documentation/BUILDING.md) · [Development Workflow](documentation/DEVELOPMENT.md)

### First Run

1. **Install** the APK on an arm64-v8a device (4 GB+ RAM recommended)
2. **Sign in** with Google or GitHub (optional — guest mode works fully)
3. **Browse models** in the Catalog tab — filters by your device's RAM
4. **Download and load** a model (Qwen3-0.6B mixed int4, ~475 MB, is a great starting point)
5. **Start chatting** — messages stream in real-time with markdown rendering
6. **Enable voice** in Settings → Voice Assistant and say "Hey Andro"
7. **Unlock the agent** — enable Tool Calling in Settings → Automation and try a multi-step request

---

## 🎙️ Voice Assistant

Complete offline voice pipeline — no internet required:

```
[Microphone @ 16kHz]
       │
       ▼
  Wake Word Detection
  "Hey Andro" / "Okay Andro"
  (sherpa-onnx KWS, ~3 MB)
       │
       ▼
  Streaming ASR
  English speech → text
  (zipformer-en-20M int8, ~8 MB)
       │
       ▼
  Command Router ───► 12 local commands
       │
       ▼
  LLM Generation ───► LiteRT-LM (.litertlm) or Cloud API
       │
       ▼
  TTS Playback
  Piper VITS-LJS @ 22050Hz
  (~114 MB, lazy-loaded)
       │
       ▼
  Barge-in via VAD ──► Interrupt and re-listen
```

**Supported voice commands:** mute, unmute, stop speaking, new chat, open settings,
open models, switch theme, delete conversation, summarize chat, enable/disable offline
mode and voice.

### Voice + Agent

- **Spoken confirmations** — for high-risk actions the assistant asks aloud
  ("*send the SMS to Mom?*") and listens for yes/no
- **Multi-step spoken tasks** — voice runs the same tool workflow as chat
- **Smart TTS** — LLM output is normalized before speaking: numbers, dates,
  currencies, units, math, emoji, URLs, phones, and out-of-lexicon words
  ("LLM" → "el el em") are all pronounced correctly; each stage is
  configurable in Settings → Text Normalization

📖 [Voice Architecture](documentation/voice/voice-assistant.md) · [Text Normalization](documentation/voice/text-normalization.md)

---

## 🤖 AI Agent

Beyond chat, AndroLLM is a full **on-device AI agent**. Enable it in
**Settings → Automation → Tool Calling**, then ask for multi-step tasks:

> *"Check today's weather and text Mom if it will rain"*
> *"Search GitHub for LiteLLM and summarize the latest release"*
> *"Turn on Bluetooth, connect my earbuds, then play my workout playlist"*

### How it works

```
Your request
     │
     ▼
  PLANNER ─── local LiteRT-LM (JSON-compat planner) ── or ── cloud (native tool calls)
     │  picks tools + arguments
     ▼
  EXECUTOR ── permission gate → confirmation gate → timeout (20 s)
     │  (the only place tool code runs)
     ▼
  TOOLS ── 45+ built-ins · accessibility · MCP remote tools
     │
     ▼
  RESULTS feed back → re-plan (up to 6 rounds) → grounded final answer
```

### Capabilities

| Capability | Highlights |
|---|---|
| **Tool catalog** | Weather, search, SMS/calls/email (confirmed), calendar, alarms, reminders, clipboard, notes, files, calculator, unit & currency converters, translation, GitHub, media, PDF/Markdown export, QR, and more — [full catalog](documentation/agent/tools.md) |
| **Workflow engine** | Multi-round plan→execute→re-plan, IF/ELSE & loops via `variable_set`/`variable_get`, live device context (battery, time, clipboard, app) injected every round — [details](documentation/agent/workflow-engine.md) |
| **Confirmations** | High-risk actions ask first — chat card **and** spoken voice question; modes: High-risk / Always / Never — [details](documentation/agent/agent-platform.md) |
| **MCP servers** | Import tools from any MCP (Streamable HTTP) server; they become first-class `mcp_<server>_<tool>` capabilities — [details](documentation/agent/mcp.md) |
| **UI automation** | Drive any app via the accessibility service: tap, type, scroll, swipe, pinch, multi-step tasks — [details](documentation/agent/accessibility-automation.md) |
| **Transparency** | Every call is trace-logged (Developer → Tool Debug); tools never run silently; retry-once on transient failures |

---

## ☁️ Cloud Providers

Add any LiteLLM-compatible endpoint from Settings → Cloud Providers:

| Provider | Status | Notes |
|---|---|---|
| Google Gemini | ✅ Supported | Via LiteLLM proxy |
| Anthropic Claude | ✅ Supported | Via LiteLLM proxy |
| OpenAI GPT | ✅ Supported | Native OpenAI API |
| xAI Grok | ✅ Supported | OpenAI-compatible endpoint |
| Meta Llama | ✅ Supported | Via self-hosted LiteLLM |
| Mistral | ✅ Supported | OpenAI-compatible API |
| Custom LiteLLM | ✅ Supported | Any OpenAI-compatible router |

API keys are encrypted with **AES-256/GCM via Android Keystore** — they never touch shared preferences or plaintext storage.

📖 [Cloud Provider Architecture](documentation/cloud/cloud-providers.md)

---

## 🧠 Memory System

Memories extracted from conversations are stored locally and injected into future contexts:

```
Conversation exchange
        │
        ├──▶ Extract facts & preferences
        │       (JSON schema: category, content, importance, tags)
        │
        ├──▶ Embed content
        │       ├── Cloud path: LiteLLM embeddings API
        │       └── Local path: LiteRT embedding engine (CompiledModel API)
        │
        ├──▶ Store in SQLite + CosineVectorIndex
        │
        └──▶ Future conversation:
                Hybrid search (vector + keyword)
                → Inject into system prompt
```

Works fully offline. Falls back to keyword/recency sorting when embeddings are unavailable.

📖 [Memory Architecture](documentation/memory/memory-architecture.md)

---

## 📊 Model Support

**Format:** `.litertlm` (LiteRT-LM engine file format) — the primary and only runnable local format. The catalog ships 7 curated models across Qwen, Gemma, and DeepSeek families (architectures: `gemma3`, `gemma4`, `gemma-embedding`, `qwen2`, `qwen3`) from the `litert-community` repos on Hugging Face and ModelScope.

| Model | Quantization | Size | Use Case |
|---|---|---|---|
| Qwen3-0.6B Mixed Int4 | Mixed int4 | ~475 MB | **Recommended daily driver** · 2–4 GB RAM |
| Gemma 3 1B IT Q4 | Q4 | ~560 MB | Small + capable · 2–4 GB RAM |
| Qwen2.5-1.5B Q8 | Q8 | ~1.3 GB | Best quality on 4 GB+ devices |

Context length is detected from container metadata at load time; the tool advertisement is budgeted to the real window so small models never overflow. Legacy GGUF files can be *inspected* (metadata) in the import flow but are **not** runnable — the app has no llama.cpp runtime.

📖 [Model Support Guide](documentation/MODEL_SUPPORT.md)

---

## 🔐 Privacy & Security

| Aspect | Implementation |
|---|---|
| **Local inference** | Runs entirely on-device; zero network transmission |
| **API key storage** | AES-256/GCM encrypted in Android Keystore |
| **Database** | Room in app-private sandbox (`/data/data/io.androllm.app/`) |
| **Network** | HTTPS-only; cleartext disabled (`usesCleartextTraffic=false`) |
| **Permissions** | Minimal set; requested lazily (not at launch) |
| **Voice audio** | Processed entirely on-device; no server transmission |
| **Analytics** | None. Zero telemetry or crash reporting services. |
| **Guest mode** | Full functionality without any authentication |

See [PRIVACY.md](PRIVACY.md) and [SECURITY.md](SECURITY.md) for full details.

---

## 📚 Documentation

| Document | Description |
|---|---|
| [ARCHITECTURE.md](documentation/ARCHITECTURE.md) | Complete system architecture with diagrams |
| [BUILDING.md](documentation/BUILDING.md) | Environment setup and build instructions |
| [CONTRIBUTING.md](CONTRIBUTING.md) | Development guidelines and code style |
| [TESTING.md](documentation/TESTING.md) | Test strategy, frameworks, and conventions |
| [DEVELOPMENT.md](documentation/DEVELOPMENT.md) | IDE setup, debugging, profiling guide |
| [TROUBLESHOOTING.md](documentation/TROUBLESHOOTING.md) | Common issues and solutions |
| [FAQ.md](documentation/FAQ.md) | Frequently asked questions |
| [PERFORMANCE.md](documentation/PERFORMANCE.md) | Token speed, RAM, Vulkan, battery guidance |
| [MODEL_SUPPORT.md](documentation/MODEL_SUPPORT.md) | `.litertlm` format, families, quantizations, RAM guidance |
| [ROADMAP.md](documentation/ROADMAP.md) | Completed, planned, and future features |
| [CHANGELOG.md](documentation/CHANGELOG.md) | Version history |
| [RELEASE_PROCESS.md](documentation/RELEASE_PROCESS.md) | Build, sign, and publish procedures |
| [PROJECT_STRUCTURE.md](documentation/PROJECT_STRUCTURE.md) | 34-module dependency graph |

### Deep-Dive Documentation (`documentation/`)

```
documentation/
├── getting-started/first-run.md        # Installation and first-time setup
├── ai/                                 # AI engine internals
│   ├── litert-lm.md                    # LiteRT-LM runtime, compat layer, lifecycle
│   ├── model-formats.md                # .litertlm containers, catalog sources, GGUF inspection
│   └── acceleration.md                 # CPU (XNNPACK) vs GPU (OpenCL delegate), fallback
├── voice/voice-assistant.md            # Full voice pipeline: KWS → ASR → TTS
├── voice/text-normalization.md         # TTS text normalization + OOV spelling
├── agent/                              # AI agent platform
│   ├── agent-platform.md               # Planning, executor safety gates, chat/voice
│   ├── tools.md                        # Complete built-in tool catalog
│   ├── workflow-engine.md              # Multi-step execution, variables, confirmations
│   ├── mcp.md                          # MCP server integration
│   └── accessibility-automation.md     # UI automation, gestures, planners
├── cloud/cloud-providers.md            # LiteLLM client, streaming, security
├── memory/memory-architecture.md       # Embeddings, retrieval, vector index
├── ui/                                 # UI architecture
│   ├── ui-architecture.md              # Design system, components, theming
│   └── chat-architecture.md            # Streaming, markdown, state management
├── backend/                            # Data layer
│   ├── database.md                     # Room schema v5, migrations, DAOs
│   ├── networking.md                   # Ktor + Retrofit stacks
│   └── firebase-auth.md                # Google + GitHub auth flow
├── security/security-architecture.md   # 4-layer security model
├── development/error-handling.md       # Result/UiState patterns, recovery
├── android/permissions.md              # All declared permissions reference
└── INDEX.md                            # Complete documentation index
```

---

## 🛠️ Project Structure

**34 Gradle modules** organized into three tiers:

```
AndroLLM/
├── app/                          # Entry point, navigation host, auth
├── core/                         # 17 shared library modules
│   ├── common/      Base types (Result, UiState, BaseViewModel)
│   ├── ui/          Compose theme, design system, shared components
│   ├── database/    Room DB (4 entities, version 5, WAL mode)
│   ├── datastore/   Preferences DataStore
│   ├── navigation/  Route constants and extensions
│   ├── models/      Domain models + catalog engine (137 architectures)
│   ├── network/     Ktor client + HuggingFace API
│   ├── cloud/       LiteLLM client + provider manager + KeyCipher
│   ├── utils/       Permissions, storage, connectivity helpers
│   ├── telemetry/   Performance metrics storage
│   ├── memory/      Vector memory system with embeddings
│   ├── voice/       sherpa-onnx ASR/TTS/KWS/VAD engines
│   ├── tools/       AI agent: planner, executor, registry, workflow, traces
│   ├── mcp/         MCP client: connection manager + remote tool adapter
│   ├── accessibility/ UI automation service, gestures, QR scanning
│   ├── runtime/     Runtime registry: tools, voice, automation registration
│   ├── permissions/ Central permission/access manager + feature map
├── engine/                       # LiteRT-LM inference engine (pure Kotlin)
│   ├── api/            InferenceEngine, EngineRepository, DefaultEngineRepository
│   ├── core/           LiteRtLmEngine — session lifecycle, streaming
│   ├── compat/         ModelFamily, registry, templates, special tokens, metadata
│   ├── models/         EngineModelInfo, GenerationConfig, MemoryStats, backends
│   ├── embedding/      LiteRtEmbeddingEngine (CompiledModel API)
│   └── utils/          MemoryEstimator, ThreadManager, CoherenceChecker, LiteRtValidator
├── feature/                      # 12 independent feature modules
│   ├── home/    Home screen, recent chats, quick actions
│   ├── chat/    Chat UI, streaming, markdown, drawer
│   ├── models/  Model catalog browser, downloader, benchmark
│   ├── voice/   Foreground service, overlay UI, state machine
│   ├── settings/ App settings, voice config, memory controls
│   ├── splash/  Animated splash screen
│   ├── onboarding/ First-run onboarding flow
│   ├── setup/    First-launch permission & access setup, Permissions & Access
│   ├── profile/ User profile with Firebase sync
│   ├── prompts/ Prompt library
│   ├── developer/ Developer tools and diagnostics
│   └── cloud/   Cloud provider management and model browsing
├── documentation/               # Internal documentation module
└── gradle/libs.versions.toml     # Centralized version catalog
```

Feature modules depend **only** on `core:*` modules — never on each other.

---

## 🚧 Roadmap Highlights

| Status | Feature |
|---|---|
| ✅ | LiteRT-LM 0.16.0 runtime — pure Kotlin, no native code |
| ✅ | `.litertlm` model catalog (7 curated models, 5 architectures) |
| ✅ | CPU (XNNPACK) + OpenCL GPU delegate with automatic fallback |
| ✅ | Cloud provider abstraction via LiteLLM |
| ✅ | Persistent memory with vector embeddings |
| ✅ | Offline voice assistant (wake word → ASR → TTS) |
| ✅ | Firebase Auth (Google + GitHub) |
| ✅ | AI agent platform (47 tools, workflow engine, confirmations) |
| ✅ | MCP server integration (Streamable HTTP) |
| ✅ | Accessibility UI automation (gestures, multi-step app tasks) |
| ✅ | Voice confirmations + TTS text normalization |
| 🔮 | **NPU backend support** (next milestone) |
| 🚧 | Multi-language ASR (Chinese, Japanese, Korean) |
| 🚧 | CI/CD pipeline |
| 🔮 | Multi-modal vision models |

See [ROADMAP.md](documentation/ROADMAP.md) for the full list.

---

## 🤝 Contributing

We welcome contributions of all kinds — bug reports, documentation fixes, new features.

📖 [Contributing Guide](CONTRIBUTING.md)

---

## 📄 License

This project is licensed under the Apache License 2.0. See [LICENSE](LICENSE.md) for details.
Third-party component licenses are listed in [LICENSES.md](LICENSES.md).

---

## 🔗 References

- [LiteRT-LM](https://ai.google.dev/edge/litert-lm) — On-device LLM inference runtime (Google AI Edge)
- [LiteRT](https://ai.google.dev/edge/litert) — On-device ML runtime (CompiledModel API for embeddings)
- [sherpa-onnx](https://k2-fsa.github.io/sherpa/onnx/) — Offline voice processing
- [Jetpack Compose](https://developer.android.com/jetpack/compose) — Modern Android UI
- [Material 3](https://m3.material.io/) — Design system
- [Firebase Auth](https://firebase.google.com/docs/auth) — Authentication
- [LiteLLM](https://litellm.vercel.app/) — Cloud provider abstraction
- [Android Keystore](https://developer.android.com/privacy-and-security/keystore) — Secure key storage
