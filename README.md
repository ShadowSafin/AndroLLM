# 🤖 AndroLLM

### Private AI. Native Android. Your Models. Your Choice.

A production-grade Android application that runs local GGUF language models, connects to cloud AI providers via LiteLLM, maintains persistent memory, supports offline voice interaction, and delivers a modern Material 3 interface — all in one app.

---

## ✨ What Makes AndroLLM Different

Most mobile AI apps either require the cloud or are limited demos. AndroLLM is a **full product** — private by default, extensible when you need more power.

| | Most Mobile AI Apps | AndroLLM |
|---|---|---|
| **Local LLMs** | None or limited | Full llama.cpp + GGUF support |
| **GPU Acceleration** | Rare | Vulkan offloading on capable devices |
| **Cloud Providers** | One proprietary backend | Any LiteLLM-compatible endpoint |
| **Persistent Memory** | None | Vector embeddings + retrieval |
| **Voice Assistant** | None or cloud-only | Fully offline: wake word → ASR → LLM → TTS |
| **Your Data** | Sent to provider's servers | Stays on device unless you opt in |

---

## 🚀 Quick Start

```bash
# Requires: Android Studio, JDK 17, NDK r26, Vulkan SDK (for debug builds)
git clone https://github.com/your-org/androllm.git
cd androllm
./gradlew assembleDebug
```

📖 [Building Guide](BUILDING.md)

---

## 🔧 Architecture at a Glance

```
┌─────────────────────────────────────────────────────────────┐
│                     Presentation Layer                       │
│         Jetpack Compose · Material 3 · Hilt Navigation       │
├─────────────────────────────────────────────────────────────┤
│                         Chat Layer                           │
│   ChatViewModel · Streaming UI · Markdown · Memory Context   │
├──────────────┬──────────────────────────────────────────────┤
│  Local Runtime│          Cloud Gateway                       │
│              │                                              │
│  llama.cpp   │  LiteLLM Client (Retrofit + OkHttp)          │
│      ↓       │        ↓                                     │
│   GGUF Model │  Provider Manager + KeyCipher                │
│              │        ↓                                     │
│  Vulkan/CPU  │  SSE Streaming + Retry Policy                │
├──────────────┴──────────────────────────────────────────────┤
│                    Support Layer                             │
│  Memory (embeddings/vector search) · Voice (sherpa-onnx)    │
│  Database (Room) · Auth (Firebase) · Network (Ktor)         │
└─────────────────────────────────────────────────────────────┘
```

---

## 🧠 Core Features

### Local AI Engine
Run open-source language models directly on your device through a **vendored llama.cpp** build. Supports GGUF format with automatic CPU↔Vulkan backend selection.

- **GGUF** models from HuggingFace or any source
- **Vulkan GPU acceleration** on devices with Adreno/Mali/Apple Silicon GPUs
- **CPU fallback** for devices without Vulkan support
- Streaming token output with real-time markdown rendering
- Multi-turn conversations using KV-cache persistence

### Cloud AI Integration
Connect to any OpenAI-compatible provider through a **LiteLLM proxy** or direct API.

- Google Gemini, Anthropic Claude, OpenAI GPT, xAI Grok, Meta Llama
- Encrypted API key storage via Android Keystore (AES-256/GCM)
- Automatic health monitoring and provider failover
- Custom provider configuration

### Persistent Memory
The app remembers what matters across conversations.

- SQLite-backed storage with vector embeddings
- Automatic extraction of facts, preferences, and context
- Hybrid search: keyword match + cosine similarity
- Works with both local and cloud models
- Full data deletion on demand

### Offline Voice Assistant
Say **"Hey Andro"** and interact hands-free, completely offline.

| Pipeline Stage | Technology | Runs Offline? |
|---|---|---|
| Wake Word | sherpa-onnx KWS (zipformer2) | ✅ Yes |
| Speech Recognition | sherpa-onnx streaming ASR (en-20M int8) | ✅ Yes |
| Voice Activity Detection | Energy-based VAD | ✅ Yes |
| Text-to-Speech | Piper VITS (sherpa-onnx) | ✅ Yes |

The voice service runs as a foreground service with system overlay and barge-in support.

### Privacy-First Design
- All local inference happens on-device — no network requests during generation
- Firebase authentication is optional; guest mode works without signing in
- API keys are encrypted in the Android Keystore; never stored in plaintext
- Memory data lives in a local Room database; user controls what gets shared

### Modern UI / UX
Built on the **"Parchment Ledger"** design system — warm, editorial aesthetics with terracotta accents.

