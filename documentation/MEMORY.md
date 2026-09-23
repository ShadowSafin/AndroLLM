# Memory Quick Guide

Quick reference for the shared persistent memory system — one memory layer
for local and cloud models.

---

## What Is Memory?

Memory lets AndroLLM remember durable facts about you across conversations
and sessions — preferences, identity, projects, decisions, tasks — not
temporary chat context. Before every response, the latest user message is
used to retrieve the most relevant memories, which are injected as a `system`
block ahead of history. Only the top relevant items are injected, never the
whole store.

**Shared across models:** memory is stored as plain natural language in the
local `memory.db`, independent of any chat model. A fact stored while
chatting with a cloud model is retrieved identically for a local model and
vice versa — switching models never loses memory. Text chat
(`ChatViewModel`) and voice chat (`ChatManager`) call the same
`MemoryManager.buildContext` / `processExchange`, so both paths behave the
same.

Hardening ensures: no duplicates for the same fact, only meaningful
long-term information is stored, temporary requests are ignored, corrections
update stale facts, and retrieval is relevance-first (semantic dominates;
recency/importance only re-order relevant hits).

---

## Enabling Memory

1. Go to **Settings → On-device Memory**
2. Toggle memory **On** (default **Off** for privacy)
3. Adjust settings (all validated, persisted via DataStore):
   - **Similarity threshold** `0.5–0.99` (default `0.78`): higher = stricter dedupe
   - **Retrieval count** `1–20` (default `5`): how many memories to retrieve per prompt
   - **Max context memories** `0–8` (default `5`): how many are actually injected (ranked, contradiction-resolved, capped `3000` chars)
   - **Max context summaries** `0–2` (default `2`): conversation summaries injected
   - **Summarization interval** `4–100` (default `20`): messages between summaries
   - **Embedding model**: local `.tflite` (EmbeddingGemma 300M) or cloud `text-embedding-3-small` (optional; keyword fallback works with neither)

---

## What Gets Stored (Write Policy)

`MemoryWritePolicy` gates every write, identically for local extraction,
cloud extraction, and manual saves:

- **STORE:** preferences, stable identity facts, named project context,
  goals, decisions, ongoing tasks, skills, devices, pinned facts, explicit
  "remember this" requests.
- **UPDATE (not duplicate):** user corrections ("actually…", "I meant…",
  "change X to Y", "my X is now Y") overwrite the stale fact via conflict
  resolution (newer + higher-priority wins).
