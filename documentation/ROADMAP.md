# Roadmap

This document tracks the planned development direction for AndroLLM. Items are marked with their current status.

---

## ✅ Completed

- [x] Multi-module Gradle project structure (33 modules)
- [x] Jetpack Compose Material 3 UI with "Parchment Ledger" theme
- [x] Hilt dependency injection across all modules
- [x] Room database v5 with WAL mode
- [x] DataStore preferences
- [x] Firebase Authentication (Google + GitHub)
- [x] Navigation Compose with 15 routes
- [x] **LiteRT-LM engine** (pure Kotlin/Java, no native code)
- [x] `.litertlm` model container support with metadata introspection
- [x] GPU acceleration (LiteRT GPU delegate) with automatic CPU fallback
- [x] Corruption recovery (coherence probe + recovery counters)
- [x] Container validation and memory estimation (`LiteRtValidator`, `MemoryEstimator`, `ModelResourceGuard`)
- [x] LiteRT catalog (7 models: Qwen, Gemma, DeepSeek — `litert-community`)
- [x] HuggingFace model browsing and download (filtered to `litertlm`)
- [x] On-device embeddings (EmbeddingGemma 300M via raw LiteRT)
- [x] Native tool calling (Qwen/Gemma `<|tool_call|>` markers + JSON-compat fallback)
- [x] Agent platform: 47 built-in tools, planning, confirmation, MCP client
- [x] Cloud provider abstraction via LiteLLM
- [x] SSE streaming with retry policy
- [x] Encrypted API key storage (Android Keystore AES-256/GCM)
- [x] Persistent memory system (vector embeddings + hybrid retrieval)
- [x] Offline voice assistant (wake word, ASR, TTS via sherpa-onnx)
- [x] Foreground voice service with overlay and barge-in
- [x] Markdown rendering in chat
- [x] Conversation export and share
- [x] Developer diagnostics screen
- [x] Performance telemetry system
- [x] ~62 test classes across all modules
- [x] Adaptive navigation (phone/tablet)
- [x] Model parameter sheet (temperature, top-p, etc.)
- [x] **Chat Attachments** — conversation-scoped file attachments for cloud models (PDF/DOCX/PPTX/XLSX/TXT/MD/CSV/JSON/HTML/images/OCR), paperclip picker, composer chips, history cards, cloud-only gating

---

## 🚧 In Progress

- [ ] **NPU (QNN) backend** — leverage Snapdragon NPU for local inference (next milestone; same `.litertlm` files, no re-downloads)
- [ ] Release build signing automation
- [ ] CI/CD pipeline (GitHub Actions)
- [ ] Play Store deployment preparation
- [ ] Localization / i18n support
- [ ] Memory UI polish (editing, categorization, tagging)
- [ ] Voice command extensibility framework
- [ ] On-device model benchmark dashboard

---

## 📋 Planned

### Near Term (Next Release Cycle)

- [ ] **Multi-language ASR** — Add Chinese, Japanese, Korean zipformer models
- [ ] **Voice cloning TTS** — Integrate Pocket/ZipVoice models alongside Piper
- [ ] **Conversation summary** — Auto-summarize long conversations for context window management
- [ ] **Widget support** — Home screen widget for quick chat access
- [ ] **Notification replies** — Reply to messages from system notifications

### Medium Term (3–6 Months)

- [ ] **Cross-device sync** — Sync conversations and memory via Firebase Firestore
- [ ] **Shared models** — Share downloaded models between users on the same device
- [ ] **API key import/export** — Backup and restore encrypted key store
- [ ] **Context window optimization** — Automatic context truncation strategies beyond conversation reseeding
- [ ] **Speaker diarization** — Distinguish speakers in voice chat via sherpa-onnx
- [ ] **Thermal-aware generation pacing** — Throttle generation during thermal throttling

### Long Term (6–12 Months)

- [ ] **Multi-modal models** — Vision + text models (Gemma 3 vision, etc.)
- [ ] **Code interpreter** — Run snippets locally
- [ ] **Real-time translation** — Live conversation translation using on-device models
- [ ] **Specialized fine-tuned models** — Domain-specific `.litertlm` containers
- [ ] **Enterprise deployment** — MDM support, custom branding, policy enforcement

---

## 🔮 Future (Research / Exploration)

- [ ] **Federated learning** — Contribute model improvements without sharing raw data
- [ ] **Homomorphic encryption** — Encrypted inference (research-stage)
- [ ] **AR overlay** — Augmented reality chat interface
- [ ] **WearOS support** — Companion app for smartwatches
- [ ] **HarmonyOS port** — Native support for Huawei devices

---

## Contributing to the Roadmap

Want to help with a planned feature?

1. Check [existing issues](https://github.com/ShadowSafin/AndroLLM/issues) for related work
2. Comment on the issue to claim it or propose an approach
3. Read [CONTRIBUTING.md](../CONTRIBUTING.md) for development guidelines
4. Open a PR with your changes

Feature requests and bug reports are always welcome — see [SUPPORT.md](../SUPPORT.md).