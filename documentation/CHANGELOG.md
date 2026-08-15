# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [Unreleased]

### Added
- Modular multi-module Gradle project with 33 modules
- Jetpack Compose Material 3 UI with "Parchment Ledger" design system
- **LiteRT-LM engine migration**: full on-device inference via Google's
  LiteRT-LM runtime (`litertlm-android:0.16.0`) — pure Kotlin/Java, no native code
- `.litertlm` model container format with embedded LlmMetadata proto
- Raw LiteRT `CompiledModel` API (`litert:2.2.0`) for on-device embeddings
  (EmbeddingGemma 300M, 768-dim, SentencePiece tokenizer)
- GPU acceleration via the LiteRT GPU delegate with automatic CPU fallback
- Corruption recovery system: output coherence probe + recovery counters
- Multi-turn chat via LiteRT-LM `Conversation` (chat templates applied per family)
- Context-overflow recovery: automatic conversation reseed on
  "Input token ids are too long"
- Native tool calling: `<|tool_call|>` markers for Qwen/Gemma families
  (`NativeToolCallScanner`, up to 3 tool rounds) with JSON-compat `planLocal`
  fallback
- Model catalog rebuilt for LiteRT: 7 models (6 `.litertlm` + 1 `.tflite`),
  `litert-community` sources on HuggingFace / ModelScope
- HuggingFace browser now filters to `litertlm` artifacts
- Memory estimation + resource guard (RAM-aware load refusal)
- Container metadata inspection for model detail screens
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
- Test suite: ~62 test classes covering ViewModels, repositories, parsers, catalog, and engine

### Changed
- Package namespace migrated from `io.pocketllm.*` to `io.androllm.*` (77 Kotlin files, 16 Gradle modules)
- AGP updated to 8.6.0, Kotlin to 2.1.20, Compose BOM to 2024.10.00
- Build target raised: minSdk 28, compileSdk/targetSdk 35
- **Inference runtime migrated from vendored llama.cpp + JNI to LiteRT-LM**:
  - Removed native engine (`engine/src/main/cpp/` deleted, ~3700-line JNI bridge gone)
  - Removed Vulkan backend; replaced by LiteRT GPU delegate (CPU/GPU only)
  - Model format: GGUF → `.litertlm` containers
  - GGUF catalog (101 models, 137 architectures) → LiteRT catalog (7 models)
  - `GgufValidator` removed; replaced by `LiteRtValidator` + `ContainerMetadataReader`
  - Legacy `BackendType` values (QUALCOMM_QNN, LLAMA_CPP_VULKAN, ONNX_RUNTIME, VULKAN)
    kept only for serializer/UI compatibility — never produced by the engine
- Context length is now detected from container metadata at load time
  (Qwen2.5-1.5B → 4096; Qwen3-0.6B → 2048)

### Deprecated
- None

### Removed
- On-device image generation (stable-diffusion.cpp)

### Fixed
- Fixed UTF-16 round-trip encoding for emoji/CJK character handling in JNI bridge (legacy, removed)
- Fixed context shift corruption edge case (superseded by LiteRT-LM conversation reseeding)

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
| Phase 2 | LiteRT-LM engine, .litertlm catalog, CPU/GPU acceleration (completed) |
| Phase 3 | Cloud providers, memory system, voice assistant (completed) |
