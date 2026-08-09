# Memory System Architecture

Deep dive into the persistent memory system — how AndroLLM remembers conversations across sessions.

---

## Overview

The memory system gives AndroLLM the ability to retain facts, preferences, and context across conversations. Unlike conversation history (which is linear and grows unbounded), memory is:

- **Structured**: Facts are categorized, tagged, and linked
- **Compressed**: Raw conversations become concise statements
- **Retrievable**: Relevant memories are injected into the system prompt automatically
- **Model-independent**: Memories extracted with one model work with any other model

---

## Architecture Diagram

```
┌─────────────────────────────────────────────────────────────────────┐
│                         MemoryManager                               │
│                         (public API)                                │
└───────────────────────────┬─────────────────────────────────────────┘
                            │
            ┌───────────────┼───────────────┐
            ▼               ▼               ▼
   ┌──────────────┐ ┌──────────────┐ ┌──────────────┐
   │  Intelligence│ │  Embedding   │ │   Context    │
   │    Router    │ │    Router    │ │    Builder   │
   └──────┬───────┘ └──────┬───────┘ └──────┬───────┘
          │                │                │
    ┌─────┴─────┐    ┌─────┴─────┐   ┌─────┴─────┐
    │ Cloud     │    │ Cloud     │   │ Formats   │
    │ Memory    │    │ Embedding │   │ memories   │
    │ Intel.    │    │ Provider  │   │ for system │
    └───────────┘    └─────┬─────┘   │ prompt    │
           │               │          └───────────┘
           │         ┌─────┴─────┐
           │         │ Local     │
           │         │ LLM       │
           │         │ Embedding │
           │         └───────────┘
           │
    ┌──────┴──────┐
    │  Local      │
    │  Memory     │
    │  Intel.     │
    └─────────────┘
          │
          ▼
    ┌─────────────┐
    │ Memory      │
    │ Repository  │
    │ (Room DB)   │
    └──────┬──────┘
           │
     ┌─────┴──────────────────┐
     ▼                         ▼
┌──────────┐          ┌─────────────────┐
│Memories  │          │ CosineVectorIndex│
│Entity    │          │ (in-memory,      │
│+Embed-   │          │  brute-force     │
│dingEntity│          │  cosine search)  │
└──────────┘          └─────────────────┘
```

---

## Write Pipeline

### Step 1: Exchange Processing

When a conversation exchange (user message + assistant response) completes, `MemoryManager.processExchange()` is called after a 2-second delay:

```kotlin
suspend fun processExchange(exchange: ConversationExchange): Result<MemoryWriteSummary>
```

### Step 2: Memory Extraction

The `MemoryIntelligence` router decides whether to extract memories locally or via cloud:

```kotlin
// RoutingMemoryIntelligence
if (!cloudGateway.isConfigured()) return local.extract(exchange, settings)
return cloud.extract(exchange, settings).orElse { local.extract(exchange, settings) }
```

Both paths use identical prompts and JSON schema contracts.

**Extraction prompt** asks the model to identify:
- User preferences (dietary, interests, habits)
- Important facts (names, dates, locations)
- Ongoing projects or goals
- Opinions and beliefs worth remembering

Output format (JSON):
```json
[
  {
    "category": "preference",
    "content": "User is vegetarian",
    "importance": 0.8,
    "tags": ["diet", "lifestyle"],
    "project": null
  }
]
```

### Step 3: Embedding

Each extracted memory is embedded if an embedding provider is available:

```kotlin
// RoutingEmbeddingProvider
if (cloudEmbeddingModel.isNotBlank() && cloudGateway.isConfigured()) {
    val cloudVectors = cloud.embed(texts)
    if (cloudVectors is Result.Success) return cloudVectors
}
return local.embed(texts)  // Llama embedding model
```

**Embedding options:**
- **Cloud**: Via LiteLLM-compatible embedding endpoint (e.g., `text-embedding-3-small`)
- **Local**: Via a separate GGUF embedding model loaded in a dedicated native handle

If neither provider is available, memories are still stored (without vectors) and retrieval falls back to keyword matching.

### Step 4: Deduplication and Merge

