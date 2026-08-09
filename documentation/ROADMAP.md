# Roadmap

This document tracks the planned development direction for AndroLLM. Items are marked with their current status.

---

## ✅ Completed

- [x] Multi-module Gradle project structure
- [x] Jetpack Compose Material 3 UI with "Parchment Ledger" theme
- [x] Hilt dependency injection across all 26 modules
- [x] Room database v5 with WAL mode
- [x] DataStore preferences
- [x] Firebase Authentication (Google + GitHub)
- [x] Navigation Compose with 15 routes
- [x] llama.cpp native engine integration (vendored upstream, stock)
- [x] JNI bridge with RAII lifecycle management
- [x] Vulkan GPU backend with runtime CPU fallback
- [x] Corruption recovery (NaN/INF logits, device-lost escalation)
- [x] GGUF model validation and memory estimation
- [x] Model catalog with search, filter, sort, recommendations
- [x] Official model catalog (Gemma, Qwen, DeepSeek built-ins)
- [x] HuggingFace model browsing and download
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
- [x] 51 test classes across all modules
- [x] Adaptive navigation (phone/tablet)
- [x] Model parameter sheet (temperature, top-p, etc.)

---

## 🚧 In Progress

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
- [ ] **Function calling** — Expose tool-use capabilities from models that support it
- [ ] **Image generation** — Run diffusion models via GPU backend (planned)
- [ ] **Widget support** — Home screen widget for quick chat access
- [ ] **Notification replies** — Reply to messages from system notifications

### Medium Term (3–6 Months)

- [ ] **QNN/NPU backend** — Leverage Snapdragon NPU for local inference
- [ ] **ONNX Runtime backend** — General ML model execution beyond voice
- [ ] **Model quantization tools** — Built-in Q4→Q2 conversion for smaller devices
- [ ] **Cross-device sync** — Sync conversations and memory via Firebase Firestore
- [ ] **Shared models** — Share downloaded models between users on the same device
- [ ] **API key import/export** — Backup and restore encrypted key store
- [ ] **Context window optimization** — Automatic context truncation strategies

### Long Term (6–12 Months)

- [ ] **Multi-modal models** — Vision + text models (LLaVA, etc.)
- [ ] **Agents** — Autonomous task execution with tool calling
- [ ] **Code interpreter** — Run Python snippets locally via PyTorch Mobile
- [ ] **Real-time translation** — Live conversation translation using on-device models
- [ ] **Medical/legal domain models** — Specialized fine-tuned models
- [ ] **Enterprise deployment** — MDM support, custom branding, policy enforcement

---

## 🔮 Future (Research / Exploration)

- [ ] **WebGPU backend** — WebAssembly-based inference for browser extension
- [ ] **Federated learning** — Contribute model improvements without sharing raw data
- [ ] **Homomorphic encryption** — Encrypted inference (research-stage)
- [ ] **AR overlay** — Augmented reality chat interface
- [ ] **WearOS support** — Companion app for smartwatches
- [ ] **HarmonyOS port** — Native support for Huawei devices

---

## Contributing to the Roadmap

Want to help with a planned feature?

1. Check [existing issues](https://github.com/your-org/androllm/issues) for related work
2. Comment on the issue to claim it or propose an approach
3. Read [CONTRIBUTING.md](../CONTRIBUTING.md) for development guidelines
4. Open a PR with your changes

Feature requests and bug reports are always welcome — see [SUPPORT.md](../SUPPORT.md).
