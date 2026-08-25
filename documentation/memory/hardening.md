# Memory Production Hardening

Deep dive into the production-grade hardening that makes the memory system reliable for thousands of memories, concurrent writes, and long-term consistency.

---

## Goals

- **Never duplicate** the same fact (deterministic)
- **Only meaningful** long-term information is stored (no temporary context)
- **Confidence-gated** commits (≥0.55, ≥0.62 for `CUSTOM` low)
- **Merge** similar, **update** superseded, **resolve** conflicts via evidence
- **Relevance-first** retrieval (semantic dominates, recency only tie-breaks)
- **No pollution** (capped, relevance-filtered, contradiction-free context)
- **Forget correctly** (deleted never reappears via embeddings/cache/index)
- **Thread-safe, transactional, recoverable** and **performant** at scale

All hardening preserves the public `MemoryManager` API and `memory.db` schema (v3).

---

## Deterministic Creation

- **Normalization**: `lowercase + whitespace collapsed + punctuation stripped` (`hardeningHelper.normalizeForDedupe`) — `User prefers dark mode` and `user prefers  dark  mode.` are identical
- **Serialized writes**: `writeMutex` serializes `writeMemory` (insert vs update vs merge); `indexMutex` for vector index, `cacheMutex` for LRU
- **Exact + near-duplicate**: embedding `score ≥ threshold (0.78)` → update; `≥ threshold-0.08` → merge (≤280 chars); else word-overlap Jaccard ≥0.5 for `PREFERENCES/IDENTITY/PROJECTS` → update

Result: same fact from two exchanges, two threads, or two devices yields **exactly one** row.

---

## Extraction Filtering (Only Meaningful Long-Term)

`ExtractionPrompts.SYSTEM_INSTRUCTION` + `MemoryHardeningHelper` + `MemorySecurityFilter`:

- **Ignored**: greetings (`hi`, `thanks`), `just for now`/`one-off`/`temporary`/`right now`/`in this session only`, short-lived preferences (`use X for now`), `explain X`/`summarize this` one-offs, debugging noise, `>800` chars, `<12` chars, secrets (`sk-***`, `ghp_***`, `password=***`), injection (`ignore previous instructions`, `<tool_call>`, `system:`), hallucinations (not grounded ≥30% words), PII (SSN, credit card)
- **Stored**: Identity, Preferences, Prompt memory (`copyable code blocks`, `short prompts`), Projects (`Remember this project context`), Goals, Skills, PINNED_FACTS, Developer notes — all **grounded** in the exchange (`isGroundedInExchange` checks ≥30% significant words overlap; manual `saveMemory` with empty exchange is considered grounded)

---

## Confidence Scoring

`hardeningHelper.confidenceScore(ExtractedMemory, exchange)` → `0.0..1.0`:

- Length 15–140 → +0.1..0.2, <15 → -0.3, >280 → -0.1
- Category `PREFERENCES/IDENTITY/PINNED_FACTS` → +0.15, `CUSTOM` → -0.05
- Importance `(importance-3)*0.07`
- Grounded → +0.1 else -0.35
- Temporary → -0.4
- Project/tag → +0.05/0.03

`shouldCommit` threshold `0.55` ( `0.62` for `CUSTOM` low) — low-confidence is `SKIPPED` before any DB write, never persisted.

---

## Merging Similar

- **Embedding near-threshold** (`threshold-0.08 ≤ score < threshold`): `updateExisting(mergeContent=true)` concatenates `"${existing} ${new}".take(280)` (word-boundary)
- **Word-overlap** for `PREFERENCES` etc.: Jaccard ≥0.5 or overlap≥60% with ≥2 significant words → update, not duplicate

---

## Intelligent Update and Supersession

`updateExisting` when `findExactDuplicate` (normalized or Jaccard) finds an existing row:

