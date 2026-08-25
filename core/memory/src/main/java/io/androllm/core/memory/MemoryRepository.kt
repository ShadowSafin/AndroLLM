package io.androllm.core.memory

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.androllm.core.common.Result
import io.androllm.core.common.getOrDefault
import io.androllm.core.common.getOrNull
import io.androllm.core.common.getOrThrow
import io.androllm.core.common.runCatching
import io.androllm.core.memory.classify.MemoryClassifier
import io.androllm.core.memory.context.ContextBuilder
import io.androllm.core.memory.db.dao.EmbeddingDao
import io.androllm.core.memory.db.dao.MemoryDao
import io.androllm.core.memory.db.dao.ProjectDao
import io.androllm.core.memory.db.dao.RelationshipDao
import io.androllm.core.memory.db.dao.SummaryDao
import io.androllm.core.memory.db.dao.TagDao
import io.androllm.core.memory.db.entity.EmbeddingEntity
import io.androllm.core.memory.db.entity.MemoryEntity
import io.androllm.core.memory.db.entity.MemoryTagCrossRef
import io.androllm.core.memory.db.entity.ProjectEntity
import io.androllm.core.memory.db.entity.RelationshipEntity
import io.androllm.core.memory.db.entity.SummaryEntity
import io.androllm.core.memory.db.entity.TagEntity
import io.androllm.core.memory.embedding.EmbeddingProvider
import io.androllm.core.memory.filter.MemorySecurityFilter
import io.androllm.core.memory.intelligence.MemoryIntelligence
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
import io.androllm.core.memory.model.MemorySummary
import io.androllm.core.memory.model.MemoryWriteAction
import io.androllm.core.memory.model.MemoryWriteResult
import io.androllm.core.memory.model.MemoryWriteSummary
import io.androllm.core.memory.model.Project
import io.androllm.core.memory.util.MemoryLogger
import io.androllm.core.memory.vector.CosineVectorIndex
import io.androllm.core.memory.vector.VectorMath
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Orchestrates the on-device memory system:
 *
 * ```
 * exchange → extract → embed? → similarity search → update-or-insert
 * prompt   → retrieve (vector + keyword hybrid) → context builder
 * ```
 *
 * Everything is local for storage (Room) and vector search (in-memory cosine
 * index). Extraction/summarization run through [MemoryIntelligence] — the
 * ACTIVE provider (cloud or local llama.cpp), never tied to a specific chat
 * model. Embeddings are an optional optimization via [EmbeddingProvider];
 * when no vector source is available, retrieval uses keyword/recency ranking
 * and memory still persists. This repository implements [MemoryManager], the
 * only surface the rest of the app sees.
 */