- Jetpack Compose with Material 3
- Adaptive navigation (bottom bar on phone, rail on tablet/foldable)
- Streaming text with 60fps throttling
- Markdown rendering with code block syntax highlighting
- Dark and light themes

---

## 📊 Technical Overview

| Category | Technology |
|---|---|
| **Language** | Kotlin 2.1.20 |
| **UI Framework** | Jetpack Compose 1.7.2 / Material 3 |
| **Architecture** | MVVM + Clean Architecture + Repository Pattern |
| **DI** | Hilt (Dagger) 2.57.1 |
| **Navigation** | Navigation Compose 2.8.4 |
| **Async** | Kotlin Coroutines 1.8.0 + Flow |
| **Database** | Room 2.8.4 (WAL mode, 4 entities, version 5) |
| **Preferences** | DataStore Preferences 1.1.1 |
| **Native Inference** | llama.cpp (vendored upstream, stock) + JNI |
| **GPU Backend** | Vulkan via ggml (build-time enabled) |
| **Voice ASR/TTS/KWS** | sherpa-onnx 1.13.4 (ONNX Runtime Mobile) |
| **Networking** | Ktor 3.0.3 + Retrofit + OkHttp 4.12.0 |
| **Authentication** | Firebase Auth 34.12.0 (Google + GitHub OAuth) |
| **Secrets Storage** | Android Keystore (AES-256/GCM) via KeyCipher |
| **Logging** | Timber 5.0.1 |
| **Image Loading** | Coil 2.6.0 |
| **Min SDK** | 28 (Android 9) |
| **Target SDK** | 34 |

---

## 🏗️ Project Structure

AndroLLM uses a **multi-module Gradle project** with 26 modules organized into three tiers:

```
AndroLLM/
├── app/                          # Main application (entry point, DI, navigation)
│
├── core/                         # Shared library modules (11 modules)
│   ├── common/                   # BaseViewModel, Result, UiState, extensions
│   ├── ui/                       # Theme, color tokens, shared composable components
│   ├── database/                 # Room entities, DAOs, repositories, migrations
│   ├── datastore/                # Preferences DataStore wrappers
│   ├── navigation/               # Route constants, navigation helpers
│   ├── models/                   # Domain models, model catalog engine & search
│   ├── network/                  # Ktor client factory, Hugging Face API, downloads
│   ├── cloud/                    # LiteLLM client, provider manager, key encryption
│   ├── utils/                    # Permissions, storage, connectivity, logging
│   ├── telemetry/                # Performance telemetry & history
│   ├── memory/                   # Vector memory: embeddings, retrieval, summarization
│   └── voice/                    # sherpa-onnx: ASR, TTS, VAD, wake word engines
│
├── engine/                       # LLM inference engine
│   ├── src/main/java/            # Kotlin API: InferenceEngine, EngineRepository
│   └── src/main/cpp/             # Native layer
│       ├── native_api.cpp        # JNI bridge (~3700 lines)
│       └── llama.cpp/            # Vendored upstream llama.cpp (stock, unpatched)
│
├── feature/                      # Feature modules (10 modules)
│   ├── home/                     # Home screen, recent chats, quick actions
│   ├── chat/                     # Chat UI, streaming, markdown, drawer
│   ├── models/                   # Model catalog, download manager, benchmarks
│   ├── settings/                 # App settings, voice config, memory settings
│   ├── voice/                    # Foreground service, overlay UI, state machine
│   ├── splash/                   # Animated splash screen
│   ├── onboarding/               # First-run onboarding flow
│   ├── profile/                  # User profile with Firebase sync
│   ├── prompts/                  # Prompt library
│   ├── developer/                # Developer tools & diagnostics
│   └── cloud/                    # Cloud provider management & model browsing
│
├── docs/                         # Internal documentation module
├── gradle/                       # Version catalog (libs.versions.toml)
└── tools/                        # Build helper scripts
```

---

## 🎙️ Voice Assistant Pipeline

```
[Microphone @ 16kHz mono]
         │
         ▼
  AudioRecorder (200ms chunks → Channel<FloatArray>)
         │
    ┌────┴────┐
    │  LISTEN │ ← SherpaOnnxWakeWordEngine (KWS zipformer2)
    │  PHASE  │   Detects: "Hey Andro" / "Okay Andro"
    └────┬────┘
         │ WAKE_DETECTED
         ▼
    ┌───────────┐ ← SherpaOnnxStreamingRecognizer (en-20M int8)
    │  ASR      │   Streaming speech → text
    │  PHASE    │
    └─────┬─────┘
          │ Transcript
          ▼
    ┌──────────────┐
    │ Command Router│  "mute", "settings", "new chat"... (12 local commands)
    └───────┬──────┘
            │
     ┌──────┴──────┐
     │   LLM       │ ← Local: llama.cpp GGUF
     │   Routing   │ ← Cloud: LiteLLM (Gemini, Claude, GPT, etc.)
     └──────┬──────┘
            │ Streamed response
            ▼
     ┌──────────────┐ ← SentenceAssembler (split on . ! ? newline)
     │  TTS Play    │ ← PiperSpeechSynthesizer (VITS-LJSpeech @ 22050Hz)
     │  + VAD Barge │ ← Energy-based VAD (threshold 0.005 RMS)
     └──────────────┘
```

