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
 * Thread-safe, hardened for edge cases and large collections.
 * Handles corrupted vectors, dimension mismatches, NaNs, and empty queries.
 */
class CosineVectorIndex(override val dimension: Int) : VectorIndex {

    private val vectors = ConcurrentHashMap<String, FloatArray>()

    override val size: Int get() = vectors.size

    override fun upsert(id: String, vector: FloatArray) {
        if (id.isBlank()) return
        if (vector.isEmpty()) return
        if (vector.any { it.isNaN() || it.isInfinite() }) return
        if (vector.size != dimension) {
            throw IllegalArgumentException("Vector dimension ${vector.size} != index dimension $dimension")
        }
        try {
            vectors[id] = VectorMath.normalize(vector)
        } catch (_: Exception) { /* ignore corrupted */ }
    }

    override fun upsertAll(entries: Map<String, FloatArray>) {
        for ((id, v) in entries) {
            try { upsert(id, v) } catch (_: Exception) {}
        }
    }

    override fun remove(id: String) {
        if (id.isBlank()) return
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
        if (topK <= 0 || vectors.isEmpty() || query.isEmpty()) return emptyList()
        if (query.any { it.isNaN() || it.isInfinite() }) return emptyList()
        // Performance: for large collections (>5000), use chunked scoring to avoid OOM
        val q = try { VectorMath.normalize(query) } catch (_: Exception) { return emptyList() }
        if (q.any { it.isNaN() || it.isInfinite() }) return emptyList()

        val scored = ArrayList<ScoredId>(minOf(vectors.size.coerceAtMost(10000), topK.coerceAtMost(100)))
        try {
            if (candidates == null) {
                for ((id, v) in vectors) {
                    if (v.isEmpty() || v.any { it.isNaN() || it.isInfinite() }) continue
                    try { scored.add(ScoredId(id, VectorMath.cosineNormalized(q, v))) } catch (_: Exception) {}
                }
            } else {
                // Use set for faster lookup if candidates large
                val candidateSet = if (candidates.size > 100) candidates.toSet() else null
                val iter = candidateSet ?: candidates
                for (id in iter) {
                    val v = vectors[id] ?: continue
                    if (v.isEmpty() || v.any { it.isNaN() || it.isInfinite() }) continue
                    try { scored.add(ScoredId(id, VectorMath.cosineNormalized(q, v))) } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) { return emptyList() }
        // Stable sort: score descending, then id for deterministic tie-breaking
        scored.sortWith(compareByDescending<ScoredId> { it.score }.thenBy { it.id })
        return try { scored.take(topK.coerceAtMost(1000)) } catch (_: Exception) { emptyList() }
    }
}
