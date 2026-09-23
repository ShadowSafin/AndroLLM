package io.androllm.core.memory.ranking

import com.google.common.truth.Truth.assertThat
import io.androllm.core.memory.MemoryCategory
import io.androllm.core.memory.model.Memory
import io.androllm.core.memory.model.MemorySearchResult
import org.junit.Test

class MemoryRankerTest {

    private val ranker = MemoryRanker()
    private val now = 1_700_000_000_000L

    private fun memory(
        id: String,
        content: String,
        priority: Int = 3,
        updatedAt: Long = now,
        pinned: Boolean = false,
        accessCount: Int = 5,
        category: MemoryCategory = MemoryCategory.CUSTOM
    ) = Memory(
        id = id, category = category, content = content, importance = priority,
        createdAt = updatedAt - 1000, updatedAt = updatedAt,
        isPinned = pinned, accessCount = accessCount, priority = priority
    )

    @Test
    fun `semantic relevance dominates recency`() {
        val oldRelevant = MemorySearchResult(memory("old", "User prefers dark mode", updatedAt = now - 20 * 24 * 3600 * 1000L), 0.9f)
        val newIrrelevant = MemorySearchResult(memory("new", "User bought milk once", updatedAt = now), 0.1f)
        val ranked = ranker.rank(listOf(newIrrelevant, oldRelevant), now)
        assertThat(ranked.first().memory.id).isEqualTo("old")
    }

    @Test
    fun `pinned outranks equal-score unpinned`() {
        val pinned = MemorySearchResult(memory("p", "User prefers dark mode", pinned = true), 0.5f)
        val plain = MemorySearchResult(memory("u", "User prefers dark mode"), 0.5f)
        val ranked = ranker.rank(listOf(plain, pinned), now)
        assertThat(ranked.first().memory.id).isEqualTo("p")
    }

    @Test
    fun `preference category outranks custom at equal relevance`() {
        val pref = MemorySearchResult(
            memory("pref", "User prefers short prompts", category = MemoryCategory.PREFERENCES), 0.5f
        )
        val custom = MemorySearchResult(memory("cus", "User saw a bird"), 0.5f)
        val ranked = ranker.rank(listOf(custom, pref), now)
        assertThat(ranked.first().memory.id).isEqualTo("pref")
    }

    @Test
    fun `recency breaks ties between equally relevant memories`() {
        val older = MemorySearchResult(memory("older", "User prefers dark mode", updatedAt = now - 1000), 0.6f)
        val newer = MemorySearchResult(memory("newer", "User prefers dark mode", updatedAt = now), 0.6f)
        val ranked = ranker.rank(listOf(older, newer), now)
        assertThat(ranked.first().memory.id).isEqualTo("newer")
    }

    @Test
    fun `weak old memories decay below fresh equals`() {
        val weakOld = MemorySearchResult(
            memory("weak", "User saw a thing", priority = 1, updatedAt = now - 60 * 24 * 3600 * 1000L, accessCount = 0),
            0.6f
        )
        val fresh = MemorySearchResult(memory("fresh", "User saw a thing", priority = 1, updatedAt = now), 0.6f)
        val ranked = ranker.rank(listOf(weakOld, fresh), now)
        assertThat(ranked.first().memory.id).isEqualTo("fresh")
    }

    @Test
    fun `keyword match boosts equal semantic scores`() {
        val keyword = MemorySearchResult(memory("kw", "User prefers dark mode"), 0.5f, matchedByKeyword = true)
        val plain = MemorySearchResult(memory("pl", "User prefers dark mode"), 0.5f)
        val ranked = ranker.rank(listOf(plain, keyword), now)
        assertThat(ranked.first().memory.id).isEqualTo("kw")
    }

    @Test
    fun `ranking is deterministic for ties`() {
        val a = MemorySearchResult(memory("a", "User prefers dark mode", updatedAt = now), 0.5f)
        val b = MemorySearchResult(memory("b", "User prefers dark mode", updatedAt = now), 0.5f)
        assertThat(ranker.rank(listOf(a, b), now).map { it.memory.id })
            .isEqualTo(ranker.rank(listOf(b, a), now).map { it.memory.id })
    }
}
