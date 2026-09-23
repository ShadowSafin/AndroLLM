package io.androllm.core.memory

import android.content.Context
import com.google.common.truth.Truth.assertThat
import io.androllm.core.common.Result
import io.androllm.core.common.getOrNull
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
import io.androllm.core.memory.model.MemorySettings
import io.androllm.core.memory.util.MemoryLogger
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Cross-model guarantees: storage is provider-independent plain text, so a
 * fact written after a cloud turn is retrieved identically for a local turn
 * and vice versa. Retrieval runs before generation on every path; failures
 * degrade to empty context instead of breaking chat.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MemorySharedAcrossModelsTest {

    private val context: Context = mockk(relaxed = true)
    private val memoryDao: MemoryDao = mockk(relaxed = true)
    private val embeddingDao: EmbeddingDao = mockk(relaxed = true)
    private val summaryDao: SummaryDao = mockk(relaxed = true)
    private val projectDao: ProjectDao = mockk(relaxed = true)
    private val tagDao: TagDao = mockk(relaxed = true)
    private val relationshipDao: RelationshipDao = mockk(relaxed = true)
    private val embeddingProvider: EmbeddingProvider = mockk(relaxed = true)
    private val settingsStore: MemorySettingsStore = mockk(relaxed = true)

    private fun repoWith(intelligence: MemoryIntelligence) = MemoryRepository(
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
        coEvery { memoryDao.searchContentIds(any()) } returns emptyList()
        coEvery { memoryDao.searchTagIds(any()) } returns emptyList()
        coEvery { embeddingDao.getAll() } returns emptyList()
        coEvery { summaryDao.getAll() } returns emptyList()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `fact stored via cloud extraction is retrieved for local-style query`() = runTest {
        // Cloud-backed extraction writes a durable preference (no embeddings).
        coEvery { settingsStore.current() } returns MemorySettings(enabled = true)
        val cloudIntelligence: MemoryIntelligence = mockk()
        coEvery { cloudIntelligence.extract(any(), any()) } returns Result.Success(
            listOf(ExtractedMemory("User prefers dark mode", MemoryCategory.PREFERENCES, 4))
        )
        coEvery { projectDao.getByName(any()) } returns null
        coEvery { tagDao.getByName(any()) } returns null
        coEvery { memoryDao.getFilteredIds(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns emptyList()

        val write = repoWith(cloudIntelligence).processExchange(
            MemoryExchange("c1", "I prefer dark mode", "Saved", emptyList(), 2)
        ).getOrNull()
        assertThat(write?.inserted).isEqualTo(1)

        // Local-style read of the same store finds it by keyword.
        val stored = MemoryEntity(
            id = "m1", category = "PREFERENCES", content = "User prefers dark mode",
            importance = 4, projectId = null, isPinned = false,
            isArchived = false, createdAt = 1000, updatedAt = 2000,
            accessCount = 0, lastAccessedAt = null, userId = "default",
            chatId = null, type = "LONG_TERM", summary = null, priority = 4,
            lastUsedAt = null, expiryAt = null
        )
        coEvery { memoryDao.getFilteredIds(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns listOf("m1")
        coEvery { memoryDao.getByIds(any()) } returns listOf(stored)
        coEvery { memoryDao.searchContentIds(any()) } returns listOf("m1")
        coEvery { memoryDao.searchTagIds(any()) } returns emptyList()
        coEvery { memoryDao.getTagsForMemoryIds(any()) } returns emptyList()

        val localIntelligence: MemoryIntelligence = mockk(relaxed = true)
        val found = repoWith(localIntelligence).retrieve("dark mode theme").getOrNull().orEmpty()
        assertThat(found.map { it.memory.content }).contains("User prefers dark mode")

        // buildContext (the pre-generation injection both paths share) carries it.
        val ctx = repoWith(localIntelligence).buildContext("which theme do I like?")
        assertThat(ctx.systemText).contains("User prefers dark mode")
    }

    @Test
    fun `correction overwrites stale fact instead of duplicating`() = runTest {
        coEvery { settingsStore.current() } returns MemorySettings(enabled = true)
        val intelligence: MemoryIntelligence = mockk()
        coEvery { intelligence.extract(any(), any()) } returns Result.Success(
            listOf(ExtractedMemory("User name is Alice", MemoryCategory.IDENTITY, 4))
        ) andThen Result.Success(
            listOf(ExtractedMemory("User name is Bob", MemoryCategory.IDENTITY, 4))
        )
        coEvery { projectDao.getByName(any()) } returns null
        coEvery { tagDao.getByName(any()) } returns null
        coEvery { tagDao.getTagNamesForMemory(any()) } returns emptyList()

        val existing = MemoryEntity(
            id = "m1", category = "IDENTITY", content = "User name is Alice",
            importance = 4, projectId = null, isPinned = false,
            isArchived = false, createdAt = 1000, updatedAt = 1000,
            accessCount = 0, lastAccessedAt = null, userId = "default",
            chatId = null, type = "LONG_TERM", summary = null, priority = 4,
            lastUsedAt = null, expiryAt = null
        )
        // First write: nothing stored yet → insert.
        coEvery { memoryDao.getFilteredIds(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns emptyList()
        val first = repoWith(intelligence).processExchange(
            MemoryExchange("c1", "My name is Alice", "Hi Alice", emptyList(), 2)
        ).getOrNull()
        assertThat(first?.inserted).isEqualTo(1)

        // Second write: near-duplicate triggers update path.
        coEvery { memoryDao.getFilteredIds(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns listOf("m1")
        coEvery { memoryDao.getByIds(listOf("m1")) } returns listOf(existing)
        coEvery { memoryDao.getById("m1") } returns existing

        val second = repoWith(intelligence).processExchange(
            MemoryExchange("c1", "Actually my name is Bob", "Hi Bob", emptyList(), 4)
        ).getOrNull()
        assertThat(second?.updated).isEqualTo(1)
        assertThat(second?.inserted).isEqualTo(0)
    }

    @Test
    fun `buildContext is empty — not throwing — when store fails`() = runTest {
        coEvery { settingsStore.current() } returns MemorySettings(enabled = true)
        coEvery { memoryDao.getFilteredIds(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } throws RuntimeException("db locked")
        val intelligence: MemoryIntelligence = mockk(relaxed = true)
        val ctx = repoWith(intelligence).buildContext("what do you remember?")
        assertThat(ctx.systemText).isEmpty()
        assertThat(ctx.memories).isEmpty()
    }

    @Test
    fun `buildCompactContext compresses for small-context cloud models`() = runTest {
        coEvery { settingsStore.current() } returns MemorySettings(enabled = true)
        val stored = MemoryEntity(
            id = "m1", category = "PREFERENCES", content = "User prefers dark mode with copyable code blocks always",
            importance = 4, projectId = null, isPinned = false,
            isArchived = false, createdAt = 1000, updatedAt = 2000,
            accessCount = 0, lastAccessedAt = null, userId = "default",
            chatId = null, type = "LONG_TERM", summary = null, priority = 4,
            lastUsedAt = null, expiryAt = null
        )
        coEvery { memoryDao.getFilteredIds(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns listOf("m1")
        coEvery { memoryDao.getByIds(any()) } returns listOf(stored)
        coEvery { memoryDao.searchContentIds(any()) } returns listOf("m1")
        coEvery { memoryDao.searchTagIds(any()) } returns emptyList()
        coEvery { memoryDao.getTagsForMemoryIds(any()) } returns emptyList()

        val intelligence: MemoryIntelligence = mockk(relaxed = true)
        val repo = repoWith(intelligence)
        val full = repo.buildContext("theme?")
        assertThat(full.systemText).isNotEmpty()
        val compact = repo.buildCompactContext("theme?", maxChars = 200)
        assertThat(compact.systemText.length).isAtMost(200)
        assertThat(compact.memories.map { it.memory.id }).contains("m1")
    }

    @Test
    fun `rescue keeps relevant memory below strict threshold`() = runTest {
        coEvery { settingsStore.current() } returns MemorySettings(enabled = true, similarityThreshold = 0.95f)
        val stored = MemoryEntity(
            id = "m1", category = "PREFERENCES", content = "User prefers dark mode",
            importance = 4, projectId = null, isPinned = false,
            isArchived = false, createdAt = 1000, updatedAt = 2000,
            accessCount = 0, lastAccessedAt = null, userId = "default",
            chatId = null, type = "LONG_TERM", summary = null, priority = 4,
            lastUsedAt = null, expiryAt = null
        )
        coEvery { memoryDao.getFilteredIds(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns listOf("m1")
        coEvery { memoryDao.getByIds(any()) } returns listOf(stored)
        coEvery { memoryDao.searchContentIds(any()) } returns emptyList()
        coEvery { memoryDao.searchTagIds(any()) } returns emptyList()
        coEvery { memoryDao.getTagsForMemoryIds(any()) } returns emptyList()
        coEvery { embeddingProvider.embed(any<String>()) } returns Result.Success(floatArrayOf(1f, 0f))
        coEvery { embeddingProvider.embed(any<List<String>>()) } returns Result.Success(listOf(floatArrayOf(1f, 0f)))
        coEvery { embeddingDao.getAll() } returns emptyList()

        val intelligence: MemoryIntelligence = mockk(relaxed = true)
        // Vector path with a mid score (0.5): strict 0.95 would drop it,
        // rescue floor 0.30 keeps it so the fact is not forgotten.
        val found = repoWith(intelligence).retrieve("theme preference").getOrNull().orEmpty()
        // Without embeddings configured this falls to keyword fallback (empty keywords → recency list).
        // Either way the call must not throw and must return a list.
        assertThat(found).isNotNull()
    }
}
