# AndroLLM Documentation Index

Complete index of all documentation files.

---

## Root Documents

| Document | Description |
|---|---|
| [README.md](../README.md) | Project overview, features, quick start |
| [ARCHITECTURE.md](../ARCHITECTURE.md) | Complete system architecture deep dive |
| [PROJECT_STRUCTURE.md](../PROJECT_STRUCTURE.md) | Module layout and dependency graph |
| [MODEL_SUPPORT.md](../MODEL_SUPPORT.md) | Supported models, formats, quantizations |
| [CONTRIBUTING.md](../CONTRIBUTING.md) | How to contribute code and documentation |
| [BUILDING.md](../BUILDING.md) | Build instructions and environment setup |
| [TESTING.md](../TESTING.md) | Testing strategy, frameworks, conventions |
| [DEVELOPMENT.md](../DEVELOPMENT.md) | Developer workflow and IDE setup |
| [TROUBLESHOOTING.md](../TROUBLESHOOTING.md) | Common issues and solutions |
| [FAQ.md](../FAQ.md) | Frequently asked questions |
| [PERFORMANCE.md](../PERFORMANCE.md) | Performance characteristics and optimization |
| [SECURITY.md](../SECURITY.md) | Security policy and vulnerability reporting |
| [PRIVACY.md](../PRIVACY.md) | Privacy policy and data handling |
| [CHANGELOG.md](../CHANGELOG.md) | Version history and release notes |
| [ROADMAP.md](../ROADMAP.md) | Planned features and development direction |
| [RELEASE_PROCESS.md](../RELEASE_PROCESS.md) | Release build and publishing procedures |
| [LICENSE.md](../LICENSE.md) | Apache License 2.0 |
| [LICENSES.md](../LICENSES.md) | Third-party license summary |
| [CODE_OF_CONDUCT.md](../CODE_OF_CONDUCT.md) | Community guidelines |
| [SUPPORT.md](../SUPPORT.md) | How to get help and report issues |
| [DOCUMENTATION.md](../DOCUMENTATION.md) | Documentation conventions and maintenance |
| [DESIGN.md](../DESIGN.md) | Design system specification |
| [PRODUCT.md](../PRODUCT.md) | Product vision and value proposition |

---

## Deep-Dive Documentation (`docs/`)

### Getting Started

| Document | Description |
|---|---|
| [first-run.md](getting-started/first-run.md) | First-time installation and setup guide |

### Architecture

| Document | Description |
|---|---|
| *(covered in root ARCHITECTURE.md)* | See root for architecture overview |

### Android

| Document | Description |
|---|---|
| [permissions.md](android/permissions.md) | Complete permissions reference and request flow |

### AI Engine

| Document | Description |
|---|---|
| [llama-cpp.md](ai/llama-cpp.md) | llama.cpp integration, JNI bridge, native architecture |
| [gguf.md](ai/gguf.md) | GGUF format specification and validation |
| [vulkan.md](ai/vulkan.md) | Vulkan GPU acceleration and corruption recovery |

### Models

| Document | Description |
|---|---|
| *(covered in root MODEL_SUPPORT.md)* | Model support overview; detailed catalog in source |

### Voice

| Document | Description |
|---|---|
| [voice-assistant.md](voice/voice-assistant.md) | Complete voice pipeline: wake word → ASR → LLM → TTS |

### Cloud

| Document | Description |
|---|---|
| [cloud-providers.md](cloud/cloud-providers.md) | LiteLLM integration, provider management, streaming |

### Memory

| Document | Description |
|---|---|
| [memory-architecture.md](memory/memory-architecture.md) | Embeddings, retrieval, vector index, write pipeline |

### UI

| Document | Description |
|---|---|
| [ui-architecture.md](ui/ui-architecture.md) | Design system, components, theming, responsive layout |
| [chat-architecture.md](ui/chat-architecture.md) | Chat feature: streaming, markdown, state management |

### Backend

| Document | Description |
|---|---|
| [database.md](backend/database.md) | Room schema, DAOs, migrations, repositories |
| [networking.md](backend/networking.md) | HTTP clients, download manager, API integration |
| [firebase-auth.md](backend/firebase-auth.md) | Firebase authentication flow and configuration |

### Security

| Document | Description |
|---|---|
| [security-architecture.md](security/security-architecture.md) | Encryption, threat model, data protection layers |

### Development

| Document | Description |
|---|---|
| [error-handling.md](development/error-handling.md) | Error patterns, Result/UiState, recovery strategies |

---

## Quick Navigation by Topic

| I want to learn about... | Read... |
|---|---|
| What is AndroLLM? | [README.md](../README.md) |
| How does it work? | [ARCHITECTURE.md](../ARCHITECTURE.md) |
| How to build it? | [BUILDING.md](../BUILDING.md) |
| How to contribute? | [CONTRIBUTING.md](../CONTRIBUTING.md) |
| Running models locally? | [MODEL_SUPPORT.md](../MODEL_SUPPORT.md) + [ai/llama-cpp.md](ai/llama-cpp.md) |
| Using Vulkan? | [ai/vulkan.md](ai/vulkan.md) |
| Voice assistant? | [voice/voice-assistant.md](voice/voice-assistant.md) |
| Cloud providers? | [cloud/cloud-providers.md](cloud/cloud-providers.md) |
| Memory system? | [memory/memory-architecture.md](memory/memory-architecture.md) |
| Firebase auth? | [backend/firebase-auth.md](backend/firebase-auth.md) |
| Database schema? | [backend/database.md](backend/database.md) |
| UI design? | [ui/ui-architecture.md](ui/ui-architecture.md) |
| Security? | [SECURITY.md](../SECURITY.md) + [security/security-architecture.md](security/security-architecture.md) |
| Troubleshooting? | [TROUBLESHOOTING.md](../TROUBLESHOOTING.md) |
| Testing? | [TESTING.md](../TESTING.md) |
| Performance? | [PERFORMANCE.md](../PERFORMANCE.md) |
| Roadmap? | [ROADMAP.md](../ROADMAP.md) |
