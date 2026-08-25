package io.androllm.core.memory.hardening

import com.google.common.truth.Truth.assertThat
import io.androllm.core.memory.MemoryCategory
import io.androllm.core.memory.model.ExtractedMemory
import io.androllm.core.memory.model.Memory
import io.androllm.core.memory.model.MemoryExchange
import org.junit.Test

class MemoryHardeningHelperTest {

    private val helper = MemoryHardeningHelper()

    @Test
    fun `temporary context detection - just for now is temporary`() {
        assertThat(helper.isTemporaryContext("Use dark mode just for now", null)).isTrue()
        assertThat(helper.isTemporaryContext("Prefer light mode just for this chat", null)).isTrue()
        assertThat(helper.isTemporaryContext("Explain quantum computing just for now", null)).isTrue()
        assertThat(helper.isTemporaryContext("User prefers dark mode", null)).isFalse()
    }

    @Test
    fun `temporary detection via exchange - one-off request is temporary`() {
        val exchange = MemoryExchange("c1", "just for this chat, use dark mode", "ok", emptyList(), 2)
        assertThat(helper.isTemporaryContext("User prefers dark mode", exchange)).isTrue()
    }

    @Test
    fun `confidence scoring - high confidence for durable preference`() {
        val extracted = ExtractedMemory("User prefers copyable code blocks", MemoryCategory.PREFERENCES, 4)
        val exchange = MemoryExchange("c1", "Use copyable code blocks", "Got it", emptyList(), 2)
        val score = helper.confidenceScore(extracted, exchange)
        assertThat(score).isAtLeast(0.55)
        assertThat(helper.shouldCommit(extracted, exchange)).isTrue()
    }

    @Test
    fun `confidence scoring - low confidence for trivial hello is rejected`() {
        val extracted = ExtractedMemory("hello", MemoryCategory.CUSTOM, 1)
        val exchange = MemoryExchange("c1", "hello", "hi", emptyList(), 2)
        assertThat(helper.shouldCommit(extracted, exchange)).isFalse()
    }

    @Test
    fun `confidence scoring - manual save with empty exchange is grounded`() {
        val extracted = ExtractedMemory("User prefers light mode", MemoryCategory.PREFERENCES, 1)
        val emptyExchange = MemoryExchange("", "", "", emptyList(), 0)
        assertThat(helper.confidenceScore(extracted, emptyExchange)).isAtLeast(0.55)
        assertThat(helper.shouldCommit(extracted, emptyExchange)).isTrue()
    }

    @Test
    fun `contradiction detection - opposite preferences are contradictory`() {
        assertThat(helper.isContradictory("User prefers dark mode", "User prefers light mode")).isTrue()
        assertThat(helper.isContradictory("User likes cats", "User dislikes cats")).isTrue()
        assertThat(helper.isContradictory("User prefers dark mode", "User prefers dark mode")).isFalse()
        assertThat(helper.isContradictory("User prefers dark mode", "User lives in Tokyo")).isFalse()
    }

    @Test
    fun `conflict resolution - newer higher priority wins`() {
        val existing = Memory(
            id = "id1", category = MemoryCategory.PREFERENCES, content = "User prefers dark mode",
            importance = 3, createdAt = 1000, updatedAt = 1000, lastAccessedAt = 1000, priority = 3
        )
        // Newer, same priority should win (new supersedes)
        val winner = helper.resolveConflict("User prefers light mode", 3, 2000, existing)
        assertThat(winner).isNull() // null means new wins
        // Older, lower priority should lose
        val winner2 = helper.resolveConflict("User prefers light mode", 1, 500, existing)
        assertThat(winner2?.id).isEqualTo("id1")
    }

    @Test
    fun `deterministic dedupe - normalized comparison is case and punctuation insensitive`() {
        assertThat(helper.normalizeForDedupe("User prefers dark mode.")).isEqualTo(helper.normalizeForDedupe("user prefers  dark  mode"))
        assertThat(helper.normalizeForDedupe("User prefers dark mode!")).isEqualTo(helper.normalizeForDedupe("User prefers dark mode"))
    }

    @Test
    fun `contradiction filtering among retrieved - keeps winner`() {
        val existing = listOf(
            Memory(id = "a", category = MemoryCategory.PREFERENCES, content = "User prefers dark mode", importance = 3, createdAt = 1000, updatedAt = 1000),
            Memory(id = "b", category = MemoryCategory.PREFERENCES, content = "User prefers light mode", importance = 4, createdAt = 2000, updatedAt = 2000)
        )
        val contradictions = helper.findContradictions("User prefers light mode", existing)
        assertThat(contradictions).isNotEmpty()
    }
}
