package io.androllm.core.memory

import android.content.Context
import io.androllm.core.common.Result
import io.androllm.core.common.getOrNull
import io.androllm.core.common.isSuccess
import io.androllm.core.memory.context.ContextBuilder
import io.androllm.core.memory.db.dao.EmbeddingDao
import io.androllm.core.memory.db.dao.MemoryDao
import io.androllm.core.memory.db.dao.ProjectDao
import io.androllm.core.memory.db.dao.RelationshipDao
import io.androllm.core.memory.db.dao.SummaryDao
import io.androllm.core.memory.db.dao.TagDao
import io.androllm.core.memory.db.entity.MemoryEntity
import io.androllm.core.memory.embedding.EmbeddingProvider
import io.androllm.core.memory.intelligence.MemoryIntelligence
import io.androllm.core.memory.model.ExtractedMemory
import io.androllm.core.memory.model.MemoryExchange
import io.androllm.core.memory.model.MemorySearchFilters
import io.androllm.core.memory.model.MemorySettings
import io.androllm.core.memory.model.MemoryWriteAction
import io.androllm.core.memory.util.MemoryLogger
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for the memory update/retrieval pipeline with mocked persistence.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MemoryRepositoryTest {

    private val context: Context = mockk(relaxed = true)
    private val memoryDao: MemoryDao = mockk(relaxed = true)
    private val embeddingDao: EmbeddingDao = mockk(relaxed = true)
    private val summaryDao: SummaryDao = mockk(relaxed = true)
    private val projectDao: ProjectDao = mockk(relaxed = true)
    private val tagDao: TagDao = mockk(relaxed = true)
    private val relationshipDao: RelationshipDao = mockk(relaxed = true)
    private val embeddingProvider: EmbeddingProvider = mockk(relaxed = true)
    private val intelligence: MemoryIntelligence = mockk(relaxed = true)
    private val settingsStore: MemorySettingsStore = mockk(relaxed = true)

    private fun repo() = MemoryRepository(
        context = context,
        memoryDao = memoryDao,
        embeddingDao = embeddingDao,
        summaryDao = summaryDao,
        projectDao = projectDao,
        tagDao = tagDao,
        relationshipDao = relationshipDao,
        embeddingProvider = embeddingProvider,
        intelligence = intelligence,
        settingsStore = settingsStore,
        contextBuilder = ContextBuilder(),
        logger = MemoryLogger()
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        coEvery { memoryDao.getFilteredIds(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns emptyList()
        coEvery { memoryDao.getByIds(any()) } returns emptyList()
        coEvery { embeddingDao.getAll() } returns emptyList()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `processExchange is a no-op when memory disabled`() = runTest {
        coEvery { settingsStore.current() } returns MemorySettings(enabled = false)

        val summary = repo().processExchange(
            MemoryExchange("c1", "hi", "hello", emptyList(), 2)
        ).getOrNull()

        assertNotNull(summary)
        assertEquals(0, summary!!.inserted)
        coVerify(exactly = 0) { intelligence.extract(any(), any()) }
    }

    @Test
    fun `processExchange inserts extracted memories with embeddings`() = runTest {
        coEvery { settingsStore.current() } returns MemorySettings(
            enabled = true,
            extractionEnabled = true,
            embeddingModelPath = "/models/embed.gguf"
        )
        coEvery { intelligence.extract(any(), any()) } returns Result.Success(
            listOf(ExtractedMemory("User prefers dark mode", io.androllm.core.memory.MemoryCategory.PREFERENCES, 3, listOf("ui")))
        )
        coEvery { embeddingProvider.embed(any<String>()) } returns Result.Success(floatArrayOf(1f, 0f, 0f, 0f))
        coEvery { projectDao.getByName(any()) } returns null
        coEvery { tagDao.getByName(any()) } returns null

        val summary = repo().processExchange(
            MemoryExchange("c1", "I prefer dark mode", "Got it!", listOf("user" to "I prefer dark mode"), 2)
        ).getOrNull()

        assertNotNull(summary)
        assertEquals(1, summary!!.inserted)
        coVerify(exactly = 1) { embeddingDao.upsert(any()) }
        coVerify(exactly = 1) { memoryDao.upsert(any()) }
    }

    @Test
    fun `exact duplicate content updates instead of inserting`() = runTest {
        coEvery { settingsStore.current() } returns MemorySettings()

        val repo = repo()
        val first = repo.saveMemory(io.androllm.core.memory.MemoryCategory.PREFERENCES, "User prefers dark mode")
        assertTrue(first.isSuccess())
        assertEquals(MemoryWriteAction.INSERTED, first.getOrNull()?.action)
        val id = first.getOrNull()?.memoryId

        val existing = MemoryEntity(
            id = id!!,
            category = "PREFERENCES",
            content = "User prefers dark mode",
            createdAt = 0L,
            updatedAt = 0L
        )
        coEvery { memoryDao.getFilteredIds(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns listOf(id)
        coEvery { memoryDao.getByIds(any()) } returns listOf(existing)
        coEvery { memoryDao.getById(id) } returns existing
        coEvery { tagDao.getTagNamesForMemory(id) } returns emptyList()

        val second = repo.saveMemory(io.androllm.core.memory.MemoryCategory.PREFERENCES, "User prefers dark mode")
        assertEquals(MemoryWriteAction.UPDATED, second.getOrNull()?.action)
        coVerify(exactly = 1) { memoryDao.update(any()) }
    }

    @Test
    fun `retrieve falls back to keyword when no vector index`() = runTest {
        coEvery { settingsStore.current() } returns MemorySettings(enabled = true, retrievalCount = 5)
        coEvery { memoryDao.getFilteredIds(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns listOf("a", "b")
        coEvery { memoryDao.getByIds(any()) } returns listOf(
            MemoryEntity("a", "PREFERENCES", "User likes Kotlin", importance = 3, createdAt = 1L, updatedAt = 2L),
            MemoryEntity("b", "CUSTOM", "unrelated", createdAt = 1L, updatedAt = 1L)
        )
        coEvery { memoryDao.searchContentIds(any()) } returns listOf("a")
        coEvery { memoryDao.searchTagIds(any()) } returns emptyList()

        val results = repo().retrieve("kotlin").getOrNull().orEmpty()
        assertEquals("a", results.first().memory.id)
        assertTrue(results.first().matchedByKeyword)
    }

    @Test
    fun `retrieve returns empty when no candidates match filters`() = runTest {
        coEvery { settingsStore.current() } returns MemorySettings(enabled = true)
        coEvery { memoryDao.getFilteredIds(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns emptyList()

        val results = repo().retrieve("anything", MemorySearchFilters(projectId = "nope")).getOrNull().orEmpty()
        assertTrue(results.isEmpty())
    }

    @Test
    fun `inspector stats reflect stored counts`() = runTest {
        coEvery { settingsStore.current() } returns MemorySettings(enabled = true, retrievalCount = 7, similarityThreshold = 0.8f)
        coEvery { memoryDao.count() } returns 12
        coEvery { embeddingDao.count() } returns 10
        coEvery { projectDao.count() } returns 2
        coEvery { tagDao.count() } returns 5
        coEvery { summaryDao.count() } returns 1
        coEvery { relationshipDao.count() } returns 3

        val stats = repo().getInspectorStats()
        assertEquals(12, stats.memoryCount)
        assertEquals(10, stats.embeddingCount)
        assertEquals(7, stats.retrievalCount)
        assertEquals(0.8f, stats.similarityThreshold, 1e-6f)
        assertTrue(stats.enabled)
    }

    @Test
    fun `memories are stored without embeddings when no embedding source exists`() = runTest {
        coEvery { settingsStore.current() } returns MemorySettings(
            enabled = true,
            extractionEnabled = true
            // No local path, no cloud id ? embeddings skipped, memory still saved.
        )
        coEvery { intelligence.extract(any(), any()) } returns Result.Success(
            listOf(ExtractedMemory("User codes in Kotlin", io.androllm.core.memory.MemoryCategory.SKILLS, 2))
        )
        coEvery { projectDao.getByName(any()) } returns null
        coEvery { tagDao.getByName(any()) } returns null

        val summary = repo().processExchange(
            MemoryExchange("c1", "I code in Kotlin", "Great!", emptyList(), 1)
        ).getOrNull()

        assertNotNull(summary)
        assertEquals(1, summary!!.inserted)
        coVerify(exactly = 1) { memoryDao.upsert(any()) }
        coVerify(exactly = 0) { embeddingDao.upsert(any()) }
    }

    @Test
    fun `embedPendingMemories drains only memories missing vectors`() = runTest {
        coEvery { settingsStore.current() } returns MemorySettings(enabled = true, embeddingModelPath = "/models/embed.gguf")
        coEvery { memoryDao.getMemoryIdsWithoutEmbeddings() } returns listOf("m1", "m2")
        coEvery { memoryDao.getByIds(listOf("m1", "m2")) } returns listOf(
            MemoryEntity("m1", "PREFERENCES", "User likes tea", createdAt = 1L, updatedAt = 1L),
            MemoryEntity("m2", "CUSTOM", "Note", createdAt = 1L, updatedAt = 1L)
        )
        coEvery { embeddingProvider.embed(any<List<String>>()) } returns Result.Success(
            listOf(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f))
        )

        val count = repo().embedPendingMemories().getOrNull()

        assertEquals(2, count)
        coVerify(exactly = 1) { embeddingDao.upsertAll(any()) }
    }

    @Test
    fun `embedPendingMemories is a no-op without any embedding source`() = runTest {
        coEvery { settingsStore.current() } returns MemorySettings(enabled = true)

        val count = repo().embedPendingMemories().getOrNull()

        assertEquals(0, count)
        coVerify(exactly = 0) { memoryDao.getMemoryIdsWithoutEmbeddings() }
    }
}