- **IGNORE:** greetings/filler ("hi", "thanks"), one-off requests ("explain
  X", "summarize this"), temporary scope ("just for now", "for this chat
  only"), secrets/tokens, injection, raw logs, vague low-value statements.

After the policy, the existing confidence gate (`≥0.55`, `≥0.62` for weak
`CUSTOM`), security filter, and deterministic dedupe (embedding threshold +
normalized comparison + word overlap) still apply.

---

## When Retrieval Happens (Read Path)

Before **every** generation — local and cloud — the app:

1. Takes the latest user message as the query (empty → skip).
2. Calls `MemoryManager.buildContext` with a 1500ms budget (text chat) or
   1000ms (voice); on timeout/failure returns empty so the turn still sends.
3. Injects `systemText` as the first `system` message, ahead of attachments,
   tool advertisement, and history — identically for LiteRT prompts and
   OpenAI-compatible cloud requests.
4. Cloud turns use the provider context window (32k) for history trimming so
   cloud history is never over-trimmed by the local container size; small-
   context cloud models can request `buildCompactContext(maxChars)` instead
   of the full 3000-char block.

Retrieval covers direct facts, project context, long-term preferences, open
tasks, and prior decisions via the same hybrid index. If the store is empty
or unreachable, chat continues normally without memory.

---

## How Ranking Works

`MemoryRanker` scores every candidate deterministically:

- Semantic similarity × 1.0 (dominant) + keyword boost +0.06
- Pinned +0.08, `PREFERENCES`/`PINNED_FACTS` +0.04
- Priority +0.02 per point, recency +0.00–0.08 (30-day decay, tie-breaker)
- Weak-memory decay −0.10 for old (30d+), rarely used, low-priority items

If the strict similarity threshold would drop everything while semantic
candidates exist, a rescue pass keeps the best above 0.30 so important facts
survive vocabulary mismatch. Contradictory pairs are resolved before ranking
(newer + higher-priority wins), covering preference flips *and* name /
location / value corrections.

---

## How Memories Are Created (Pipeline)

After each exchange (user + assistant, 2-second settle delay, cancelled if a
new turn starts so the UI never stalls):

1. **Extract** via `MemoryIntelligence` — cloud provider when cloud mode is
   configured (same prompt/schema as local), local model otherwise with cloud
   fallback. Output: `content` (≤25 words, present tense, third person),
   `category`, `importance` 1–5, `tags`, `project`.
2. **Write policy** (`MemoryWritePolicy`): persist / update / ignore (see above).
3. **Security filter** (`MemorySecurityFilter`): secrets, injection, PII, `too long` (>800).
4. **Confidence gate** (`0.0–1.0`): length, category, importance, grounding
   (≥30% words in exchange), temporary penalty; below threshold → `SKIPPED`.
5. **Deterministic dedupe** (`writeMutex` serialized, transactional):
   embedding cosine `≥ threshold` → `UPDATED`; near-threshold → merged
   (≤280 chars); exact/near-duplicate text → `UPDATED` (new supersedes old).
6. **Conflict resolution**: contradictory memories resolved by timestamp +
   priority + evidence; loser `SKIPPED`, winner kept.
7. **Summarization**: every `summarizationInterval` messages, rolling summary
   stored per conversation for long-chat continuity.

All writes are thread-safe, transactional (partial failures roll back), and
logged without sensitive data (sanitized, truncated 400).

---

## Managing Memories

| Action | How |
|---|---|
| View all | **Settings → On-device Memory → View memories** (pinned-first) |
| Pin | Tap → Pin (always included, ranked first) |
| Archive | Tap → Archive (excluded from retrieval) |
| Delete | Tap → Delete (memory + embedding + tags + index + cache cleared) |
| Delete all | **Settings → Delete all** (all tables + index + cache + logs) |
| Export | **Settings → Export** (`memory_exports/androllm_memory_*.json`, version 1) |
| Import | **Settings → Import** (deduplicates via same pipeline, re-embeds lazily) |
| Disable | Toggle **Off** (`processExchange` no-ops, existing memories retained) |

---

## Privacy

- All content in `memory.db` (Room, WAL, separate instance, lazy-open).
- Vectors in `embedding_entity` (`BLOB` LittleEndian, `model_path` for staleness).
- In-memory index rebuilt from local data on start.
- No transmission unless cloud extraction/embedding explicitly configured
  (user opt-in by enabling cloud mode / setting a cloud embedding model).
- Logs sanitized (`sk-***`, `ghp_***`, `password=***`, truncated 400).
- Delete at any time, permanent.

---

## Model Independence

Plain natural language, not model-specific: *stored via Model A → retrieved
→ injected → understood by Model B*. Switching between local LiteRT and any
cloud provider (Gemini, Claude, GPT, Grok, custom LiteLLM) preserves the same
memory store, ranking, and injection format.

---

## Troubleshooting

- **No memories retrieved**: check `similarityThreshold` (lower to 0.6),
  `retrievalCount`, embedding model loaded (`Inspector → embeddingModelLoaded`).
  The rescue pass (≥0.30) should still surface strong semantic hits.
- **Cloud forgets but local remembers (or vice versa)**: both paths now share
  `buildContext` — check logcat `MEMORY CONTEXT` lines on both paths and
  confirm memory is enabled (voice low-latency mode intentionally skips it).
- **Duplicate memories**: dedupe + conflict resolution should prevent; check
  `Inspector → logs` for `Merging similar` vs `Inserted`.
- **Correction didn't stick**: corrections need shared subject words ("my name
  is X" → "actually my name is Y"); check logs for `Correction signal`.
- **Forgot memories reappear**: ensure `deleteMemory` completed (check
  `vectorCount` in Inspector decreases).

---

## See Also

- [Memory Architecture](memory/memory-architecture.md) — Full deep dive (write pipeline, retrieval, ranking, API reference)
- [README](../README.md) — Feature overview
