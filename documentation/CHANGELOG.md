# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [Unreleased]

### Added
- Modular multi-module Gradle project with 26 modules
- Jetpack Compose Material 3 UI with "Parchment Ledger" design system
- Full llama.cpp integration via vendored upstream build (stock, unpatched)
- JNI bridge (`native_api.cpp`, ~3700 lines) with RAII `LlamaEngine` class
- Vulkan GPU acceleration backend with runtime CPU fallback
- Corruption recovery system: NaN/INF logits, invalid tokens, Vulkan device-lost
- Multi-turn chat via KV-cache diff-based continuation
- GGUF model validation (`GgufValidator.kt`) — pure Kotlin binary header parser
- Memory estimation utility (`MemoryEstimator.kt`)
- Model catalog system with 137 supported architectures
- Search, filter, sort, and recommendation engine for the catalog
- Cloud AI integration via LiteLLM-compatible providers (Gemini, Claude, GPT, Grok, DeepSeek)
- Encrypted API key storage via Android Keystore AES-256/GCM (`KeyCipher`)
- SSE streaming parser for cloud provider responses with retry policy
- Persistent memory system: SQLite + vector embeddings + hybrid retrieval
- Offline voice assistant pipeline: wake word → ASR → LLM → TTS (all via sherpa-onnx)
- Foreground voice service with system overlay and barge-in detection
- Firebase Authentication: Google Sign-In + GitHub OAuth
- Hilt/Dagger dependency injection across all modules
- Room database v5 with WAL mode, 4 entities, 4 migrations
- DataStore preferences for user settings
- Navigation Compose with 15 routes and deep link support
- Conversation exporter and sharer utilities
- Markdown rendering with syntax-highlighted code blocks
- Developer diagnostics screen with hardware info and performance telemetry
- Test suite: 51 test classes covering ViewModels, repositories, parsers, catalog, and engine

### Changed
- Package namespace migrated from `io.pocketllm.*` to `io.androllm.*` (77 Kotlin files, 16 Gradle modules)
- AGP updated to 8.6.0, Kotlin to 2.1.20, Compose BOM to 2024.10.00
- Build target raised: minSdk 28, compileSdk/targetSdk 34

### Deprecated
- None

### Removed
- None

### Fixed
- Fixed NDK toolchain resolution for Vulkan shader compilation on Windows hosts
- Fixed UTF-16 round-trip encoding for emoji/CJK character handling in JNI bridge
- Fixed context shift corruption edge case when `pos_check >= nCtx - 4`

---

## [1.0.0] — Initial Release

### Added
- Core application scaffolding
- Splash screen, onboarding flow, auth screens
- Home, Chat, Models, Settings, Profile, Prompts, Developer screens
- Basic chat UI with message bubbles and input area
- Room database with Conversation, Message, Model, Settings entities
- Model download infrastructure
- Firebase Authentication integration
- Cloud adaptive navigation (bottom bar / navigation rail)

---

## Version History Notes

| Milestone | Description |
|---|---|
| Phase 1 | App scaffolding, UI, architecture foundation (completed) |
| Phase 2 | llama.cpp engine, Vulkan, GGUF model loading (completed) |
| Phase 3 | Cloud providers, memory system, voice assistant (completed) |