Before inserting a new memory:
1. Check similarity threshold against existing memories in the same category
2. If a memory is too similar (cosine similarity ≥ threshold):
   - Merge tags (union)
   - Take maximum importance
   - Update timestamp
3. If exact content match: update timestamp only
4. Otherwise: insert as new memory

### Step 5: Relationship Linking

Newly written memories are linked with `"related_to"` relationships to memories from the same conversation or with overlapping tags.

### Step 6: Summarization

If the conversation exceeds the summarization interval, `ConversationSummarizer` generates a compressed summary:

```kotlin
suspend fun summarize(
    conversationId: String,
    previousSummary: String?,
    recentMessages: List<Message>
): Result<String>
```

Summaries are stored in `SummaryEntity` and used to compress old conversation turns in the context builder.

---

## Retrieval Algorithm

```kotlin
suspend fun retrieve(
    query: String,
    filters: Map<String, String> = emptyMap(),
    topK: Int = 5
): Result<List<MemorySearchResult>>
```

### Steps

1. **Filter candidates**: Get memory IDs matching filters (category, project, tags, pinned, importance)
2. **Keyword search**: SQL LIKE queries on `content` and `tags` columns
3. **Vector search** (if embedding provider available):
   - Embed the query text
   - Brute-force cosine similarity against all indexed vectors
   - Return top K×3 candidates
4. **Hybrid merge**: Combine keyword and vector results with boost (+0.06 for keyword matches)
5. **Final sort**: By score + keyword_boost, then pinned, importance, recency
6. **Access bump**: Update `access_count` and `updated_at` on results

### Fallback Behavior

If embeddings are unavailable:
- Vector search is skipped
- Keyword + recency sorting is used instead
- Results are still useful but less precise

---

## Database Schema

### Main Entities

```sql
-- memories: the core memory records
CREATE TABLE memories (
    id TEXT PRIMARY KEY,           -- UUID
    content TEXT NOT NULL,         -- The extracted fact/preference
    category TEXT,                 -- preference | fact | project | opinion
    importance REAL DEFAULT 0.5,   -- 0.0 to 1.0
    project TEXT,                  -- Associated project name
    tags TEXT DEFAULT '[]',        -- JSON array of strings
    pinned INTEGER DEFAULT 0,      -- Always include in context
    archived INTEGER DEFAULT 0,    -- Hide from retrieval
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    access_count INTEGER DEFAULT 0
);

-- embeddings: vector representations (separate table for BLOB storage)
CREATE TABLE embeddings (
    memory_id TEXT PRIMARY KEY,    -- FK → memories.id
    vector BLOB NOT NULL,          -- FloatArray serialized
    dimension INTEGER NOT NULL,    -- Vector dimension (e.g., 1536, 384)
    FOREIGN KEY (memory_id) REFERENCES memories(id) ON DELETE CASCADE
);

-- summaries: conversation summaries for context compression
CREATE TABLE summaries (
    conversation_id TEXT PRIMARY KEY,
    summary TEXT NOT NULL,
    generated_at INTEGER NOT NULL,
    token_count INTEGER DEFAULT 0
);

-- projects
CREATE TABLE projects (
    id TEXT PRIMARY KEY,
    name TEXT UNIQUE NOT NULL,
    description TEXT,
    created_at INTEGER NOT NULL
);

-- tags
CREATE TABLE tags (
    id TEXT PRIMARY KEY,
    name TEXT UNIQUE NOT NULL
);

-- memory-tag junction
CREATE TABLE memory_tags (
    memory_id TEXT NOT NULL,
    tag_id TEXT NOT NULL,
    PRIMARY KEY (memory_id, tag_id),
    FOREIGN KEY (memory_id) REFERENCES memories(id) ON DELETE CASCADE,
    FOREIGN KEY (tag_id) REFERENCES tags(id) ON DELETE CASCADE
);

-- relationships between memories
CREATE TABLE relationships (
    from_memory_id TEXT NOT NULL,
    to_memory_id TEXT NOT NULL,
    type TEXT DEFAULT 'related_to',
    PRIMARY KEY (from_memory_id, to_memory_id),
    FOREIGN KEY (from_memory_id) REFERENCES memories(id) ON DELETE CASCADE,
    FOREIGN KEY (to_memory_id) REFERENCES memories(id) ON DELETE CASCADE
);
```

