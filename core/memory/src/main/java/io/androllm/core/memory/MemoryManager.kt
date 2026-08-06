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
 * subsystem. The app (chat, settings, developer) depends ONLY on this
 * interface — nothing here knows about a chat model, an embedding model or a
 * provider. Memory is an application capability; inference providers and
 * embedding engines are interchangeable implementations behind it.
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

    /** Memories + summaries formatted for injection into the system prompt. */
    suspend fun buildContext(
        userQuery: String,
        filters: MemorySearchFilters = MemorySearchFilters(),
        conversationId: String? = null,
        topK: Int? = null
    ): MemoryContext

    // ── Write pipeline ──

    /** Post-response pipeline: extract → (embed) → update-or-insert → summarize. */
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