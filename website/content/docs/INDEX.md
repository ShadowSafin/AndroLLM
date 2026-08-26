# AndroLLM Documentation Index

Complete index of all documentation files.

---

## Root Documents

Files that must stay at the repository root (repo-level policies and pointers).

| Document | Description |
|---|---|
| [README.md](../README.md) | Project overview, features, quick start |
| [CONTRIBUTING.md](../CONTRIBUTING.md) | How to contribute code and documentation |
| [SECURITY.md](../SECURITY.md) | Security policy and vulnerability reporting |
| [PRIVACY.md](../PRIVACY.md) | Privacy policy and data handling |
| [LICENSE.md](../LICENSE.md) | Apache License 2.0 |
| [LICENSES.md](../LICENSES.md) | Third-party license summary |
| [CODE_OF_CONDUCT.md](../CODE_OF_CONDUCT.md) | Community guidelines |
| [SUPPORT.md](../SUPPORT.md) | How to get help and report issues |

---

## Project Documentation

Deep technical documentation lives in this `documentation/` folder.

| Document | Description |
|---|---|
| [ARCHITECTURE.md](ARCHITECTURE.md) | Complete system architecture deep dive |
| [PROJECT_STRUCTURE.md](PROJECT_STRUCTURE.md) | Module layout and dependency graph |
| [MODEL_SUPPORT.md](MODEL_SUPPORT.md) | Supported models, formats, quantizations |
| [BUILDING.md](BUILDING.md) | Build instructions and environment setup |
| [TESTING.md](TESTING.md) | Testing strategy, frameworks, conventions |
| [DEVELOPMENT.md](DEVELOPMENT.md) | Developer workflow and IDE setup |
| [TROUBLESHOOTING.md](TROUBLESHOOTING.md) | Common issues and solutions |
| [FAQ.md](FAQ.md) | Frequently asked questions |
| [PERFORMANCE.md](PERFORMANCE.md) | Performance characteristics and optimization |
| [CHANGELOG.md](CHANGELOG.md) | Version history and release notes |
| [ROADMAP.md](ROADMAP.md) | Planned features and development direction |
| [RELEASE_PROCESS.md](RELEASE_PROCESS.md) | Release build and publishing procedures |
| [DOCUMENTATION.md](DOCUMENTATION.md) | Documentation conventions and maintenance |
| [DESIGN.md](DESIGN.md) | Design system specification |
| [PRODUCT.md](PRODUCT.md) | Product vision and value proposition |

---

## Deep-Dive Documentation

### Getting Started

| Document | Description |
|---|---|
| [first-run.md](getting-started/first-run.md) | First-time installation and setup guide |

### Architecture

| Document | Description |
|---|---|
| *(covered in ARCHITECTURE.md)* | See the architecture deep dive |

### Android

| Document | Description |
|---|---|
| [permissions.md](android/permissions.md) | Complete permissions reference and request flow |

### AI Engine

| Document | Description |
|---|---|
| [litert-lm.md](ai/litert-lm.md) | LiteRT-LM engine integration: runtime, compat layer, tool calling |
| [model-formats.md](ai/model-formats.md) | Model formats: `.litertlm` containers, GGUF legacy, `.tflite` |
| [acceleration.md](ai/acceleration.md) | CPU (XNNPACK) and GPU (LiteRT GPU delegate) acceleration |
| [LITERT.md](LITERT.md) | LiteRT / LiteRT-LM quick reference (versions, APIs, Maven artifacts) |

### Models

| Document | Description |
|---|---|
| *(covered in MODEL_SUPPORT.md)* | Model support overview; detailed catalog in source |

### Voice

| Document | Description |
|---|---|
| [voice-assistant.md](voice/voice-assistant.md) | Complete voice pipeline: wake word → ASR → LLM → TTS |
| [text-normalization.md](voice/text-normalization.md) | TTS text normalization pipeline and OOV spelling |

### Cloud

| Document | Description |
|---|---|
| [cloud-providers.md](cloud/cloud-providers.md) | LiteLLM integration, provider management, streaming |
| [cloud-pipeline.md](cloud/cloud-pipeline.md) | Cloud request pipeline: tool calling, usage dashboard, prompt caching, fallback chain |

### Memory

| Document | Description |
|---|---|
| [memory-architecture.md](memory/memory-architecture.md) | Embeddings, retrieval, vector index, write pipeline, production hardening |
| [hardening.md](memory/hardening.md) | Production hardening: deterministic dedupe, confidence, merging, conflict resolution, thread-safety, startup validation |
| [chat-attachments.md](features/chat-attachments.md) | Conversation-scoped file attachments for cloud models: formats, workflow, settings, privacy |

### AI Agent & Automation

| Document | Description |
|---|---|
| [agent-platform.md](agent/agent-platform.md) | AI agent architecture, planning, safety gates, chat/voice integration |
| [tools.md](agent/tools.md) | Complete reference of every built-in tool, permission and category |
| [workflow-engine.md](agent/workflow-engine.md) | Multi-step execution, variables, conditionals, confirmations, retry |
| [mcp.md](agent/mcp.md) | MCP server integration: connect external tools via Streamable HTTP |
| [accessibility-automation.md](agent/accessibility-automation.md) | Accessibility-driven UI automation, gestures, planners, safety |

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
| How does it work? | [ARCHITECTURE.md](ARCHITECTURE.md) |
| How to build it? | [BUILDING.md](BUILDING.md) |
| How to contribute? | [CONTRIBUTING.md](../CONTRIBUTING.md) |
| Running models locally? | [MODEL_SUPPORT.md](MODEL_SUPPORT.md) + [ai/litert-lm.md](ai/litert-lm.md) |
| Model formats? | [ai/model-formats.md](ai/model-formats.md) |
| CPU/GPU acceleration? | [ai/acceleration.md](ai/acceleration.md) |
| Voice assistant? | [voice/voice-assistant.md](voice/voice-assistant.md) |
| Voice text normalization? | [voice/text-normalization.md](voice/text-normalization.md) |
| AI agent / tool calling? | [agent/agent-platform.md](agent/agent-platform.md) |
| All built-in tools? | [agent/tools.md](agent/tools.md) |
| Multi-step workflows? | [agent/workflow-engine.md](agent/workflow-engine.md) |
| MCP servers? | [agent/mcp.md](agent/mcp.md) |
| Controlling other apps? | [agent/accessibility-automation.md](agent/accessibility-automation.md) |
| Cloud providers? | [cloud/cloud-providers.md](cloud/cloud-providers.md) |
| Cloud tools, usage & caching? | [cloud/cloud-pipeline.md](cloud/cloud-pipeline.md) |
| Memory system? | [memory/memory-architecture.md](memory/memory-architecture.md) |
| Memory hardening? | [memory/hardening.md](memory/hardening.md) |
| Firebase auth? | [backend/firebase-auth.md](backend/firebase-auth.md) |
| Database schema? | [backend/database.md](backend/database.md) |
| UI design? | [ui/ui-architecture.md](ui/ui-architecture.md) |
| Security? | [SECURITY.md](../SECURITY.md) + [security/security-architecture.md](security/security-architecture.md) |
| Troubleshooting? | [TROUBLESHOOTING.md](TROUBLESHOOTING.md) |
| Testing? | [TESTING.md](TESTING.md) |
| Performance? | [PERFORMANCE.md](PERFORMANCE.md) |
| Roadmap? | [ROADMAP.md](ROADMAP.md) |
