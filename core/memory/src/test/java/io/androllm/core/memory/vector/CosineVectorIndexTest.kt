package io.androllm.core.memory.vector

import io.androllm.core.memory.vector.VectorIndex.ScoredId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CosineVectorIndexTest {

    private fun index() = CosineVectorIndex(dimension = 3)

    @Test
    fun `search orders results by descending similarity`() {
        val idx = index()
        idx.upsert("a", floatArrayOf(1f, 0f, 0f))
        idx.upsert("b", floatArrayOf(0f, 1f, 0f))
        idx.upsert("c", floatArrayOf(0.9f, 0.1f, 0f))

        val results = idx.search(floatArrayOf(1f, 0f, 0f), topK = 3)
        assertEquals("a", results[0].id)
        assertEquals("c", results[1].id)
        assertEquals("b", results[2].id)
        assertTrue(results[0].score >= results[1].score)
        assertTrue(results[1].score >= results[2].score)
    }

    @Test
    fun `search respects topK`() {
        val idx = index()
        idx.upsertAll(
            mapOf(
                "a" to floatArrayOf(1f, 0f, 0f),
                "b" to floatArrayOf(0f, 1f, 0f),
                "c" to floatArrayOf(0f, 0f, 1f)
            )
        )
        val results = idx.search(floatArrayOf(1f, 0f, 0f), topK = 2)
        assertEquals(2, results.size)
    }

    @Test
    fun `candidates restricts the search space`() {
        val idx = index()
        idx.upsert("a", floatArrayOf(1f, 0f, 0f))
        idx.upsert("b", floatArrayOf(0f, 1f, 0f))

        val results = idx.search(floatArrayOf(1f, 0f, 0f), topK = 5, candidates = listOf("b"))
        assertEquals(listOf("b"), results.map { it.id })
    }

    @Test
    fun `candidates that are absent are skipped`() {
        val idx = index()
        idx.upsert("a", floatArrayOf(1f, 0f, 0f))
        val results = idx.search(floatArrayOf(1f, 0f, 0f), topK = 5, candidates = listOf("zzz"))
        assertTrue(results.isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `upsert rejects wrong dimension`() {
        index().upsert("a", floatArrayOf(1f, 0f))
    }

    @Test
    fun `remove drops an entry`() {
        val idx = index()
        idx.upsert("a", floatArrayOf(1f, 0f, 0f))
        idx.remove("a")
        assertTrue(idx.size == 0)
        assertTrue(idx.search(floatArrayOf(1f, 0f, 0f), 5).isEmpty())
    }

    @Test
    fun `clear empties the index`() {
        val idx = index()
        idx.upsert("a", floatArrayOf(1f, 0f, 0f))
        idx.clear()
        assertEquals(0, idx.size)
    }

    @Test
    fun `same vectors score one`() {
        val idx = index()
        idx.upsert("a", floatArrayOf(0.5f, 0.5f, 0.5f))
        val results = idx.search(floatArrayOf(0.5f, 0.5f, 0.5f), 1)
        assertEquals(1f, results.first().score, 1e-5f)
    }
}
