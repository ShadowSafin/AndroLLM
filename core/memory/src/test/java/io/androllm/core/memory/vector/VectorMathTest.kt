package io.androllm.core.memory.vector

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VectorMathTest {

    @Test
    fun `normalize produces a unit vector`() {
        val v = VectorMath.normalize(floatArrayOf(3f, 4f))
        assertEquals(1f, VectorMath.magnitude(v), 1e-6f)
        assertEquals(0.6f, v[0], 1e-6f)
        assertEquals(0.8f, v[1], 1e-6f)
    }

    @Test
    fun `normalize of zero vector is safe`() {
        val v = VectorMath.normalize(floatArrayOf(0f, 0f, 0f))
        assertArrayEquals(floatArrayOf(0f, 0f, 0f), v, 0f)
    }

    @Test
    fun `cosine of identical vectors is one`() {
        val a = floatArrayOf(1f, 2f, 3f)
        assertEquals(1f, VectorMath.cosine(a, a), 1e-5f)
    }

    @Test
    fun `cosine of orthogonal vectors is zero point five`() {
        // Mapped cosine (dot+1)/2: orthogonal dot=0 -> 0.5
        val a = floatArrayOf(1f, 0f)
        val b = floatArrayOf(0f, 1f)
        assertEquals(0.5f, VectorMath.cosine(a, b), 1e-5f)
    }

    @Test
    fun `cosine of opposite vectors is zero`() {
        val a = floatArrayOf(1f, 0f)
        val b = floatArrayOf(-1f, 0f)
        assertEquals(0f, VectorMath.cosine(a, b), 1e-5f)
    }

    @Test
    fun `normalized dot equals cosine`() {
        val a = VectorMath.normalize(floatArrayOf(0.5f, -1.2f, 3.3f))
        val b = VectorMath.normalize(floatArrayOf(-2f, 1.1f, 0.4f))
        assertEquals(VectorMath.cosine(a, b), VectorMath.cosineNormalized(a, b), 1e-5f)
    }

    @Test
    fun `bytes roundtrip preserves floats`() {
        val original = floatArrayOf(0.123456f, -1.5f, 42f, 0f)
        val restored = VectorMath.fromBytes(VectorMath.toBytes(original))
        assertArrayEquals(original, restored, 1e-6f)
    }

    @Test
    fun `empty vector converts to empty bytes`() {
        assertTrue(VectorMath.toBytes(FloatArray(0)).isEmpty())
    }
}
