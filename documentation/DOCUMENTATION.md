# Documentation Guide

How to maintain and extend the AndroLLM documentation.

---

## Documentation Structure

```
AndroLLM/
├── README.md                      # Project overview and quick start (repo root)
├── CONTRIBUTING.md                # How to contribute (repo root)
├── SECURITY.md                    # Security policy (repo root)
├── PRIVACY.md                     # Privacy policy (repo root)
├── LICENSE.md                     # Apache 2.0 license (repo root)
├── LICENSES.md                    # Third-party license summary (repo root)
├── CODE_OF_CONDUCT.md             # Community guidelines (repo root)
├── SUPPORT.md                     # How to get help (repo root)
└── documentation/                 # All technical documentation lives here
    ├── INDEX.md                   # Complete documentation index
    ├── ARCHITECTURE.md            # System architecture deep dive
    ├── PROJECT_STRUCTURE.md       # Module layout and dependencies
    ├── BUILDING.md                # Build instructions
    ├── TESTING.md                 # Testing guide
    ├── DEVELOPMENT.md             # Developer workflow
    ├── TROUBLESHOOTING.md         # Common issues and solutions
    ├── FAQ.md                     # Frequently asked questions
    ├── PERFORMANCE.md             # Performance considerations
    ├── MODEL_SUPPORT.md           # Model format and compatibility
    ├── CHANGELOG.md               # Version history
    ├── ROADMAP.md                 # Development roadmap
    ├── RELEASE_PROCESS.md         # Release procedures
    ├── DESIGN.md                  # Design system spec
    ├── PRODUCT.md                 # Product vision
    ├── DOCUMENTATION.md           # This documentation guide
    ├── MEMORY.md                  # Memory system overview
    ├── VOICE_ASSISTANT.md         # Voice assistant overview
    ├── CLOUD_PROVIDERS.md         # Cloud provider overview
    ├── VULKAN.md                  # Vulkan overview
    ├── LLAMA_CPP.md               # llama.cpp overview
    ├── FIREBASE_AUTH.md           # Firebase auth overview
    ├── ANDROLLM_REBRAND_SUMMARY.md
    ├── getting-started/
    │   └── first-run.md           # First-time setup guide
    ├── agent/
    │   ├── agent-platform.md      # AI agent architecture & safety gates
    │   ├── tools.md               # Built-in tool catalog
    │   ├── workflow-engine.md     # Multi-step execution & variables
    │   ├── mcp.md                 # MCP server integration
    │   └── accessibility-automation.md
    ├── ai/
    │   ├── llama-cpp.md           # llama.cpp integration
    │   ├── gguf.md                # GGUF format guide
    │   └── vulkan.md              # Vulkan acceleration
    ├── voice/
    │   ├── voice-assistant.md     # Voice pipeline deep dive
    │   └── text-normalization.md  # TTS normalization & OOV spelling
    ├── cloud/
    │   └── cloud-providers.md     # Cloud provider architecture
    ├── memory/
    │   └── memory-architecture.md # Memory system deep dive
    ├── ui/
    │   ├── ui-architecture.md     # UI design system
    │   └── chat-architecture.md   # Chat feature deep dive
    ├── backend/
    │   ├── database.md            # Room database schema
    │   ├── networking.md          # HTTP client architecture
    │   └── firebase-auth.md       # Firebase auth details
    ├── android/
    │   └── permissions.md         # Permissions reference
    ├── security/
    │   └── security-architecture.md # Security layers
    ├── development/
    │   └── error-handling.md      # Error handling patterns
    └── src/                       # Kotlin source (docs Gradle module)
```

---

## Writing Conventions

### Headings

Use consistent heading hierarchy:
- `#` for document title (only one per file)
- `##` for major sections
- `###` for subsections
- `####` for sub-subsections (rarely needed)

### Code Blocks

Always specify the language:
````markdown
```kotlin
// Good
val list = mutableListOf<String>()
```

    ```  // Bad — no language specified
```

For Kotlin code, use descriptive variable names and follow project conventions.

### Cross-References

Link to related documents using relative paths:

```markdown
See [Building Guide](BUILDING.md) for full instructions.
See also [ai/llama-cpp.md](ai/llama-cpp.md).
```

Link to source files using relative paths from the doc location:

```markdown
Implementation: [`LlamaCppEngine.kt`](engine/src/main/java/io/androllm/engine/llama/LlamaCppEngine.kt)
```

### Tables

Use tables for structured comparisons:

| Column 1 | Column 2 | Column 3 |
|---|---|---|
| Data | Data | Data |

Keep columns narrow. Use abbreviation columns for compact tables.

### Status Indicators

Use emoji indicators consistently:

| Emoji | Meaning |
|---|---|
| ✅ | Implemented |
| 🚧 | In progress / planned |
| 🔮 | Future / research |
| ⚠️ | Warning / caution |
| ❌ | Not supported / deprecated |
| ℹ️ | Informational note |

### Callouts

Use blockquotes for special notes:

```markdown
> **Warning:** This operation deletes all local data permanently.

> **Note:** The Vulkan backend requires a host Vulkan SDK for building.

> **Tip:** Use the benchmark tool in Developer settings to measure your device.
```

---

## Documentation Audit Checklist

Before submitting documentation changes:

- [ ] All file paths in cross-references exist
- [ ] All code snippets compile (check imports and types)
- [ ] All external links are to current official documentation
- [ ] No imaginary features are documented as implemented
- [ ] Planned features are clearly marked with 🚧 or 🔮
- [ ] Terminology is consistent with the codebase
- [ ] Architecture diagrams match the actual code
- [ ] Badge claims are verifiable (no fake star counts, etc.)
- [ ] Build commands are verified against the actual repository
- [ ] No secrets, API keys, or passwords are included

---

## Adding a New Document

1. Determine the appropriate directory based on topic
2. Follow the naming convention: lowercase with hyphens (e.g., `my-topic.md`)
3. Start with a one-sentence description
4. Use the heading hierarchy from this guide
5. Add a cross-reference from the parent document
6. Update this file's table of contents

---

## Maintaining Existing Documents

Documentation should be updated when:
- A public API changes
- A new feature is added
- A bug fix changes behavior
- A dependency is upgraded
- User feedback reveals confusion

Schedule a documentation review with each release.
