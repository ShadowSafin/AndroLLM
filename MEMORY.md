# Memory Quick Guide

Quick reference for the persistent memory system.

---

## What Is Memory?

Memory lets AndroLLM remember facts about you across conversations. When you start a new chat, relevant memories are injected into the system prompt so the model has context about who you are.

---

## Enabling Memory

1. Go to **Settings → On-device Memory**
2. Toggle memory **On**
3. Adjust settings:
   - **Similarity threshold** (0.5–1.0): Lower = more memories retrieved
   - **Retrieval count** (1–20): How many memories to inject
   - **Summarization interval**: How often to summarize conversations

---

## How Memories Are Created

After each conversation exchange, the system:
1. Extracts facts, preferences, and important details
2. Converts them to vector embeddings (optional)
3. Stores them in the local database
4. Links related memories together

Example extractions from "I'm vegetarian and I live in Tokyo":
- `category: preference` — "User is vegetarian"
- `category: fact` — "User lives in Tokyo"

---

## How Memories Are Used

When you start a new conversation:
1. Your message is searched against stored memories
2. Relevant memories are formatted into a context block
3. The context block is prepended to the system prompt
4. The model responds with awareness of your memories

---

## Managing Memories

| Action | How |
|---|---|
| View all memories | Settings → On-device Memory → View memories |
| Pin a memory | Tap memory → Pin (always included in context) |
| Archive a memory | Tap memory → Archive (excluded from retrieval) |
| Delete a memory | Tap memory → Delete |
| Delete all memories | Settings → On-device Memory → Delete all |
| Export memories | Settings → On-device Memory → Export |

---

## Privacy

- All memories are stored **locally** in SQLite
- Vectors are computed on-device (or via your configured cloud provider)
- No memory data is transmitted unless you explicitly use cloud features
- Delete all memories at any time

---

## Model Independence

Memories work with any model. A fact extracted by a large cloud model can be used by a small local model — the text is plain natural language, not model-specific.

---

## See Also

- [Memory Architecture](docs/memory/memory-architecture.md) — Full technical deep dive
- [README](README.md) — Feature overview