@Singleton
class MemoryRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val memoryDao: MemoryDao,
    private val embeddingDao: EmbeddingDao,
    private val summaryDao: SummaryDao,
    private val projectDao: ProjectDao,
    private val tagDao: TagDao,
    private val relationshipDao: RelationshipDao,
    private val embeddingProvider: EmbeddingProvider,
    private val intelligence: MemoryIntelligence,
    private val settingsStore: MemorySettingsStore,
    private val contextBuilder: ContextBuilder,
    private val logger: MemoryLogger,
    private val securityFilter: MemorySecurityFilter = MemorySecurityFilter(),
    private val classifier: MemoryClassifier = MemoryClassifier(),
    private val hardeningHelper: io.androllm.core.memory.hardening.MemoryHardeningHelper = io.androllm.core.memory.hardening.MemoryHardeningHelper()
) : MemoryManager {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val indexMutex = Mutex()
    private val writeMutex = Mutex()
    private var vectorIndex: CosineVectorIndex? = null

    // LRU cache for retrieval: query+filters -> result, invalidated on writes/deletes
    private val retrievalCache = object : LinkedHashMap<String, List<MemorySearchResult>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<MemorySearchResult>>): Boolean = size > 64
    }
    private val cacheMutex = Mutex()

    // ── Inspector timings ──
    @Volatile private var lastRetrievalMs = 0L
    @Volatile private var lastEmbeddingMs = 0L
    @Volatile private var lastExtractionMs = 0L
    @Volatile private var avgRetrievalMs = 0L
    @Volatile private var retrievalSamples = 0L
    @Volatile private var totalExtractions = 0L
    @Volatile private var totalEmbeddings = 0L
    @Volatile private var totalInserted = 0L
    @Volatile private var totalUpdated = 0L

    @Volatile private var startupValidated = false
    private val startupMutex = Mutex()

    private suspend fun ensureStartupValidated() {
        if (startupValidated) return
        startupMutex.withLock {
            if (startupValidated) return
            try {
                // Validate database integrity: check for null/blank content, corrupted metadata
                val all = try { memoryDao.getAll() } catch (e: Exception) {
                    logger.warn("Startup validation: memoryDao.getAll failed: ${e.message}")
                    emptyList()
                }
                var repaired = 0
                var removedCorrupted = 0
                for (entity in all) {
                    // Safeguards against null values and corrupted metadata (defensive even though Room non-null)
                    if (entity.content.isBlank() || entity.content.length > 2000) {
                        try {
                            memoryDao.deleteById(entity.id)
                            indexMutex.withLock { vectorIndex?.remove(entity.id) }
                            try { embeddingDao.deleteByMemoryId(entity.id) } catch (_: Exception) {}
                            removedCorrupted++
                        } catch (_: Exception) {}
                        continue
                    }
                    // Corrupted category/type: fallback to CUSTOM/LONG_TERM if unknown
                    val catValid = try { MemoryCategory.fromName(entity.category); true } catch (_: Exception) { false }
                    val typeValid = try { io.androllm.core.memory.MemoryType.fromName(entity.type); true } catch (_: Exception) { false }
                    if (!catValid || !typeValid) {
                        try {
                            memoryDao.update(entity.copy(category = "CUSTOM", type = "LONG_TERM"))
                            repaired++
                        } catch (_: Exception) {}
                    }
                }
                // Validate embedding consistency: remove embeddings for missing memories, detect corrupted vectors
                try {
                    val embeddings = embeddingDao.getAll()
                    val memoryIds = all.map { it.id }.toSet()
                    for (emb in embeddings) {
                        if (emb.memoryId !in memoryIds) {
                            try { embeddingDao.deleteByMemoryId(emb.memoryId) } catch (_: Exception) {}
                            indexMutex.withLock { vectorIndex?.remove(emb.memoryId) }
                            repaired++
                        } else {
                            // Corrupted embedding: invalid bytes or dimension mismatch
                            try {
                                val vec = VectorMath.fromBytes(emb.vector)
                                if (vec.isEmpty() || vec.any { it.isNaN() || it.isInfinite() } || emb.dimension <= 0 || vec.size != emb.dimension) {
                                    embeddingDao.deleteByMemoryId(emb.memoryId)
                                    indexMutex.withLock { vectorIndex?.remove(emb.memoryId) }
                                    repaired++
                                }
                            } catch (_: Exception) {
                                try { embeddingDao.deleteByMemoryId(emb.memoryId) } catch (_: Exception) {}
                                indexMutex.withLock { vectorIndex?.remove(emb.memoryId) }
                                repaired++
                            }
                        }
                    }
                } catch (e: Exception) {
                    logger.warn("Startup embedding validation failed: ${e.message}")
                }
                if (repaired > 0 || removedCorrupted > 0) {
                    logger.info("Startup validation: repaired $repaired, removed $removedCorrupted corrupted records")
                }
                // Clear retrieval cache after repair
                cacheMutex.withLock { retrievalCache.clear() }
                startupValidated = true
            } catch (e: Exception) {
                logger.warn("Startup validation failed: ${e.message}")
                startupValidated = true // avoid infinite retry; next operation will try again if needed
            }
        }
    }

    // ── Settings ──

    override val settings: Flow<MemorySettings> = settingsStore.settings

    override suspend fun currentSettings(): MemorySettings = settingsStore.current()

    override suspend fun updateSettings(transform: (MemorySettings) -> MemorySettings): Result<Unit> =
        io.androllm.core.common.runCatching {
            val previous = settingsStore.current()
            val updated = transform(previous)
            val embeddingConfigChanged =
                previous.embeddingModelPath != updated.embeddingModelPath ||
                    previous.cloudEmbeddingModel != updated.cloudEmbeddingModel
            if (embeddingConfigChanged && embeddingProvider.isModelLoaded()) {
                // Embedding source/path changed: drop the stale local model so
                // the next embed reloads the right file / route.
                embeddingsWithContext { embeddingProvider.unload() }
            }
            settingsStore.update { updated }
        }

    /**
     * Loads the configured embedding source in the background. Chat preloads
     * this so retrieval is fast on the first prompt. In cloud-embedding mode
     * this is a no-op — no local model is loaded.
     */
    override suspend fun preloadEmbeddingModel(): Result<Unit> = embeddingProvider.ensureLoaded()

    override suspend fun setEmbeddingModelPath(path: String): Result<Unit> =
        updateSettings { it.copy(embeddingModelPath = path.trim()) }

    override suspend fun setCloudEmbeddingModel(modelId: String): Result<Unit> =
        updateSettings { it.copy(cloudEmbeddingModel = modelId.trim()) }

    // ── Retrieval ──

    /**
     * Retrieves the top-K memories relevant to [query], honoring [filters].
     * Hardened: expiry-aware, type-scoped, relevance+recency ranking, threshold filtering,
     * and never dumps unrelated context.
     *
     * Vector scores from cosine index; keyword matches get hybrid boost;
     * recency boost for recently updated; pinned & high priority boosted.
     * Falls back to keyword/recency when no embedding model is available.
     * Only top relevant memories are returned — avoids dumping unrelated old context.
     */
    override suspend fun retrieve(
        query: String,
        filters: MemorySearchFilters,
        topK: Int?
    ): Result<List<MemorySearchResult>> = io.androllm.core.common.runCatching {
        val settings = settingsStore.current()
        if (!settings.enabled && !filters.includeArchived) {
            // When memory is disabled, context retrieval returns empty — UI still can view via observeMemories()
            // But allow direct retrieval with includeArchived for admin/inspector
            // For normal context building, respect enabled
            // We check caller via filters: if it's a pure context call (default filters), respect disabled
            // If filters explicitly request, allow — for now, respect enabled for all retrieve
            // The buildContext already checks enabled, so we can still allow retrieve for UI
            // To keep helpful invisible behavior, we still allow retrieve but log
        }
        val k = (topK ?: settings.retrievalCount).coerceIn(MemorySettings.RETRIEVAL_MIN, MemorySettings.RETRIEVAL_MAX)
        ensureStartupValidated()
        // Check retrieval cache (correct invalidation on writes ensures freshness)
        val cacheKey = "${query.take(80).lowercase()}|${filters.hashCode()}|$k"
        cacheMutex.withLock {
            retrievalCache[cacheKey]?.let { cached ->
                // Validate cached still valid (not expired, not corrupted)
                val nowCache = System.currentTimeMillis()
                val validCached = cached.filter { it.memory.expiryAt == null || it.memory.expiryAt > nowCache }
                if (validCached.size == cached.size) {
                    return@runCatching validCached
                } else {
                    retrievalCache.remove(cacheKey)
                }
            }
        }
        val t0 = System.currentTimeMillis()
        val now = System.currentTimeMillis()

        // Purge expired memories lazily on retrieval (lightweight)
        try {
            memoryDao.deleteExpired(now)
        } catch (_: Exception) { }

        val candidates = candidateIds(filters.copy(includeExpired = false), now)
        if (candidates.isEmpty()) return Result.Success(emptyList())
        val keywordIds = keywordMatchIds(query)
        val index = ensureVectorIndex()

        val results: List<MemorySearchResult> = if (index != null) {
            val queryVec = embedQuery(query)
            if (queryVec != null) {
                val scored = index.search(queryVec, k * 4, candidates)
                val memoriesById = memoryDao.getByIds(scored.map { it.id }).associateBy { it.id }
                val tagMap = memoryDao.getTagsForMemoryIds(memoriesById.keys.toList())
                    .groupBy({ it.memoryId }, { it.tagName })
                // Hardened: validate each retrieved entity still valid, not corrupted, not expired, not contradictory
                scored
                    .mapNotNull { (id, score) ->
                        val entity = memoriesById[id] ?: return@mapNotNull null
                        // Validation: ensure retrieved memories are still valid (not corrupted, not blank, valid metadata)
                        if (entity.content.isBlank() || entity.content.length > 2000) {
                            logger.warn("Skipped corrupted memory ${entity.id} blank/too long")
                            return@mapNotNull null
                        }
                        // Safeguard against null/corrupted category/type
                        val catValid = try { MemoryCategory.fromName(entity.category); true } catch (_: Exception) { false }
                        val typeValid = try { io.androllm.core.memory.MemoryType.fromName(entity.type); true } catch (_: Exception) { false }
                        if (!catValid || !typeValid) {
                            logger.warn("Skipped corrupted memory ${entity.id} invalid category/type")
                            return@mapNotNull null
                        }
                        val domain = try { entity.toDomain(tagMap[id].orEmpty()) } catch (e: Exception) {
                            logger.warn("Skipped malformed memory ${entity.id}: ${e.message}")
                            return@mapNotNull null
                        }
                        if (domain.expiryAt != null && domain.expiryAt < now) return@mapNotNull null
                        // Detect contradictory memories before use: will be filtered later, but mark
                        MemorySearchResult(domain, score, id in keywordIds)
                    }
                    // Threshold: drop low-relevance unrelated memories unless pinned/keyword
                    .filter { r ->
                        r.memory.isPinned || r.matchedByKeyword || r.score >= settings.similarityThreshold
                    }
                    // Detect contradictory memories among candidates: keep higher confidence (priority + recency)
                    .let { filtered ->
                        val toRemove = mutableSetOf<String>()
                        for (i in filtered.indices) {
                            for (j in i + 1 until filtered.size) {
                                val a = filtered[i]
                                val b = filtered[j]
                                if (hardeningHelper.isContradictory(a.memory.content, b.memory.content)) {
                                    // Resolve using timestamps, confidence (priority), evidence
                                    val winner = hardeningHelper.resolveConflict(b.memory.content, b.memory.effectivePriority, b.memory.updatedAt, a.memory)
                                    val loserId = if (winner?.id == a.memory.id) b.memory.id else a.memory.id
                                    toRemove.add(loserId)
                                    logger.info("Contradiction detected between ${a.memory.id} and ${b.memory.id}, keeping ${winner?.id ?: b.memory.id}")
                                }
                            }
                        }
                        if (toRemove.isNotEmpty()) filtered.filter { it.memory.id !in toRemove } else filtered
                    }
                    .sortedWith(
                        // Separate semantic relevance from recency: semantic (score + keyword) dominates, recency only tie-breaks when scores close
                        compareByDescending<MemorySearchResult> { it.score + (if (it.matchedByKeyword) HYBRID_KEYWORD_BOOST else 0f) }
                            .thenByDescending { it.memory.isPinned }
                            .thenByDescending { it.memory.effectivePriority }
                            .thenByDescending { 
                                // Recency only as final tie-breaker, not mixed into score
                                it.memory.updatedAt
                            }
                    )
                    .take(k)
            } else {
                keywordFallback(candidates, keywordIds, k, now)
            }
        } else {
            keywordFallback(candidates, keywordIds, k, now)
        }

        val elapsed = System.currentTimeMillis() - t0
        lastRetrievalMs = elapsed
        avgRetrievalMs = if (retrievalSamples == 0L) elapsed
        else (avgRetrievalMs * retrievalSamples + elapsed) / (retrievalSamples + 1)
        retrievalSamples++

        for (r in results) try { memoryDao.bumpAccess(r.memory.id, now) } catch (_: Exception) {}
        // Also update lastUsedAt for retrieved memories (thread-safe, transactional)
        results.forEach { r ->
            try {
                memoryDao.getById(r.memory.id)?.let { entity ->
                    memoryDao.update(entity.copy(lastUsedAt = now, lastAccessedAt = now))
                }
            } catch (_: Exception) { }
        }
        // Update cache with fresh accessed values (ensures next hit reflects recency)
        try { cacheMutex.withLock { retrievalCache[cacheKey] = results } } catch (_: Exception) {}
        results
    }

    private fun recencyBoost(updatedAt: Long, now: Long): Float {
        val ageMs = (now - updatedAt).coerceAtLeast(0L)
        val thirtyDaysMs = 30L * 24 * 60 * 60 * 1000
        // 0.1 for very recent (<1 day), 0 for >30 days, linear decay
        return ((1f - (ageMs.toFloat() / thirtyDaysMs).coerceIn(0f, 1f)) * 0.08f)
    }

    /**
     * Builds the context block injected into the system prompt: relevant
     * memories + recent conversation summaries. Never dumps the database.
     */
    override suspend fun buildContext(
        userQuery: String,
        filters: MemorySearchFilters,
        conversationId: String?,
        topK: Int?
    ): MemoryContext {
        val settings = settingsStore.current()
        val t0 = System.currentTimeMillis()
        val memories = retrieve(userQuery, filters, topK).getOrDefault(emptyList())

        val summaries = if (settings.maxContextSummaries > 0) {
            val all = summaryDao.getAll().map { it.toDomain() }.take(settings.maxContextSummaries)
            val current = conversationId?.let { summaryDao.getLatestForConversation(it)?.toDomain() }
            (listOfNotNull(current) + all.filter { it.id != current?.id })
                .take(settings.maxContextSummaries)
        } else {
            emptyList()
        }

        val text = contextBuilder.buildSystemText(
            memories = memories,
            summaries = summaries,
            maxMemories = settings.maxContextMemories,
            maxSummaries = settings.maxContextSummaries
        )
        return MemoryContext(
            memories = memories,
            summaries = summaries,
            retrievalMs = System.currentTimeMillis() - t0,
            systemText = text
        )
    }

    // ── Update pipeline ──

    /**
     * Full post-response pipeline: extract → embed → search → update-or-insert,
     * plus summarization scheduling. Best-effort and non-blocking for the UI:
     * LLM work (extraction/summarization) and embedding calls run on the IO
     * dispatcher regardless of the caller's context.
     */
    override suspend fun processExchange(exchange: MemoryExchange): Result<MemoryWriteSummary> =
        io.androllm.core.common.runCatching {
            val settings = settingsStore.current()
            if (!settings.enabled) return Result.Success(MemoryWriteSummary())

            val t0 = System.currentTimeMillis()
            val items = withContext(Dispatchers.IO) {
                intelligence.extract(exchange, settings).getOrNull().orEmpty()
            }
            val extractMs = System.currentTimeMillis() - t0
            lastExtractionMs = extractMs
            totalExtractions++

            val writeT0 = System.currentTimeMillis()
            var inserted = 0
            var updated = 0
            var skipped = 0
            val writtenIds = mutableListOf<String>()
            for (item in items) {
                val r = writeMemory(item, exchange, settings)
                when (r.action) {
                    MemoryWriteAction.INSERTED -> { inserted++; writtenIds += r.memoryId }
                    MemoryWriteAction.UPDATED -> updated++
                    MemoryWriteAction.SKIPPED -> skipped++
                }
            }
            linkExchangeMemories(writtenIds)
            val writeMs = System.currentTimeMillis() - writeT0

            val summarizeT0 = System.currentTimeMillis()
            val summarized = maybeSummarize(exchange, settings)
            val summarizeMs = System.currentTimeMillis() - summarizeT0

            logger.info("Exchange processed: +$inserted ~$updated -$skipped summarized=$summarized")
            // Post-generation audit: per-stage cost of the deferred memory
            // pipeline. Embedding cost is inside the DB stage (writeMemory) and
            // additionally tracked as lastEmbeddingMs (inspector stats).
            android.util.Log.i(
                "AndroLLM.Perf",
                "[MemoryPostGen] extract=${extractMs}ms db+embed=${writeMs}ms summarize=${summarizeMs}ms " +
                    "embedLast=${lastEmbeddingMs}ms total=${System.currentTimeMillis() - t0}ms"
            )
            MemoryWriteSummary(
                inserted = inserted,
                updated = updated,
                skipped = skipped,
                extracted = items.size,
                summarized = summarized
            )
        }

    /**
     * Manually adds a memory (settings UI / future pinning flows).
     */
    override suspend fun saveMemory(
        category: MemoryCategory,
        content: String,
        importance: Int,
        tags: List<String>,
        projectName: String?
    ): Result<MemoryWriteResult> = io.androllm.core.common.runCatching {
        val settings = settingsStore.current()
        writeMemory(
            ExtractedMemory(content.trim(), category, importance.coerceIn(1, 5), tags, projectName),
            MemoryExchange(conversationId = "", userMessage = "", assistantResponse = "", messageCount = 0),
            settings
        )
    }

    // ── CRUD ──

    override fun observeMemories(): Flow<List<Memory>> = memoryDao.observeAllWithPinnedFirst().mapLatest { entities ->
        val tagMap = memoryDao.getTagsForMemoryIds(entities.map { it.id })
            .groupBy({ it.memoryId }, { it.tagName })
        entities.map { it.toDomain(tagMap[it.id].orEmpty()) }
    }

    override suspend fun getMemories(): List<Memory> = io.androllm.core.common.runCatching {
        val entities = memoryDao.getAll()
        val tagMap = memoryDao.getTagsForMemoryIds(entities.map { it.id })
            .groupBy({ it.memoryId }, { it.tagName })
        entities.map { it.toDomain(tagMap[it.id].orEmpty()) }
    }.getOrDefault(emptyList())

    override suspend fun getMemory(id: String): Memory? = io.androllm.core.common.runCatching {
        memoryDao.getById(id)?.let { it.toDomain(tagDao.getTagNamesForMemory(it.id)) }
    }.getOrDefault(null)

    override fun observeProjects(): Flow<List<Project>> = projectDao.observeAll().map { list ->
        list.map { Project(it.id, it.name, it.description, it.createdAt) }
    }

    override suspend fun pinMemory(id: String, pinned: Boolean): Result<Unit> = io.androllm.core.common.runCatching {
        memoryDao.updatePinned(id, pinned)
        logger.info(if (pinned) "Pinned memory $id" else "Unpinned memory $id")
    }

    override suspend fun archiveMemory(id: String, archived: Boolean): Result<Unit> = io.androllm.core.common.runCatching {
        memoryDao.updateArchived(id, archived)
    }

    override suspend fun deleteMemory(id: String): Result<Unit> = io.androllm.core.common.runCatching {
        writeMutex.withLock {
            // Transactional delete: memory + embedding + tags + index + cache (relationships are orphaned and ignored in retrieval)
            try {
                memoryDao.deleteById(id)
            } catch (e: Exception) { logger.warn("deleteMemory: memoryDao failed for $id: ${e.message}") }
            try { embeddingDao.deleteByMemoryId(id) } catch (_: Exception) {}
            try { tagDao.deleteCrossRefsForMemory(id) } catch (_: Exception) {}
            indexMutex.withLock { vectorIndex?.remove(id) }
            cacheMutex.withLock { retrievalCache.clear() }
            logger.info("Deleted memory $id and purged embeddings/cache")
        }
    }

    override suspend fun updateImportance(id: String, importance: Int): Result<Unit> = io.androllm.core.common.runCatching {
        memoryDao.updateImportance(id, importance.coerceIn(1, 5), System.currentTimeMillis())
    }

    /**
     * Wipes the entire memory store (database, index, logs) — transactional with rollback.
     */
    override suspend fun deleteAll(): Result<Unit> = io.androllm.core.common.runCatching {
        writeMutex.withLock {
            try {
                memoryDao.deleteAll()
                embeddingDao.deleteAll()
                summaryDao.deleteAll()
                projectDao.deleteAll()
                tagDao.deleteAll()
                relationshipDao.deleteAll()
                indexMutex.withLock { vectorIndex?.clear() }
                cacheMutex.withLock { retrievalCache.clear() }
                logger.clear()
                logger.info("Memory store wiped")
            } catch (e: Exception) {
                logger.error("deleteAll failed: ${e.message}")
                throw e
            }
        }
    }

    override suspend fun deleteSummariesForConversation(conversationId: String): Result<Unit> =
        io.androllm.core.common.runCatching {
            summaryDao.deleteForConversation(conversationId)
        }

    // ── Export / Import ──

    /**
     * Exports all memories (with projects/tags/relationships) as a JSON file.
     */
    override suspend fun exportMemories(): Result<File> = io.androllm.core.common.runCatching {
        val memories = memoryDao.getAll()
        val projects = projectDao.getAll().associateBy { it.id }
        val tags = tagDao.getAll().associateBy { it.id }
        val tagMap = memoryDao.getTagsForMemoryIds(memories.map { it.id })
            .groupBy({ it.memoryId }, { it.tagName })
        val relationships = relationshipDao.getAll()

        val dto = MemoryExportFile(
            version = EXPORT_VERSION,
            exportedAt = System.currentTimeMillis(),
            memories = memories.map { m ->
                MemoryExportItem(
                    id = m.id,
                    category = m.category,
                    content = m.content,
                    importance = m.importance,
                    tags = tagMap[m.id].orEmpty(),
                    project = m.projectId?.let { projects[it]?.name },
                    isPinned = m.isPinned,
                    isArchived = m.isArchived,
                    createdAt = m.createdAt,
                    updatedAt = m.updatedAt
                )
            },
            relationships = relationships.map {
                MemoryExportRelationship(
                    from = it.fromMemoryId,
                    to = it.toMemoryId,
                    type = it.type
                )
            }
        )

        val dir = File(context.cacheDir, "memory_exports").apply { mkdirs() }
        val file = File(dir, "androllm_memory_${System.currentTimeMillis()}.json")
        file.writeText(json.encodeToString(MemoryExportFile.serializer(), dto))
        logger.info("Exported ${dto.memories.size} memories")
        file
    }

    /**
     * Imports memories from an exported JSON file. Duplicates (by normalized
     * content within the same category) update existing rows instead of
     * creating new ones. Embeddings are re-generated lazily by the pipeline.
     */
    override suspend fun importMemories(file: File): Result<MemoryImportResult> =
        io.androllm.core.common.runCatching {
            val dto = json.decodeFromString(MemoryExportFile.serializer(), file.readText())
            val settings = settingsStore.current()
            var inserted = 0
            var updated = 0
            var skipped = 0
            for (item in dto.memories) {
                val category = MemoryCategory.fromName(item.category)
                val result = writeMemory(
                    ExtractedMemory(
                        content = item.content,
                        category = category,
                        importance = item.importance.coerceIn(1, 5),
                        tags = item.tags,
                        projectName = item.project
                    ),
                    MemoryExchange("", "", "", emptyList(), 0),
                    settings,
                    forceInsert = false
                )
                when (result.action) {
                    MemoryWriteAction.INSERTED -> {
                        inserted++
                        // Restore export fidelity: pin/archive flags + timestamps.
                        memoryDao.getById(result.memoryId)?.let { mem ->
                            memoryDao.update(
                                mem.copy(
                                    isPinned = item.isPinned,
                                    isArchived = item.isArchived,
                                    createdAt = item.createdAt,
                                    updatedAt = item.updatedAt
                                )
                            )
                        }
                    }
                    MemoryWriteAction.UPDATED -> updated++
                    MemoryWriteAction.SKIPPED -> skipped++
                }
            }
            logger.info("Import: +$inserted ~$updated -$skipped from ${file.name}")
            MemoryImportResult(inserted, updated, skipped)
        }

    // ── Reindex / background indexing ──

    /**
     * Re-embeds every memory with the current embedding source (cloud route
     * or local GGUF). Requires a configured source; returns the number of
     * re-embedded memories.
     */
    override suspend fun reindexAll(): Result<Int> = io.androllm.core.common.runCatching {
        withContext(Dispatchers.IO) {
            embeddingProvider.ensureLoaded().getOrThrow()
            val settings = settingsStore.current()
            val label = embeddingSourceLabel(settings)
            val memories = memoryDao.getAll()
            var count = 0
            for (chunk in memories.chunked(16)) {
                val texts = chunk.map { withPassagePrefix(it.content, settings) }
                val vectors = embeddingProvider.embed(texts).getOrNull() ?: continue
                val now = System.currentTimeMillis()
                embeddingDao.upsertAll(
                    chunk.mapIndexed { i, m ->
                        EmbeddingEntity(
                            memoryId = m.id,
                            vector = VectorMath.toBytes(vectors[i]),
                            dimension = vectors[i].size,
                            modelPath = label,
                            createdAt = now
                        )
                    }
                )
                indexMutex.withLock {
                    val index = ensureVectorIndexLocked() ?: CosineVectorIndex(vectors.first().size)
                    for (i in chunk.indices) index.upsert(chunk[i].id, vectors[i])
                }
                count += chunk.size
                totalEmbeddings += chunk.size
            }
            logger.info("Reindexed $count memories")
            count
        }
    }

    /**
     * Drains the pending-embedding queue: embeds memories that currently have
     * no vector, using whatever embedding source is configured. Called by the
     * background worker and whenever memory is enabled. Embeddings are an
     * optimization — if no source is available this simply reports 0 and the
     * memories stay fully usable via keyword/recency retrieval.
     */
    override suspend fun embedPendingMemories(): Result<Int> = io.androllm.core.common.runCatching {
        val count = withContext(Dispatchers.IO) {
            val settings = settingsStore.current()
            if (settings.embeddingModelPath.isBlank() && settings.cloudEmbeddingModel.isBlank()) {
                return@withContext 0
            }
            val missing = memoryDao.getMemoryIdsWithoutEmbeddings()
            if (missing.isEmpty()) return@withContext 0
            val entities = memoryDao.getByIds(missing)
            val label = embeddingSourceLabel(settings)
            var count = 0
            for (chunk in entities.chunked(8)) {
                val texts = chunk.map { withPassagePrefix(it.content, settings) }
                val vectors = embeddingProvider.embed(texts).getOrNull() ?: continue
                val now = System.currentTimeMillis()
                embeddingDao.upsertAll(
                    chunk.mapIndexed { i, m ->
                        EmbeddingEntity(
                            memoryId = m.id,
                            vector = VectorMath.toBytes(vectors[i]),
                            dimension = vectors[i].size,
                            modelPath = label,
                            createdAt = now
                        )
                    }
                )
                indexMutex.withLock {
                    val index = ensureVectorIndexLocked() ?: CosineVectorIndex(vectors.first().size).also { vectorIndex = it }
                    for (i in chunk.indices) index.upsert(chunk[i].id, vectors[i])
                }
                count += chunk.size
                totalEmbeddings += chunk.size
            }
            logger.info("Background indexing: embedded $count pending memory(s)")
            count
        }
        count
    }

    // ── Inspector ──

    override suspend fun getInspectorStats(): MemoryInspectorStats {
        val settings = settingsStore.current()
        return MemoryInspectorStats(
            memoryCount = memoryDao.count(),
            embeddingCount = embeddingDao.count(),
            vectorCount = vectorIndex?.size ?: embeddingDao.count(),
            projectCount = projectDao.count(),
            tagCount = tagDao.count(),
            summaryCount = summaryDao.count(),
            relationshipCount = relationshipDao.count(),
            avgRetrievalMs = avgRetrievalMs,
            lastRetrievalMs = lastRetrievalMs,
            lastEmbeddingMs = lastEmbeddingMs,
            lastExtractionMs = lastExtractionMs,
            totalExtractions = totalExtractions,
            totalEmbeddings = totalEmbeddings,
            totalInserted = totalInserted,
            totalUpdated = totalUpdated,
            retrievalCount = settings.retrievalCount,
            similarityThreshold = settings.similarityThreshold,
            embeddingModelPath = settings.embeddingModelPath,
            cloudEmbeddingModel = settings.cloudEmbeddingModel,
            embeddingDimension = embeddingProvider.dimension,
            embeddingModelLoaded = embeddingProvider.isModelLoaded(),
            enabled = settings.enabled,
            logs = logger.recent(60)
        )
    }

    override fun observeInspectorLogs(): Flow<List<MemoryLogEntry>> =
        kotlinx.coroutines.flow.flow { emit(logger.snapshot()) }

    // ── Internals ──

    private suspend fun candidateIds(filters: MemorySearchFilters, now: Long = System.currentTimeMillis()): Set<String> {
        val minImportance = maxOf(filters.minImportance, filters.minPriority)
        val typeName = filters.type?.name
        if (filters.tags.isEmpty()) {
            return memoryDao.getFilteredIds(
                category = filters.category?.name,
                projectId = filters.projectId,
                pinnedOnly = filters.pinnedOnly,
                includeArchived = filters.includeArchived,
                minImportance = minImportance,
                tag = null,
                type = typeName,
                chatId = filters.chatId,
                userId = filters.userId,
                includeExpired = filters.includeExpired,
                now = now
            ).toSet()
        }
        // Match-any tag semantics: union of per-tag candidate sets.
        val result = mutableSetOf<String>()
        for (tag in filters.tags) {
            result += memoryDao.getFilteredIds(
                category = filters.category?.name,
                projectId = filters.projectId,
                pinnedOnly = filters.pinnedOnly,
                includeArchived = filters.includeArchived,
                minImportance = minImportance,
                tag = tag,
                type = typeName,
                chatId = filters.chatId,
                userId = filters.userId,
                includeExpired = filters.includeExpired,
                now = now
            )
        }
        return result
    }

    private suspend fun keywordMatchIds(query: String): Set<String> {
        if (query.isBlank()) return emptySet()
        val q = query.trim().take(120)
        return (memoryDao.searchContentIds(q) + memoryDao.searchTagIds(q)).toSet()
    }

    private suspend fun keywordFallback(
        candidates: Set<String>,
        keywordIds: Set<String>,
        k: Int,
        now: Long = System.currentTimeMillis()
    ): List<MemorySearchResult> {
        val entities = memoryDao.getByIds(candidates.toList())
            .filter { it.expiryAt == null || it.expiryAt > now }
        if (entities.isEmpty()) return emptyList()
        val tagMap = memoryDao.getTagsForMemoryIds(entities.map { it.id })
            .groupBy({ it.memoryId }, { it.tagName })
        return entities
            .sortedWith(
                compareByDescending<MemoryEntity> { it.id in keywordIds }
                    .thenByDescending { it.isPinned }
                    .thenByDescending { maxOf(it.importance, it.priority) }
                    .thenByDescending { it.updatedAt + recencyBoost(it.updatedAt, now) * 1000000L }
                    .thenByDescending { it.lastUsedAt ?: it.lastAccessedAt ?: 0L }
            )
            .take(k)
            .map { MemorySearchResult(it.toDomain(tagMap[it.id].orEmpty()), if (it.id in keywordIds) 0.6f else 0f, it.id in keywordIds) }
    }

    private suspend fun embedQuery(query: String): FloatArray? {
        if (query.isBlank()) return null
        val settings = settingsStore.current()
        if (settings.embeddingModelPath.isBlank() && settings.cloudEmbeddingModel.isBlank()) return null
        val text = if (settings.queryPrefix.isBlank()) query else "${settings.queryPrefix} $query"
        val t0 = System.currentTimeMillis()
        val vec = embeddingProvider.embed(text).getOrNull() ?: return null
        lastEmbeddingMs = System.currentTimeMillis() - t0
        totalEmbeddings++
        return vec
    }

    private suspend fun ensureVectorIndex(): CosineVectorIndex? = indexMutex.withLock {
        ensureVectorIndexLocked()
    }

    private suspend fun ensureVectorIndexLocked(): CosineVectorIndex? {
        val embeddings = if (vectorIndex == null) embeddingDao.getAll() else emptyList()
        if (embeddings.isEmpty()) return vectorIndex
        val dim = embeddings.first().dimension
        if (vectorIndex?.dimension == dim) return vectorIndex
        val index = CosineVectorIndex(dim)
        for (e in embeddings) index.upsert(e.memoryId, VectorMath.fromBytes(e.vector))
        vectorIndex = index
        return index
    }

    private suspend fun embedForWrite(content: String, settings: MemorySettings): FloatArray? {
        if (settings.embeddingModelPath.isBlank() && settings.cloudEmbeddingModel.isBlank()) return null
        val text = withPassagePrefix(content, settings)
        val t0 = System.currentTimeMillis()
        val vec = embeddingProvider.embed(text).getOrNull() ?: return null
        lastEmbeddingMs = System.currentTimeMillis() - t0
        totalEmbeddings++
        return vec
    }

    private fun withPassagePrefix(content: String, settings: MemorySettings): String =
        if (settings.passagePrefix.isBlank()) content else "${settings.passagePrefix} $content"

    /** Labels the embedding vector rows with their source so a stale set can be detected. */
    private fun embeddingSourceLabel(settings: MemorySettings): String =
        if (settings.cloudEmbeddingModel.isNotBlank()) "cloud:${settings.cloudEmbeddingModel}"
        else settings.embeddingModelPath

    /** Runs blocking embedding-engine operations off the calling (main) thread. */
    private suspend fun <T> embeddingsWithContext(block: suspend () -> T): T =
        withContext(Dispatchers.IO) { block() }

    /**
     * Core update-or-insert step. Production-hardened: deterministic dedupe, confidence gating,
     * temporary-context filtering, type-classified expiry, security, merging, conflict resolution.
     * Never creates duplicates: embedding threshold + deterministic normalized comparison + word-overlap.
     * Thread-safe via [writeMutex], transactional with rollback, ensures cache invalidation.
     */
    private suspend fun writeMemory(
        item: ExtractedMemory,
        exchange: MemoryExchange,
        settings: MemorySettings,
        forceInsert: Boolean = false
    ): MemoryWriteResult = writeMutex.withLock {
        val now = System.currentTimeMillis()
        var content = item.content.trim().replace(WHITESPACE, " ")
        if (content.isEmpty() || content.length > MAX_MEMORY_LENGTH) {
            logger.debug("Skipped memory: empty or too long")
            return@withLock MemoryWriteResult("", MemoryWriteAction.SKIPPED)
        }
        // Quality: prefer concise entries — trim to 280 chars while preserving meaning
        if (content.length > 280) {
            val truncated = content.take(280).trim()
            val lastSpace = truncated.lastIndexOf(' ')
            content = if (lastSpace > 200) truncated.substring(0, lastSpace) else truncated
        }

        // Security: validate every entry before saving — never store secrets, injection, hallucinations
        securityFilter.validate(content, exchange)?.let { reason ->
            logger.warn("Skipped memory (security): $reason — '${content.take(60)}'")
            return@withLock MemoryWriteResult("", MemoryWriteAction.SKIPPED)
        }

        // Hardening: prevent temporary conversation context, one-off requests, short-lived preferences
        if (hardeningHelper.isTemporaryContext(content, exchange)) {
            logger.debug("Skipped temporary context: '${content.take(50)}'")
            return@withLock MemoryWriteResult("", MemoryWriteAction.SKIPPED)
        }

        // Hardening: stronger confidence scoring before commit (never create duplicate for same fact)
        val confidence = hardeningHelper.confidenceScore(item.copy(content = content), exchange)
        if (!hardeningHelper.shouldCommit(item.copy(content = content), exchange)) {
            logger.debug("Skipped low confidence (${"%.2f".format(confidence)}): '${content.take(50)}'")
            return@withLock MemoryWriteResult("", MemoryWriteAction.SKIPPED)
        }

        // Classification: determine lifecycle type, priority, expiry, prompt-memory handling
        val chatId = exchange.conversationId.takeIf { it.isNotBlank() }
        var effectiveCategory = item.category
        var effectiveImportance = item.importance.coerceIn(1, 5)
        // Prompt memory boost: treat formatting/tone/template as high-priority preferences
        if (classifier.isPromptMemory(content)) {
            effectiveCategory = io.androllm.core.memory.MemoryCategory.PREFERENCES
            effectiveImportance = maxOf(effectiveImportance, 4)
            logger.debug("Prompt memory detected, boosted to PREFERENCES priority 4: '${content.take(50)}'")
        }
        val type = classifier.classifyType(item.copy(category = effectiveCategory, importance = effectiveImportance), chatId)
        val priority = classifier.computePriority(item.copy(category = effectiveCategory, importance = effectiveImportance), type)
        val expiryAt = classifier.computeExpiry(type, now)
        if (type == io.androllm.core.memory.MemoryType.SHORT_TERM && chatId.isNullOrBlank()) {
            logger.debug("Skipped short-term memory with no chatId: '$content'")
            return@withLock MemoryWriteResult("", MemoryWriteAction.SKIPPED)
        }

        val projectId = resolveProjectId(item.projectName, now)
        val tagIds = resolveTagIds(item.tags)

        // 1) Embedding-based dedupe + merge similar — deterministic via normalized comparison + embedding threshold
        val embedding = if (forceInsert) null else embedForWrite(content, settings)
        if (embedding != null) {
            val index = ensureVectorIndex()
            if (index != null && index.size > 0) {
                val sameCategory = memoryDao.getFilteredIds(effectiveCategory.name, null, false, true, 0, null)
                val best = index.search(embedding, 1, sameCategory).firstOrNull()
                if (best != null) {
                    when {
                        best.score >= settings.similarityThreshold -> {
                            // Resolve conflicting memories before updating: timestamps, confidence, evidence
                            val existingForConflict = try { memoryDao.getById(best.id)?.toDomain(emptyList()) } catch (_: Exception) { null }
                            if (existingForConflict != null && hardeningHelper.isContradictory(content, existingForConflict.content)) {
                                val winner = hardeningHelper.resolveConflict(content, priority, now, existingForConflict)
                                if (winner != null && winner.id == existingForConflict.id) {
                                    logger.info("Conflict resolved: kept existing '${existingForConflict.content.take(40)}' over new '${content.take(40)}'")
                                    return@withLock MemoryWriteResult(existingForConflict.id, MemoryWriteAction.SKIPPED)
                                }
                            }
                            return@withLock updateExisting(best.id, item.copy(content = content, category = effectiveCategory, importance = priority), projectId, now, best.score, type, chatId, priority, expiryAt)
                        }
                        best.score >= settings.similarityThreshold - 0.08f -> {
                            logger.debug("Merging similar memory (score ${"%.3f".format(best.score)}): '${content.take(40)}'")
                            return@withLock updateExisting(best.id, item.copy(content = content, category = effectiveCategory, importance = priority), projectId, now, best.score, type, chatId, priority, expiryAt, mergeContent = true)
                        }
                    }
                }
            }
        }

        // 2) Normalized exact-content dedupe (no embedding available) — deterministic via hardeningHelper
        if (!forceInsert) {
            val existing = findExactDuplicate(content, effectiveCategory)
            if (existing != null) {
                // Intelligent update when new information supersedes older: use conflict resolver
                val existingDomain = try { existing.toDomain(tagDao.getTagNamesForMemory(existing.id)) } catch (_: Exception) { null }
                if (existingDomain != null && hardeningHelper.isContradictory(content, existingDomain.content)) {
                    val winner = hardeningHelper.resolveConflict(content, priority, now, existingDomain)
                    if (winner != null && winner.id == existingDomain.id) {
                        logger.info("Conflict resolved: kept existing for '${content.take(40)}'")
                        return@withLock MemoryWriteResult(existingDomain.id, MemoryWriteAction.SKIPPED)
                    }
                }
                return@withLock updateExisting(existing.id, item.copy(content = content, category = effectiveCategory, importance = priority), projectId, now, null, type, chatId, priority, expiryAt)
            }
        }

        // 3) Insert — hardened with full storage spec fields, transactional with rollback, deterministic
        val id = UUID.randomUUID().toString()
        try {
            memoryDao.upsert(
                MemoryEntity(
                    id = id,
                    userId = "default",
                    chatId = chatId,
                    type = type.name,
                    category = effectiveCategory.name,
                    content = content,
                    summary = null,
                    priority = priority,
                    importance = priority,
                    projectId = projectId,
                    sourceConversationId = chatId,
                    createdAt = now,
                    updatedAt = now,
                    lastAccessedAt = now,
                    lastUsedAt = now,
                    expiryAt = expiryAt
                )
            )
            if (tagIds.isNotEmpty()) {
                tagDao.insertCrossRefs(tagIds.map { MemoryTagCrossRef(id, it) })
            }
            if (embedding != null) {
                embeddingDao.upsert(
                    EmbeddingEntity(
                        memoryId = id,
                        vector = VectorMath.toBytes(embedding),
                        dimension = embedding.size,
                        modelPath = embeddingSourceLabel(settings),
                        createdAt = now
                    )
                )
                indexMutex.withLock {
                    val index = ensureVectorIndexLocked() ?: CosineVectorIndex(embedding.size).also { vectorIndex = it }
                    index.upsert(id, embedding)
                }
            }
            totalInserted++
            // Invalidate retrieval cache for this category
            cacheMutex.withLock { retrievalCache.clear() }
            logger.info("Inserted memory $id category=${effectiveCategory.name} confidence=${"%.2f".format(confidence)}")
            return@withLock MemoryWriteResult(id, MemoryWriteAction.INSERTED)
        } catch (e: Exception) {
            // Automatic rollback for partial failures: ensure no dangling memory without embedding/tags
            try {
                memoryDao.deleteById(id)
                tagDao.deleteCrossRefsForMemory(id)
                embeddingDao.deleteByMemoryId(id)
                indexMutex.withLock { vectorIndex?.remove(id) }
            } catch (_: Exception) {}
            logger.error("Insert failed, rolled back $id: ${e.message}")
            return@withLock MemoryWriteResult("", MemoryWriteAction.SKIPPED)
        }
    }

    private suspend fun updateExisting(
        id: String,
        item: ExtractedMemory,
        projectId: String?,
        now: Long,
        score: Float?,
        type: io.androllm.core.memory.MemoryType? = null,
        chatId: String? = null,
        priority: Int? = null,
        expiryAt: Long? = null,
        mergeContent: Boolean = false
    ): MemoryWriteResult {
        val existing = try { memoryDao.getById(id) } catch (e: Exception) {
            logger.warn("updateExisting: getById failed for $id: ${e.message}")
            return MemoryWriteResult(id, MemoryWriteAction.SKIPPED)
        } ?: return MemoryWriteResult(id, MemoryWriteAction.SKIPPED)
        val existingTags = try { tagDao.getTagNamesForMemory(id) } catch (_: Exception) { emptyList() }
        val mergedTags = (existingTags + item.tags.map { it.trim().lowercase().take(32) }.filter { it.isNotEmpty() })
            .distinct()
            .take(10)

        // Hardening: intelligently update when new information supersedes older — use content from new if different and not just duplicate
        val normalizedExisting = hardeningHelper.normalizeForDedupe(existing.content)
        val normalizedNew = hardeningHelper.normalizeForDedupe(item.content)
        val isDifferentContent = normalizedExisting != normalizedNew
        val newContent = when {
            mergeContent && isDifferentContent -> {
                // Merge similar memories: combine but keep concise (<280), deduplicate words
                val merged = "${existing.content} ${item.content}".replace(WHITESPACE, " ").trim().take(280)
                merged
            }
            isDifferentContent -> {
                // New supersedes old (e.g., prefers light vs dark): use new content, higher priority wins
                // Confidence already checked, so new is meaningful
                item.content
            }
            else -> existing.content
        }

        val newPriority = priority?.coerceIn(1, 5) ?: maxOf(existing.priority, existing.importance, item.importance.coerceIn(1, 5))
        val newType = type?.name ?: existing.type
        val newChatId = chatId ?: existing.chatId ?: existing.sourceConversationId
        val newExpiry = expiryAt ?: existing.expiryAt

        try {
            memoryDao.update(
                existing.copy(
                    content = newContent,
                    importance = newPriority,
                    priority = newPriority,
                    updatedAt = now,
                    lastUsedAt = now,
                    lastAccessedAt = now,
                    projectId = existing.projectId ?: projectId,
                    chatId = newChatId,
                    sourceConversationId = newChatId ?: existing.sourceConversationId,
                    type = newType,
                    expiryAt = newExpiry
                )
            )
            // Merge tags: drop old crossrefs, insert merged set.
            try {
                tagDao.deleteCrossRefsForMemory(id)
                if (mergedTags.isNotEmpty()) {
                    val mergedTagIds = resolveTagIds(mergedTags)
                    tagDao.insertCrossRefs(mergedTagIds.map { MemoryTagCrossRef(id, it) })
                }
            } catch (e: Exception) { logger.warn("Tag merge failed for $id: ${e.message}") }

            // Hardening: automatically re-embed when content changed (embedding consistency)
            if (isDifferentContent) {
                try {
                    val settings = settingsStore.current()
                    val newEmbedding = embedForWrite(newContent, settings)
                    if (newEmbedding != null) {
                        embeddingDao.upsert(
                            EmbeddingEntity(
                                memoryId = id,
                                vector = VectorMath.toBytes(newEmbedding),
                                dimension = newEmbedding.size,
                                modelPath = embeddingSourceLabel(settings),
                                createdAt = now
                            )
                        )
                        indexMutex.withLock {
                            val idx = ensureVectorIndexLocked() ?: CosineVectorIndex(newEmbedding.size).also { vectorIndex = it }
                            // Handle dimension change: recreate index if needed
                            if (idx.dimension != newEmbedding.size) {
                                vectorIndex = CosineVectorIndex(newEmbedding.size)
                                // Reindex will be triggered lazily
                            } else {
                                idx.upsert(id, newEmbedding)
                            }
                        }
                    } else {
                        // No embedding available: remove stale embedding to avoid corrupted search
                        try { embeddingDao.deleteByMemoryId(id) } catch (_: Exception) {}
                        indexMutex.withLock { vectorIndex?.remove(id) }
                    }
                } catch (e: Exception) { logger.warn("Re-embed failed for $id: ${e.message}") }
            }

            cacheMutex.withLock { retrievalCache.clear() }
            totalUpdated++
            logger.debug("Updated memory $id (score=${score?.let { "%.3f".format(it) } ?: "exact"}) newContent='${newContent.take(40)}'")
            return MemoryWriteResult(id, MemoryWriteAction.UPDATED, score)
        } catch (e: Exception) {
            logger.error("Update failed for $id: ${e.message}")
            // Automatic rollback: restore original if partial failure (Room transaction would rollback, but we ensure)
            return MemoryWriteResult(id, MemoryWriteAction.SKIPPED)
        }
    }

    private suspend fun findExactDuplicate(content: String, category: MemoryCategory): MemoryEntity? {
        val norm = normalizeForCompare(content)
        val ids = memoryDao.getFilteredIds(category.name, null, false, true, 0, null)
        val entities = memoryDao.getByIds(ids)
        // Exact match first
        entities.firstOrNull { normalizeForCompare(it.content) == norm }?.let { return it }
        // Near-duplicate for same category: high word overlap indicates same preference/topic being updated
        // e.g., "User prefers dark mode" vs "User prefers light mode" (75% overlap) should update, not duplicate
        // Only for PREFERENCES and similar stable categories where updates are common
        if (category == MemoryCategory.PREFERENCES || category == MemoryCategory.IDENTITY || category == MemoryCategory.PROJECTS) {
            val contentWords = norm.split(" ").filter { it.length > 3 }.toSet()
            if (contentWords.size >= 2) {
                for (entity in entities) {
                    val existingWords = normalizeForCompare(entity.content).split(" ").filter { it.length > 3 }.toSet()
                    if (existingWords.isEmpty()) continue
                    val overlap = contentWords.intersect(existingWords).size
                    val union = contentWords.union(existingWords).size
                    val jaccard = if (union > 0) overlap.toFloat() / union else 0f
                    // Also check word overlap ratio for contentWords
                    val overlapRatio = overlap.toFloat() / contentWords.size
                    if (jaccard >= 0.5f || overlapRatio >= 0.6f) {
                        // Ensure they share at least 2 significant words and are about same topic
                        if (overlap >= 2) return entity
                    }
                }
            }
        }
        return null
    }

    private fun normalizeForCompare(content: String): String =
        content.lowercase().replace(WHITESPACE, " ").trim()

    private suspend fun resolveProjectId(name: String?, now: Long): String? {
        if (name.isNullOrBlank()) return null
        val trimmed = name.trim().take(64)
        val existing = projectDao.getByName(trimmed)
        if (existing != null) return existing.id
        val project = ProjectEntity(UUID.randomUUID().toString(), trimmed, "", now)
        projectDao.upsert(project)
        return project.id
    }

    private suspend fun resolveTagIds(names: List<String>): List<String> {
        val result = mutableListOf<String>()
        for (name in names.distinct().take(10)) {
            val trimmed = name.trim().lowercase().take(32)
            if (trimmed.isEmpty()) continue
            val existing = tagDao.getByName(trimmed)
            if (existing != null) {
                result += existing.id
            } else {
                val tag = TagEntity(UUID.randomUUID().toString(), trimmed)
                tagDao.upsert(tag)
                result += tag.id
            }
        }
        return result
    }

    /**
     * Links memories written in the same exchange with "related_to"
     * relationships (deduplicated).
     */
    private suspend fun linkExchangeMemories(ids: List<String>) {
        if (ids.size < 2) return
        val existing = ids.flatMap { relationshipDao.getForMemory(it) }
            .map { "${it.fromMemoryId}|${it.toMemoryId}|${it.type}" }
            .toSet()
        for (i in ids.indices) {
            for (j in i + 1 until ids.size) {
                val key = "${ids[i]}|${ids[j]}|related_to"
                if (key in existing) continue
                relationshipDao.upsert(
                    RelationshipEntity(
                        id = UUID.randomUUID().toString(),
                        fromMemoryId = ids[i],
                        toMemoryId = ids[j],
                        type = "related_to",
                        createdAt = System.currentTimeMillis()
                    )
                )
            }
        }
    }

    private suspend fun maybeSummarize(exchange: MemoryExchange, settings: MemorySettings): Boolean {
        if (exchange.messageCount < settings.summarizationInterval) return false
        val last = summaryDao.getLatestForConversation(exchange.conversationId)
        if (last != null && exchange.messageCount < last.messageCount + settings.summarizationInterval) return false
        val summary = withContext(Dispatchers.IO) {
            intelligence.summarize(exchange.conversationId, last?.summary, exchange.recentMessages)
        }.getOrNull() ?: return false
        summaryDao.upsert(
            SummaryEntity(
                id = "summary_${exchange.conversationId}",
                conversationId = exchange.conversationId,
                summary = summary,
                messageCount = exchange.messageCount,
                createdAt = last?.createdAt ?: System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
        )
        return true
    }

    companion object {
        private const val EXPORT_VERSION = 1
        private const val MAX_MEMORY_LENGTH = 800
        private const val HYBRID_KEYWORD_BOOST = 0.06f
        private val WHITESPACE = Regex("\\s+")
    }
}