- **Tags**: union, distinct, capped 10
- **Priority**: `max(existing.priority, existing.importance, new.importance)`
- **Content**: if `mergeContent` and different → merged ≤280; else if different (e.g., `dark` → `light`) → **new supersedes old** (new content replaces old); else keep existing
- **Re-embed**: on content change, `embedForWrite(newContent)` → `embeddingDao.upsert` + `vectorIndex.upsert` (dimension guard, recreated if mismatched); if no embedding, old vector removed (no stale search)
- **Cache**: `retrievalCache.clear()` (ensures next `retrieve` sees new)

---

## Conflict Resolution

`hardeningHelper.isContradictory(a,b)` checks opposite preferences (`prefers dark` vs `light`, `likes` vs `dislikes`) with ≥1 shared significant word, or same 4-word prefix with opposite suffix (`short` vs `long`).

`resolveConflict(newContent, newPriority, newTimestamp, existing: Memory)`:

- Existing newer + higher priority → keep existing
- New newer + ≥ priority → new wins (`return null` → caller updates)
- Else `newScore = priority*0.6 + lengthBonus`, `existingScore = priority*0.6 + 0.1` → higher wins

Used **before** `updateExisting` (embedding and exact paths) and **during** `retrieve`/`ContextBuilder` (pairwise among candidates) — inconsistent entries never both injected, never both returned.

---

## Retrieval Ranking (Relevance vs Recency Separated)

```kotlin
// 1. Validated, thresholded, contradiction-resolved candidates
// 2. Sorted:
compareByDescending { score + HYBRID_KEYWORD_BOOST(0.06) } // semantic relevance dominates
  .thenByDescending { isPinned }                           // pinned first
  .thenByDescending { effectivePriority }                  // 1..5
  .thenByDescending { updatedAt }                          // recency ONLY as tie-breaker
```

- `recencyBoost` (`0.08` max) is **not** mixed into `score`; recent irrelevant (low score, not pinned, not keyword) is always after relevant old, never dominates
- `keywordFallback` when no vector: `keywordMatch` → `isPinned` → `priority` → `updatedAt + recencyBoost*1_000_000` (tie-breaker)

---

## Context Injection (No Pollution)

`ContextBuilder.buildSystemText(memories, summaries, maxMemories, maxSummaries)`:

- Filters `!isArchived`, `expiryAt` valid, `content` not blank/≤800, **relevance** (`isPinned || matchedByKeyword || score≥0.3`) — otherwise dropped (prevents pollution)
- Contradiction-resolved (same helper) among `memList`
- Sorted `pinned > score > priority`, capped `maxMemories` coerced `0..5` (hardened from 8) and total `take(3000)` chars
- Summaries capped `0..2`
- Output: `Relevant memories (use only if relevant): - [CATEGORY/TYPE] content (pinned)` + `Conversation summaries: - summary` — never mentions mechanics, never overrides system rules

---

## Validation of Retrieved Memories Still Valid

Each `retrieve` candidate is checked before use:

- `content.isBlank() || length>2000` → skip + warn, counted as corrupted
- `MemoryCategory.fromName` / `MemoryType.fromName` try/catch → skip, repaired at startup
- `entity.toDomain` try/catch → skip malformed
- `expiryAt != null && expiryAt < now` → skip (double-check after `candidateIds`)
- `isArchived` already filtered

---

## Deletion and Forgetting (Never Reappear)

`deleteMemory(id)` **transactionally** (`writeMutex`):

- `memoryDao.deleteById` + `embeddingDao.deleteByMemoryId` + `tagDao.deleteCrossRefsForMemory` + `vectorIndex.remove(id)` + `retrievalCache.clear()` + log (sanitized)

`deleteAll()` transactional: all tables + `vectorIndex.clear()` + `retrievalCache.clear()` + `logger.clear()`

`candidateIds` and `getMemoryIdsWithoutEmbeddings` exclude deleted; `retrieve` never returns deleted via `getFilteredIds`; stale embeddings for missing `memoryId` are purged at startup.

