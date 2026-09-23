package io.androllm.core.memory

import io.androllm.core.common.Result
import io.androllm.core.memory.model.ExtractedMemory
import io.androllm.core.memory.model.Memory
import io.androllm.core.memory.model.MemoryContext
import io.androllm.core.memory.model.MemoryExchange
import io.androllm.core.memory.model.MemoryImportResult
import io.androllm.core.memory.model.MemoryInspectorStats
import io.androllm.core.memory.model.MemoryLogEntry
import io.androllm.core.memory.model.MemorySearchFilters
import io.androllm.core.memory.model.MemorySearchResult
import io.androllm.core.memory.model.MemorySettings
import io.androllm.core.memory.model.MemoryWriteResult
import io.androllm.core.memory.model.MemoryWriteSummary
import io.androllm.core.memory.model.MemorySummary
import io.androllm.core.memory.model.Project
import java.io.File
import kotlinx.coroutines.flow.Flow

/**
 * The single, provider-independent public surface of the persistent memory
 * subsystem — the unified memory layer every model adapter calls.
 *
 * Local LiteRT generation ([ChatViewModel] local path), cloud generation
 * ([ChatViewModel] cloud path via [CloudGateway], [ChatManager] voice path
 * for both providers) all use the same three calls:
 * - [buildContext] before generation (retrieve + inject),
 * - [processExchange] after the response (extract + persist/update),
 * - [retrieve] for direct lookups.
 *
 * Nothing here knows about a chat model, an embedding model, or a provider.
 * Memory is an application capability: facts are stored as plain natural
 * language, so a memory written while chatting with one model is retrieved
 * and injected identically for any other model, across sessions and restarts.
 * All calls are best-effort — a memory failure never breaks chat.
 */
interface MemoryManager {

    // ── Settings ──

    val settings: Flow<MemorySettings>
    suspend fun currentSettings(): MemorySettings
    suspend fun updateSettings(transform: (MemorySettings) -> MemorySettings): Result<Unit>
    suspend fun preloadEmbeddingModel(): Result<Unit>
    suspend fun setEmbeddingModelPath(path: String): Result<Unit>
    /** Selects the cloud embedding model id ("" clears it, reverting to local). */
    suspend fun setCloudEmbeddingModel(modelId: String): Result<Unit>

    // ── Retrieval ──

    /** Top-K relevant memories; semantic when embeddings exist, keyword/recent otherwise. */
    suspend fun retrieve(
        query: String,
        filters: MemorySearchFilters = MemorySearchFilters(),
        topK: Int? = null
    ): Result<List<MemorySearchResult>>

    /**
     * Memories + summaries formatted for injection into the system prompt.
     * Call before EVERY generation (local and cloud) with the latest user
     * message; inject [MemoryContext.systemText] as a `system` message ahead
     * of history. Never throws — returns empty context when memory is
     * disabled, empty, or unavailable so chat continues normally.
     */
    suspend fun buildContext(
        userQuery: String,
        filters: MemorySearchFilters = MemorySearchFilters(),
        conversationId: String? = null,
        topK: Int? = null
    ): MemoryContext

    /**
     * Compact variant for token-constrained cloud models: identical retrieval,
     * but [MemoryContext.systemText] is compressed to [maxChars].
     * Local models use the full block; small-context cloud models pass ~1500.
     */
    suspend fun buildCompactContext(
        userQuery: String,
        filters: MemorySearchFilters = MemorySearchFilters(),
        conversationId: String? = null,
        topK: Int? = null,
        maxChars: Int = 1500
    ): MemoryContext {
        val full = buildContext(userQuery, filters, conversationId, topK)
        if (full.systemText.length <= maxChars) return full
        return full.copy(systemText = full.systemText.take(maxChars))
    }

    // ── Write pipeline ──

    /**
     * Post-response pipeline: extract → (embed) → update-or-insert → summarize.
     * Call after EVERY response (local and cloud) with the finished exchange.
     * Persists preferences, stable facts, project context, decisions, and
     * tasks; ignores noise/filler; updates (never duplicates) on correction.
     */
    suspend fun processExchange(exchange: MemoryExchange): Result<MemoryWriteSummary>

    /** Manually adds a memory (settings UI / pinning flows). */
    suspend fun saveMemory(
        category: MemoryCategory,
        content: String,
        importance: Int = 1,
        tags: List<String> = emptyList(),
        projectName: String? = null
    ): Result<MemoryWriteResult>

    // ── CRUD ──

    fun observeMemories(): Flow<List<Memory>>
    suspend fun getMemories(): List<Memory>
    suspend fun getMemory(id: String): Memory?
    fun observeProjects(): Flow<List<Project>>
    suspend fun pinMemory(id: String, pinned: Boolean): Result<Unit>
    suspend fun archiveMemory(id: String, archived: Boolean): Result<Unit>
    suspend fun deleteMemory(id: String): Result<Unit>
    suspend fun updateImportance(id: String, importance: Int): Result<Unit>
    suspend fun deleteAll(): Result<Unit>
    suspend fun deleteSummariesForConversation(conversationId: String): Result<Unit>

    // ── Export / Import ──

    suspend fun exportMemories(): Result<File>
    suspend fun importMemories(file: File): Result<MemoryImportResult>

    // ── Background indexing ──

    /** Re-embeds every memory with the current embedding source. */
    suspend fun reindexAll(): Result<Int>

    /** Embeds memories currently missing vectors (the pending queue). */
    suspend fun embedPendingMemories(): Result<Int>

    // ── Inspector ──

    suspend fun getInspectorStats(): MemoryInspectorStats
    fun observeInspectorLogs(): Flow<List<MemoryLogEntry>>
}