The memory database is a **separate Room instance** (`MemoryDatabase`) opened lazily — only when the memory feature is enabled.

---

## Vector Index

**File:** [`core/memory/src/main/java/io/androllm/core/memory/vector/VectorIndex.kt`](../../core/memory/src/main/java/io/androllm/core/memory/vector/VectorIndex.kt)

```kotlin
interface VectorIndex {
    val size: Int           // Number of vectors
    val dimension: Int      // Vector dimensionality
    fun upsert(id: String, vector: FloatArray)
    fun search(query: FloatArray, topK: Int, candidates: Collection<String>?): List<ScoredId>
}

class CosineVectorIndex(override val dimension: Int) : VectorIndex {
    private val vectors = ConcurrentHashMap<String, FloatArray>()
    
    override fun upsert(id: String, vector: FloatArray) {
        vectors[id] = vector  // L2-normalize at write time
    }
    
    override fun search(query: FloatArray, topK: Int, candidates: Collection<String>?): List<ScoredId> {
        // Brute-force dot product against all vectors
        // Filter to candidates if specified
        // Return top-K by similarity score
    }
}
```

**Properties:**
- In-memory only (no disk persistence — rebuilt from database on startup)
- Thread-safe via `ConcurrentHashMap`
- Vectors are L2-normalized at write time
- Search uses dot product (equivalent to cosine similarity on normalized vectors)
- No approximate nearest neighbor (brute-force only) — acceptable for current memory counts (< 10,000)

---

## Context Building

**File:** [`core/memory/src/main/java/io/androllm/core/memory/context/ContextBuilder.kt`](../../core/memory/src/main/java/io/androllm/core/memory/context/ContextBuilder.kt)

Formats retrieved memories for injection into the system prompt:

```kotlin
data class MemoryContext(
    val memories: List<Memory>,
    val summary: String?,
    val formattedContext: String
)

fun buildContext(
    memories: List<Memory>,
    summary: String?,
    settings: MemorySettings
): MemoryContext {
    val parts = mutableListOf<String>()
    
    // Add summary first (compressed conversation history)
    summary?.let { parts.add("Previous conversation summary: $it") }
    
    // Add memories
    memories.forEach { memory ->
        val pinMarker = if (memory.pinned) "[PINNED] " else ""
        parts.add("$pinMarker${memory.content}")
    }
    
    return MemoryContext(memories, summary, parts.joinToString("\n"))
}
```

The formatted context is prepended to the system prompt before chat generation.

---

## Memory Settings

**File:** [`core/memory/src/main/java/io/androllm/core/memory/model/MemorySettings.kt`](../../core/memory/src/main/java/io/androllm/core/memory/model/MemorySettings.kt)

```kotlin
data class MemorySettings(
    val enabled: Boolean = true,              // Master toggle
    val similarityThreshold: Float = 0.85f,   // Deduplication threshold
    val retrievalCount: Int = 5,              // Max memories to retrieve
    val summarizationInterval: Int = 10,      // Messages between summaries
    val cloudEmbeddingModel: String = "",     // Cloud embedding model ID
    val localEmbeddingModelPath: String = "", // Local GGUF embedding model path
    val categories: List<String> = listOf(
        "preference", "fact", "project", "opinion"
    )
)
```

---

## Privacy

### What Stays Local

- All memory content is stored in the local Room database
- Embeddings (vectors) are stored locally in the `embeddings` table
- The in-memory vector index is rebuilt from local data on each app start
- No memory data is transmitted to cloud servers unless explicitly configured

### What Can Go Cloud

- Embedding generation can use a cloud provider (user-configured)
- Memory extraction can use a cloud LLM (user-configured)
- These are optional; the system works fully offline without them

### Deletion

Users can:
- Delete individual memories (pin/unpin, archive)
- Delete all memories in a category
- Delete all memories entirely
- Disable the memory system (stops extraction; existing memories remain)

All deletions are permanent and cannot be undone.

---

## Performance

| Operation | Complexity | Notes |
|---|---|---|
| Upsert memory | O(1) | HashMap insertion |
| Vector search (brute-force) | O(n × d) | n = memories, d = dimension |
| Keyword search | O(n) | SQL LIKE query |
| Hybrid merge | O(n log n) | Sort combined results |
| Context building | O(m) | m = retrieved memories |

