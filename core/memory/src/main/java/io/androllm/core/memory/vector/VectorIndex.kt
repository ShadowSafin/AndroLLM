package io.androllm.core.memory.vector

import io.androllm.core.memory.vector.VectorIndex.ScoredId
import java.util.concurrent.ConcurrentHashMap

/**
 * Abstraction over the vector store used for similarity search.
 *
 * The default implementation ([CosineVectorIndex]) keeps embeddings in memory
 * (lazily loaded from Room BLOBs) and brute-force scans them — sub-millisecond
 * for the hundreds-to-low-thousands of vectors a mobile memory store holds.
 * The interface is intentionally narrow so a native index (e.g. sqlite-vec)
 * can be swapped in later without touching the retrieval logic.
 */
interface VectorIndex {

    val size: Int

    /** Number of dimensions every stored vector must have. */
    val dimension: Int

    fun upsert(id: String, vector: FloatArray)

    fun upsertAll(entries: Map<String, FloatArray>)

    fun remove(id: String)

    fun clear()

    /**
     * Returns up to [topK] entries ordered by descending similarity to
     * [query]. When [candidates] is non-null, only those ids are considered
     * (metadata pre-filtering).
     */
    fun search(query: FloatArray, topK: Int, candidates: Collection<String>? = null): List<ScoredId>

    data class ScoredId(val id: String, val score: Float)
}

/**
 * In-memory brute-force cosine index over normalized vectors.
 * Thread-safe: concurrent reads and writes are supported.
 */
class CosineVectorIndex(override val dimension: Int) : VectorIndex {

    private val vectors = ConcurrentHashMap<String, FloatArray>()

    override val size: Int get() = vectors.size

    override fun upsert(id: String, vector: FloatArray) {
        if (vector.size != dimension) {
            throw IllegalArgumentException("Vector dimension ${vector.size} != index dimension $dimension")
        }
        vectors[id] = VectorMath.normalize(vector)
    }

    override fun upsertAll(entries: Map<String, FloatArray>) {
        for ((id, v) in entries) upsert(id, v)
    }

    override fun remove(id: String) {
        vectors.remove(id)
    }

    override fun clear() {
        vectors.clear()
    }

    override fun search(
        query: FloatArray,
        topK: Int,
        candidates: Collection<String>?
    ): List<ScoredId> {
        if (topK <= 0 || vectors.isEmpty()) return emptyList()
        val q = VectorMath.normalize(query)

        val scored = ArrayList<ScoredId>(minOf(vectors.size, topK))
        if (candidates == null) {
            for ((id, v) in vectors) {
                scored.add(ScoredId(id, VectorMath.cosineNormalized(q, v)))
            }
        } else {
            for (id in candidates) {
                val v = vectors[id] ?: continue
                scored.add(ScoredId(id, VectorMath.cosineNormalized(q, v)))
            }
        }
        scored.sortByDescending { it.score }
        return scored.take(topK)
    }
}
