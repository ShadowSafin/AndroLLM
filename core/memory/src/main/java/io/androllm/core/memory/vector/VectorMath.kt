package io.androllm.core.memory.vector

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Pure-Kotlin vector math. Embeddings are L2-normalized (both at write time
 * and for the query) so cosine similarity reduces to a dot product — this is
 * what keeps retrieval a sub-millisecond brute-force scan at mobile scale.
 */
object VectorMath {

    fun dot(a: FloatArray, b: FloatArray): Float {
        var sum = 0f
        var i = 0
        while (i < a.size) {
            sum += a[i] * b[i]
            i++
        }
        return sum
    }

    fun magnitude(v: FloatArray): Float {
        var sum = 0f
        for (x in v) sum += x * x
        return kotlin.math.sqrt(sum)
    }

    /**
     * Returns a unit-length copy of [v].
     */
    fun normalize(v: FloatArray): FloatArray {
        val mag = magnitude(v)
        if (mag == 0f) return v.copyOf()
        val out = FloatArray(v.size)
        val inv = 1f / mag
        for (i in v.indices) out[i] = v[i] * inv
        return out
    }

    /**
     * Cosine similarity in [0..1] (maps the raw -1..1 dot product). Vectors
     * do not need to be pre-normalized.
     */
    fun cosine(a: FloatArray, b: FloatArray): Float {
        val magA = magnitude(a)
        val magB = magnitude(b)
        if (magA == 0f || magB == 0f) return 0f
        return (dot(a, b) / (magA * magB) + 1f) / 2f
    }

    /**
     * Dot product over two already-normalized vectors, mapped to [0..1].
     */
    fun cosineNormalized(a: FloatArray, b: FloatArray): Float = (dot(a, b) + 1f) / 2f

    fun toBytes(v: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(v.size * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        buffer.asFloatBuffer().put(v)
        return buffer.array()
    }

    fun fromBytes(bytes: ByteArray): FloatArray =
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            .asFloatBuffer().run {
                val out = FloatArray(remaining())
                get(out)
                out
            }
}
