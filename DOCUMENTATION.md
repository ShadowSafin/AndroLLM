# Documentation Guide

How to maintain and extend the AndroLLM documentation.

---

## Documentation Structure

```
AndroLLM/
├── README.md                      # Project overview and quick start
├── ARCHITECTURE.md                # System architecture deep dive
├── PROJECT_STRUCTURE.md           # Module layout and dependencies
├── CONTRIBUTING.md                # How to contribute
├── BUILDING.md                    # Build instructions
├── TESTING.md                     # Testing guide
├── DEVELOPMENT.md                 # Developer workflow
├── TROUBLESHOOTING.md             # Common issues and solutions
├── FAQ.md                         # Frequently asked questions
├── PERFORMANCE.md                 # Performance considerations
├── MODEL_SUPPORT.md               # Model format and compatibility
├── SECURITY.md                    # Security policy
├── PRIVACY.md                     # Privacy policy
├── CHANGELOG.md                   # Version history
├── ROADMAP.md                     # Development roadmap
├── RELEASE_PROCESS.md             # Release procedures
├── LICENSE.md                     # Apache 2.0 license
├── LICENSES.md                    # Third-party license summary
├── CODE_OF_CONDUCT.md             # Community guidelines
├── SUPPORT.md                     # How to get help
├── DESIGN.md                      # Design system spec
├── PRODUCT.md                     # Product vision
├── docs/                          # Detailed technical documentation
│   ├── getting-started/
│   │   ├── building.md            # (symlink to BUILDING.md)
│   │   └── first-run.md           # First-time setup guide
│   ├── architecture/
│   │   ├── android.md             # Android-specific architecture
│   │   └── ai-engine.md           # AI engine architecture
│   ├── android/
│   │   ├── permissions.md         # Permission reference
│   │   └── lifecycle.md           # Activity/service lifecycle
│   ├── ai/
│   │   ├── llama-cpp.md           # llama.cpp integration
│   │   ├── gguf.md                # GGUF format guide
│   │   └── vulkan.md              # Vulkan acceleration
│   ├── models/
│   │   ├── catalog.md             # Model catalog system
│   │   └── downloading.md         # Model download process
│   ├── voice/
│   │   ├── voice-assistant.md     # Voice pipeline deep dive
│   │   └── sherpa-onnx.md         # sherpa-onnx integration
│   ├── cloud/
│   │   ├── cloud-providers.md     # Cloud provider architecture
│   │   └── litellm.md             # LiteLLM integration details
│   ├── memory/
│   │   ├── memory-architecture.md # Memory system deep dive
│   │   └── embeddings.md          # Embedding implementation
│   ├── ui/
│   │   ├── ui-architecture.md     # UI design system
│   │   └── chat-architecture.md   # Chat feature deep dive
│   ├── backend/
│   │   ├── database.md            # Room database schema
│   │   ├── networking.md          # HTTP client architecture
│   │   └── firebase-auth.md       # Firebase auth details
│   ├── security/
│   │   └── security-architecture.md # Security layers
│   ├── development/
│   │   └── error-handling.md      # Error handling patterns
│   └── troubleshooting/
│       └── troubleshooting.md     # (symlink to TROUBLESHOOTING.md)
└── reference/
    ├── api-reference.md           # Public API reference
    └── constants.md               # AppConstants reference
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
See also [docs/ai/llama-cpp.md](docs/ai/llama-cpp.md).
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