For typical usage (< 500 memories), retrieval takes < 10 ms. Performance degrades linearly with memory count — expect ~100 ms at 10,000 memories.

🚧 **Planned**: FAISS or SQLite VEC for approximate nearest neighbor search at scale.

---

## Background Processing

**File:** [`core/memory/src/main/java/io/androllm/core/memory/background/MemoryIndexingWorker.kt`](../../core/memory/src/main/java/io/androllm/core/memory/background/MemoryIndexingWorker.kt)

Run via WorkManager to drain the pending embedding queue:

```kotlin
class MemoryIndexingWorker(
    context: Context, params: WorkerParameters
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        // Process pending embeddings
        // Update vector index
        // Schedule next run if pending items remain
        return Result.success()
    }
}
```

Triggered:
- After each `processExchange()` call
- On app foreground (via `MemoryBackgroundScheduler`)
- Periodically (every 15 minutes when cloud embedding is configured)

---

## Memory Categories

| Category | Description | Example |
|---|---|---|
| `preference` | User tastes, likes, dislikes | "Prefers dark mode", "Allergic to peanuts" |
| `fact` | Factual information about the user | "Born in Tokyo", "Works at Acme Corp" |
| `project` | Active projects and goals | "Building a React app", "Training for marathon" |
| `opinion` | Beliefs and viewpoints | "Thinks UI design is important" |

Categories are used for filtering during retrieval. Users can define custom categories via the memory UI.

---

## Model Independence

A key design principle: **memories are not tied to any specific model**.

```
Extracted with Model A → Stored as plain text → Retrieved and injected → Understood by Model B
```

The memory content is plain natural language text. Any model, regardless of architecture or training data, can understand it when injected into the system prompt. This means:

- You can extract memories with a large cloud model and use them with a small local model
- Memory quality improves as your preferred model improves
- No model-specific formatting required

---

## API Reference

### MemoryManager Interface

```kotlin
interface MemoryManager {
    // Settings
    val settings: Flow<MemorySettings>
    suspend fun updateSettings(transform: (MemorySettings) -> MemorySettings): Result<Unit>
    suspend fun preloadEmbeddingModel(): Result<Unit>
    suspend fun setEmbeddingModelPath(path: String): Result<Unit>
    suspend fun setCloudEmbeddingModel(modelId: String): Result<Unit>
    
    // Retrieval
    suspend fun retrieve(query: String, filters: Map<String, String> = emptyMap(), topK: Int = 5): Result<List<MemorySearchResult>>
    suspend fun buildContext(userQuery: String, filters: Map<String, String>, conversationId: String?, topK: Int): MemoryContext
    
    // Write pipeline
    suspend fun processExchange(exchange: ConversationExchange): Result<MemoryWriteSummary>
    suspend fun saveMemory(category: String, content: String, importance: Float, tags: List<String>, project: String?): Result<MemoryWriteResult>
    
    // CRUD
    fun observeMemories(): Flow<List<Memory>>
    suspend fun pinMemory(id: String, pinned: Boolean): Result<Unit>
    suspend fun archiveMemory(id: String, archived: Boolean): Result<Unit>
    suspend fun deleteMemory(id: String): Result<Unit>
    suspend fun deleteAll(): Result<Unit>
    
    // Background
    suspend fun reindexAll(): Result<Int>
    suspend fun embedPendingMemories(): Result<Int>
    
    // Inspector
    suspend fun getInspectorStats(): MemoryInspectorStats
}
```

---

## Planned Memory Features

| Feature | Status | Notes |
|---|---|---|
| Memory editing UI | 🚧 Planned | Inline editing of memory content |
| Memory tagging UI | 🚧 Planned | Visual tag management |
| FAISS vector search | 🔮 Future | Scale to 100K+ memories |
| Cross-conversation deduplication | 🚧 Planned | Merge duplicate facts across conversations |
| Memory expiration | 🔮 Future | Auto-expire stale memories |
| Memory sentiment analysis | 🔮 Future | Track emotional tone over time |
| Multi-language memory | 🔮 Future | Store memories in user's preferred language |