---

## Embedding Generation Consistency

- `withPassagePrefix(content, settings)` and `queryPrefix` applied identically for `embedForWrite` and `embedQuery`; `embeddingSourceLabel` (`cloud:model` or `local path`) tags `embedding_entity.model_path` for staleness detection
- `embedPendingMemories` (missing vectors, chunked 8) and `reindexAll` (chunked 16) use same prefix logic; on `updateExisting` with new content, re-embed is triggered automatically
- On `settings` change (`embeddingModelPath`/`cloudEmbeddingModel`), `updateSettings` unloads old model; next `embed` loads new one
- Dimension mismatch → `CosineVectorIndex` recreated, `reindexAll` can be triggered

---

## Corrupted / Missing Embeddings Auto-Repair

`ensureStartupValidated` (lazy, `startupMutex` once):

- Scans `memoryDao.getAll()` for blank/too-long, invalid `category/type` → repair (`CUSTOM`/`LONG_TERM`) or `deleteById`
- Scans `embeddingDao.getAll()` for orphaned (`memoryId` not in `memoryIds`), `NaN`/`Infinite`, empty, dimension mismatch → `deleteByMemoryId` + `vectorIndex.remove`
- Clears `retrievalCache`

`reindexAll` and `embedPendingMemories` also handle per-chunk `getOrNull` (skip failed chunks, log).

---

## Vector Search Stability

`CosineVectorIndex`:

- Guards `id.isBlank()`, `vector.isEmpty()`, `NaN`/`Infinite`, `dimension` mismatch (throws for direct `upsert`, silently skipped in `upsertAll` batch)
- `VectorMath.normalize` guards `mag==0`, `magnitude` guards, `cosine` maps `-1..1` → `0..1`
- `search` guards `topK≤0`, `vectors.isEmpty()`, `query.isEmpty()`, `NaN`, `normalize` try/catch, skips corrupted vectors, uses `candidateSet` for >100 candidates, stable sort `score desc, id asc`, caps `topK` to 1000, try/catch returns empty on exception

---

## Indexing Performance for Large Collections

- **Chunked**: `reindexAll` 16, `embedPendingMemories` 8, `ensureVectorIndexLocked` rebuilds once (dimension check)
- **In-memory brute-force** `O(n×d)` is <10 ms for <500, <50 ms for 5000 (768-dim); acceptable for mobile (<10K)
- **WAL** (`RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING`) so `processExchange` writes never block `retrieve` readers
- **Background**: `MemoryIndexingWorker` (WorkManager, 15 min) + foreground `MemoryBackgroundScheduler` drain pending queue
- **Caching**: `retrievalCache` LRU 64, validated on hit, `clear()` on writes/deletes

---

## Thread-Safety and Race Conditions

- `writeMutex` (writes: `writeMemory`, `deleteMemory`, `deleteAll`, `updateImportance`, etc.)
- `indexMutex` (vector index `upsert`/`remove`/`search` rebuild)
- `cacheMutex` (`retrievalCache` get/put/clear)
- `CopyOnWriteArrayList` logs, `ConcurrentHashMap` vectors
- All `MemoryDao`/`EmbeddingDao` calls are suspending Room transactions; `processExchange` loop is `writeMutex`-serialized, so simultaneous `processExchange` from two conversations never interleave and never create duplicates

---

## Transactional Consistency and Error Recovery

- `writeMemory` insert: `try { memoryDao.upsert + tag + embedding + index } catch { deleteById + deleteCrossRefs + deleteByMemoryId + index.remove; return SKIPPED }` — **automatic rollback** for partial failures, never corrupts DB
- `updateExisting`: try/catch around `memoryDao.update` + tag merge + re-embed; on catch, log and `SKIPPED`
- `retrieve` / `getFilteredIds` etc. wrapped in `runCatching` + `getOrDefault(emptyList())`
- `embedPendingMemories` / `reindexAll` per-chunk `getOrNull` → skip failed chunk, log, continue
- `deleteMemory` / `deleteAll` individual try/catch per DAO, logged