---

## 🧩 Memory System

```
User Message → MemoryManager.processExchange()
                    │
                    ├── IntelligenceRouter.extract()
                    │       ├── Cloud path: via LiteLLM embedding endpoint
                    │       └── Local path: local LLM-based extraction
                    │
                    ├── EmbeddingRouter.embed()
                    │       ├── Cloud path: via cloud provider embeddings API
                    │       └── Local path: separate llama.cpp embedding model handle
                    │
                    ├── CosineVectorIndex.upsert()  ← in-memory brute-force search
                    └── Room DB: MemoryEntity + EmbeddingEntity (BLOB)
```

Memory is **model-independent**: memories extracted with one model can be retrieved by another. The system works without embeddings (keyword/recency fallback).

---

## ⚡ Performance

Token generation speed depends on:

- **Model size** (quantization and parameter count)
- **Backend** (Vulkan GPU vs. ARM64 CPU with KleidiAI microkernels)
- **Context length** (larger context = more KV cache = slower)
- **Device thermal state** (throttling reduces clock speeds)

Benchmark your model with the built-in **Developer Diagnostics** screen. No pre-set benchmarks are claimed.

---

## 📦 Installation

### From Source
```bash
git clone <repo-url>
cd AndroLLM
# Build debug APK
./gradlew assembleDebug
# Build release APK (requires keystore configured in local.properties or gradle.properties)
./gradlew assembleRelease
```

See [Building Guide](BUILDING.md) for full environment setup.

### Prerequisites
- **Android Studio** Hedgehog or newer
- **JDK 17** (via Gradle toolchain / foojay-resolver)
- **Android SDK** with API 34 platform
- **NDK** 26.1.10909125 (for the `engine` module)
- **Vulkan SDK** (required for host-side shader compilation; install from [LunarG](https://vulkan.lunarg.com/))

---

## 🔐 Authentication

AndroLLM supports optional Firebase authentication:

- **Google Sign-In** via Credential Manager + Google Identity Services
- **GitHub Sign-In** via Firebase OAuth (scopes: `read:user`, `user:email`)

Authentication enables cloud sync and advanced features but is **not required** — you can use the app as a guest.

📖 [Firebase Auth Documentation](FIREBASE_AUTH.md)

---

## 📚 Full Documentation Index

| Document | Description |
|---|---|
| [ARCHITECTURE.md](ARCHITECTURE.md) | Complete system architecture overview |
| [BUILDING.md](BUILDING.md) | Step-by-step build instructions |
| [MODEL_SUPPORT.md](MODEL_SUPPORT.md) | Supported GGUF models and formats |
| [VOICE_ASSISTANT.md](docs/voice/voice-assistant.md) | Deep dive into the offline voice stack |
| [MEMORY.md](MEMORY.md) | Memory system, embeddings, and retrieval |
| [VULKAN.md](docs/ai/vulkan.md) | Vulkan GPU acceleration details |
| [CLOUD_PROVIDERS.md](docs/cloud/cloud-providers.md) | Cloud AI provider architecture |
| [TROUBLESHOOTING.md](TROUBLESHOOTING.md) | Common issues and solutions |
| [FAQ.md](FAQ.md) | Frequently asked questions |

---

## 🤝 Contributing

We welcome contributions of all kinds — bug reports, documentation fixes, new features.

📖 [Contributing Guide](CONTRIBUTING.md)

---

## 📄 License

This project is licensed under the Apache License 2.0. See [LICENSE](LICENSE.md).

---

## 🔗 References

- [llama.cpp documentation](https://github.com/ggerganov/llama.cpp)
- [sherpa-onnx documentation](https://k2-fsa.github.io/sherpa/onnx/)
- [Jetpack Compose documentation](https://developer.android.com/jetpack/compose)
- [Material 3 documentation](https://m3.material.io/)
- [Vulkan documentation](https://www.khronos.org/vulkan/)
- [Firebase Authentication](https://firebase.google.com/docs/auth)
- [LiteLLM documentation](https://litellm.vercel.app/)
- [Android Keystore documentation](https://developer.android.com/privacy-and-security/keystore)