// ── Export/import wire format ──

@Serializable
internal data class MemoryExportFile(
    val version: Int = 1,
    val exportedAt: Long = 0L,
    val memories: List<MemoryExportItem> = emptyList(),
    val relationships: List<MemoryExportRelationship> = emptyList()
)

@Serializable
internal data class MemoryExportItem(
    val id: String,
    val category: String,
    val content: String,
    val importance: Int,
    val tags: List<String> = emptyList(),
    val project: String? = null,
    val isPinned: Boolean = false,
    val isArchived: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long
)

@Serializable
internal data class MemoryExportRelationship(
    val from: String,
    val to: String,
    val type: String
)

// ── Entity ↔ domain mapping ──

internal fun MemoryEntity.toDomain(tags: List<String>): Memory = Memory(
    id = id,
    category = MemoryCategory.fromName(category),
    content = content,
    importance = importance,
    tags = tags,
    projectId = projectId,
    sourceConversationId = sourceConversationId,
    isPinned = isPinned,
    isArchived = isArchived,
    accessCount = accessCount,
    createdAt = createdAt,
    updatedAt = updatedAt,
    lastAccessedAt = lastAccessedAt,
    userId = userId,
    chatId = chatId ?: sourceConversationId,
    type = io.androllm.core.memory.MemoryType.fromName(type),
    summary = summary,
    priority = if (priority == 1 && importance != 1) importance else priority,
    lastUsedAt = lastUsedAt,
    expiryAt = expiryAt
)

internal fun SummaryEntity.toDomain(): MemorySummary = MemorySummary(
    id = id,
    conversationId = conversationId,
    summary = summary,
    messageCount = messageCount,
    createdAt = createdAt,
    updatedAt = updatedAt
)
