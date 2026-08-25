# Memory Quick Guide

Quick reference for the production-hardened persistent memory system.

---

## What Is Memory?

Memory lets AndroLLM remember durable facts about you across conversations — preferences, projects, and pinned facts — not temporary chat context. When you start a new chat, only the most relevant memories are injected into the system prompt, never the whole store.

Hardening ensures: no duplicates for the same fact, only meaningful long-term information is stored, temporary requests are ignored, and retrieval is relevance-first (not recency).

---

## Enabling Memory

1. Go to **Settings → On-device Memory**
2. Toggle memory **On** (default **Off** for privacy)
3. Adjust settings (all validated, persisted via DataStore):
   - **Similarity threshold** `0.5–0.99` (default `0.78`): higher = stricter dedupe
   - **Retrieval count** `1–20` (default `5`): how many memories to retrieve per prompt
   - **Max context memories** `0–8` (default `5`): how many are actually injected (relevance-filtered, contradiction-resolved, capped `3000` chars)
   - **Max context summaries** `0–2` (default `2`): conversation summaries injected
   - **Summarization interval** `4–100` (default `20`): messages between summaries
   - **Embedding model**: local `.tflite` (EmbeddingGemma 300M) or cloud `text-embedding-3-small` (optional)

---

## How Memories Are Created (Hardened)

After each exchange (user + assistant, 2-second delay, cancels if new turn starts):

1. **Extract** via `MemoryIntelligence` (local or cloud, same prompt/schema): `content` (≤25 words, present tense, third person), `category` (`PREFERENCES`, `IDENTITY`, `PROJECTS`, `PINNED_FACTS`…), `importance` 1–5, `tags`, `project`
   - Ignored: greetings, `just for now`/`one-off`/`temporary`, short-lived preferences, secrets, injection, hallucinations (not grounded ≥30% words), low-value (<12 chars)
2. **Confidence gate** (`0.0–1.0`): length, category, importance, grounding, temporary penalty; `<0.55` (or `<0.62` for `CUSTOM` low) → `SKIPPED` (never persisted)
3. **Security filter** (`MemorySecurityFilter`): secrets (`sk-***`, `ghp_***`, `password=***`), injection, PII, `too long` (>800)
4. **Deterministic dedupe** (`writeMutex` serialized, transactional with rollback):
   - Embedding cosine `≥ threshold` → `UPDATED` (merge tags, max priority, re-embed)
   - Near-threshold `≥ threshold-0.08` → `UPDATED` with merged content (≤280)
   - Exact normalized (`lowercase + whitespace + punctuation stripped`) or near-duplicate (Jaccard ≥0.5) → `UPDATED` (new supersedes old via conflict resolver)
   - Else `INSERTED` (new UUID, `userId= default`, `type` via `MemoryClassifier`, `expiryAt` per type, tags/projects resolved, embedding upserted, index `upsert`, cache cleared)
5. **Conflict resolution**: contradictory memories (`prefers dark` vs `light`) resolved by `timestamp + priority + evidence`; loser `SKIPPED`, winner kept
6. **Linking**: `related_to` for same-exchange memories
7. **Summarization**: every `summarizationInterval` messages, `ConversationSummarizer` stores `SummaryEntity`

All writes are **thread-safe** (`writeMutex`), **transactional** (partial failures rolled back, never corrupt DB), and **logged** without sensitive data (sanitized, truncated 400).

---

## How Memories Are Used

When you start a new conversation:

1. **Validation** (`ensureStartupValidated` once): purged expired, removed blank/too-long, repaired invalid `category/type`, removed orphaned/corrupted embeddings (`NaN`, dimension mismatch), cleared stale cache
2. **Candidate filtering** (`getFilteredIds`): `category, project, pinnedOnly, includeArchived, minImportance/priority, tags (union), type, chatId, userId, includeExpired, now` — expiry-aware
3. **Keyword search** (`searchContentIds` + `searchTagIds`) → `keywordIds`
4. **Vector search** (if embedding available): `embedQuery` → cosine `search(query, k*4, candidates)` → `ScoredId`
5. **Validation** per candidate: not blank, category/type valid, not expired, not malformed (`try toDomain`)
6. **Threshold** (`score ≥ 0.78` or `pinned` or `keyword`) and **contradiction filtering** (keep higher priority/newer)
7. **Ranking** (separate semantic vs recency): `score + 0.06 keyword` → `isPinned` → `effectivePriority` → `updatedAt` (recency only as tie-breaker, so recent irrelevant never dominates)
8. **Cache** (LRU 64, key `query|filters|k`, validated on hit, cleared on writes/deletes)
9. **Access bump** (`bumpAccess` + `lastUsedAt`) for retrieved
10. **Context building** (`ContextBuilder.buildSystemText`): relevance-filtered (`score≥0.3` or pinned/keyword), contradiction-resolved, sorted `pinned > score > priority`, capped `maxMemories 5` and `3000` chars, never dumps unrelated context, never overrides system rules

Retrieval is <10 ms for <500 memories, <50 ms for 5000 (brute-force `O(n×d)`, `ConcurrentHashMap`, WAL, `Dispatchers.IO`, chunked).

---

## Managing Memories

| Action | How | Hardening |
|---|---|---|
| View all | **Settings → On-device Memory → View memories** (`observeMemories` pinned-first) | Validated, not corrupted |
| Pin | Tap → Pin (always included, ranked first) | `isPinned` survives dedupe |
| Archive | Tap → Archive (excluded from retrieval) | `includeArchived=false` by default |
| Delete | Tap → Delete | Transactionally: `memory + embedding + tags + index + cache` cleared; cannot reappear via stale embedding |
| Delete all | **Settings → Delete all** | Transactional: all tables + `vectorIndex.clear()` + `retrievalCache.clear()` + `logger.clear()` |
| Export | **Settings → Export** (`memory_exports/androllm_memory_*.json`, version 1) | Includes `isPinned/isArchived/createdAt/updatedAt` |
| Import | **Settings → Import** | Deduplicates via same deterministic pipeline, re-embeds lazily |
| Disable | Toggle **Off** | `processExchange` no-ops, existing memories retained |

---

## Privacy

- All content in `memory.db` (Room, WAL, separate instance, lazy-open)
- Vectors in `embedding_entity` (`BLOB` LittleEndian, `model_path` for staleness)
- In-memory index rebuilt from local data on start
- No transmission unless cloud extraction/embedding explicitly configured (user opt-in)
- Logs sanitized (`sk-***`, `ghp_***`, `password=***`, truncated 400) — never sensitive, useful for debugging (`creation, retrieval, update, merge, forgetting, failures`)
- Delete at any time, permanent, cannot reappear (excluded from embeddings, caches, retrieval)

---

## Model Independence

Plain natural language, not model-specific: *Extracted with Model A → Retrieved → Understood by Model B*.

---

## Performance & Scale

- **Upsert**: `O(1)` HashMap + `writeMutex`
- **Search**: `O(n×d)` brute-force, `n` pre-filtered by SQL, chunked (16/8), cached
- **Context**: `O(m)` `m≤5`

For thousands, retrieval stays <50 ms; embedding generation chunked, `keepEmbeddingModelLoaded` true by default.

---

## Troubleshooting

- **No memories retrieved**: check `similarityThreshold` (lower to 0.6), `retrievalCount`, embedding model loaded (`Inspector → embeddingModelLoaded`)
- **Corrupted embeddings**: `ensureStartupValidated` auto-repairs on next retrieval; or **Reindex All** in Inspector
- **Duplicate memories**: hardened deterministic dedupe should prevent; if seen, check `Inspector → logs` for `Merging similar` vs `Inserted`
- **Forgot memories reappear**: ensure `deleteMemory` completed (check `vectorCount` in Inspector decreases)

---

## See Also

- [Memory Architecture](memory/memory-architecture.md) — Full hardened deep dive (write pipeline, retrieval, hardening table, API reference)
- [README](../README.md) — Feature overview