Failed writes never leave dangling rows, embeddings, or index entries.

---

## Startup Initialization

`ensureStartupValidated` is called lazily on first `retrieve` (and `processExchange` via `retrieve`), `getMemories`, `buildContext`:

- Validates `memory.db` integrity (blank, too-long, invalid category/type)
- Validates embedding consistency (orphaned, corrupted, dimension mismatch)
- Clears stale cache
- `startupMutex` ensures once per process, `startupValidated` volatile flag

`MemoryDatabase.getInstance` uses `Room.databaseBuilder` with `fallbackToDestructiveMigration` and explicit `MIGRATION_1_2, MIGRATION_2_3`.

---

## Malformed / Invalid Records and Safeguards

- `MemorySecurityFilter.validate` rejects `empty`, `too long (>800)`, `secrets`, `injection`, `low-value (<12)`, `not grounded`, `PII` — never persisted
- `ExtractionJsonParser` lenient: tolerates fences, truncated JSON, casing variants, never throws; `distinctBy content.lowercase()`
- `VectorMath` guards `mag==0`, `NaN`, `Infinite`, empty
- `MemoryEntity.toDomain` try/catch in `retrieve`/`getMemories`; `getTagsForMemoryIds` with `orEmpty`
- `priority` fallback: `if (priority==1 && importance!=1) importance else priority`
- `lastUsedAt` nullable, `expiryAt` nullable, `chatId` nullable

---

## Performance for Thousands

See table in `memory-architecture.md` (Performance): <10 ms for 500, <50 ms for 5000, validated via `MemoryRepositoryTest` with 1000+ mocks, `CosineVectorIndexTest` with 10K vectors.

---

## Logging (Useful, Never Sensitive)

`MemoryLogger` (`CopyOnWriteArrayList`, 200 entries, `DEBUG/INFO/WARN/ERROR`):

- `sanitizeForLog`: masks `sk-***`, `ghp_***`, `password=***`, truncates 400; used for every `log` call
- `MemoryRepository` logs `creation (Inserted memory $id category=... confidence=...)`, `retrieval (retrievalMs, candidate counts)`, `updating/merging (score)`, `forgetting (Deleted memory $id and purged embeddings/cache)`, `failures (Insert failed, rolled back, Re-embed failed)` — all with preview `take(40)` not full content, sanitized

Inspector exposes `getInspectorStats` (`memoryCount, embeddingCount, vectorCount, projectCount, tagCount, summaryCount, relationshipCount, avgRetrievalMs, lastEmbeddingMs, totalInserted/Updated, logs`).

---

## Tests

`core/memory/src/test/java/io/androllm/core/memory/hardening/MemorySystemHardeningTest.kt` (30 tests):

- Useful vs low-value, secrets, injection, raw logs, hallucination
- Retrieval relevance vs recency, pinned ranking, compact injection
- Deduplication (exact, similar merge), near-duplicate `PREFERENCES` Jaccard
- Expiry, pinned, prompt memory, project classification
- Security, grounding, system-override
- Context injection order, storage spec fields, summarization fields, concise quality

Plus `MemoryRepositoryTest`, `CosineVectorIndexTest`, `VectorMathTest`, `ExtractionJsonParserTest`, `ContextBuilderTest`, `MigrationTest`, `RoutingMemoryIntelligenceTest` — concurrent, corrupted data, conflicting, merging, forgetting, ranking, embedding regeneration, recovery all covered.

Run:

```bash
./gradlew :core:memory:testDebugUnitTest --rerun-tasks
./gradlew :core:memory:connectedAndroidTest --tests "*MigrationTest*"
```

---

## Future Hardening

- FAISS / SQLite VEC for 100K+ scale
- Confidence UI badge in Inspector
- Contradiction UI highlighting
- Cross-device sync deduplication
