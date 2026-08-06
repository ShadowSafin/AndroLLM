package io.androllm.core.memory.model

import io.androllm.core.memory.MemoryCategory

/**
 * A single long-term memory. This is the domain model surfaced to UI and
 * chat integration; embeddings live separately in the vector index.
 */
data class Memory(
    val id: String,
    val category: MemoryCategory = MemoryCategory.CUSTOM,
    val content: String,
    val importance: Int = 1,
    val tags: List<String> = emptyList(),
    val projectId: String? = null,
    val sourceConversationId: String? = null,
    val isPinned: Boolean = false,
    val isArchived: Boolean = false,
    val accessCount: Int = 0,
    val createdAt: Long,
    val updatedAt: Long,
    val lastAccessedAt: Long? = null
)

/**
 * A memory as extracted by the LLM (before persistence). Project and tags are
 * resolved by name during the update pipeline.
 */
data class ExtractedMemory(
    val content: String,
    val category: MemoryCategory = MemoryCategory.CUSTOM,
    val importance: Int = 1,
    val tags: List<String> = emptyList(),
    val projectName: String? = null
)

/**
 * Outcome of processing one extracted memory through the update pipeline.
 */
enum class MemoryWriteAction { INSERTED, UPDATED, SKIPPED }

data class MemoryWriteResult(
    val memoryId: String,
    val action: MemoryWriteAction,
    val similarityScore: Float? = null
)

/**
 * Aggregated outcome of processing one conversation exchange.
 */
data class MemoryWriteSummary(
    val inserted: Int = 0,
    val updated: Int = 0,
    val skipped: Int = 0,
    val extracted: Int = 0,
    val summarized: Boolean = false
)

/**
 * Outcome of an import operation.
 */
data class MemoryImportResult(
    val inserted: Int = 0,
    val updated: Int = 0,
    val skipped: Int = 0
)

/**
 * A rolling conversation summary, stored separately from raw messages so the
 * context builder can prefer summaries over long raw histories.
 */
data class MemorySummary(
    val id: String,
    val conversationId: String,
    val summary: String,
    val messageCount: Int,
    val createdAt: Long,
    val updatedAt: Long
)

/**
 * A named project that groups memories (e.g. "AndroLLM", "Portfolio site").
 */
data class Project(
    val id: String,
    val name: String,
    val description: String = "",
    val createdAt: Long
)

/**
 * A normalized tag attached to memories.
 */
data class Tag(
    val id: String,
    val name: String
)

/**
 * A typed link between two memories.
 */
data class Relationship(
    val id: String,
    val fromMemoryId: String,
    val toMemoryId: String,
    val type: String,
    val createdAt: Long
)

/**
 * Filtering options for memory retrieval.
 */
data class MemorySearchFilters(
    val category: MemoryCategory? = null,
    val projectId: String? = null,
    val pinnedOnly: Boolean = false,
    val tags: Set<String> = emptySet(),
    val minImportance: Int = 0,
    val includeArchived: Boolean = false
)

/**
 * A retrieved memory together with its similarity score (0..1).
 */
data class MemorySearchResult(
    val memory: Memory,
    val score: Float,
    val matchedByKeyword: Boolean = false
)

/**
 * Everything the context builder injects into the system prompt, plus the
 * diagnostics (retrieval time) reported to the memory inspector.
 */
data class MemoryContext(
    val memories: List<MemorySearchResult> = emptyList(),
    val summaries: List<MemorySummary> = emptyList(),
    val retrievalMs: Long = 0L,
    val systemText: String = ""
) {
    val isEmpty: Boolean get() = memories.isEmpty() && summaries.isEmpty()
}

/**
 * A single exchange fed into the extraction pipeline.
 */
data class MemoryExchange(
    val conversationId: String,
    val userMessage: String,
    val assistantResponse: String,
    /** The most recent chat turns (role to content), oldest first. */
    val recentMessages: List<Pair<String, String>> = emptyList(),
    /** Total message count in the conversation (for summarization scheduling). */
    val messageCount: Int = 0
)

/**
 * Snapshot of everything the developer-mode Memory Inspector renders.
 */
data class MemoryInspectorStats(
    val memoryCount: Int = 0,
    val embeddingCount: Int = 0,
    val vectorCount: Int = 0,
    val projectCount: Int = 0,
    val tagCount: Int = 0,
    val summaryCount: Int = 0,
    val relationshipCount: Int = 0,
    val avgRetrievalMs: Long = 0L,
    val lastRetrievalMs: Long = 0L,
    val lastEmbeddingMs: Long = 0L,
    val lastExtractionMs: Long = 0L,
    val totalExtractions: Long = 0L,
    val totalEmbeddings: Long = 0L,
    val totalInserted: Long = 0L,
    val totalUpdated: Long = 0L,
    val retrievalCount: Int = 5,
    val similarityThreshold: Float = 0.78f,
    val embeddingModelPath: String = "",
    /** Cloud embedding model id ("" = none; may coexist with a local path). */
    val cloudEmbeddingModel: String = "",
    val embeddingDimension: Int = 0,
    val embeddingModelLoaded: Boolean = false,
    val enabled: Boolean = false,
    val logs: List<MemoryLogEntry> = emptyList()
)

/**
 * A single memory pipeline log entry (extraction / embedding / retrieval).
 */
data class MemoryLogEntry(
    val timestamp: Long,
    val level: MemoryLogLevel,
    val message: String
)

enum class MemoryLogLevel { DEBUG, INFO, WARN, ERROR }
