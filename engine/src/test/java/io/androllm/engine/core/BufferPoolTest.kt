package io.androllm.engine.core

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.atomic.AtomicInteger

class BufferPoolTest {

    @Test
    fun borrowReturnsNonEmptyBuilder() {
        val buf = BufferPool.borrowBuilder()
        assertNotNull(buf)
        assertEquals(0, buf.builder.length)
        BufferPool.returnBuilder(buf)
    }

    @Test
    fun returnAndReuseReturnsSameInstance() {
        val first = BufferPool.borrowBuilder()
        first.builder.append("test data")
        BufferPool.returnBuilder(first)

        val second = BufferPool.borrowBuilder()
        assertSame(first, second)
        assertEquals(0, second.builder.length)
        BufferPool.returnBuilder(second)
    }

    @Test
    fun multipleBorrowsAndReturns() {
        val buffers = mutableListOf<BufferPool.PooledStringBuilder>()
        repeat(8) {
            val buf = BufferPool.borrowBuilder()
            buf.builder.append("buffer-$it")
            buffers.add(buf)
        }
        buffers.forEach { BufferPool.returnBuilder(it) }

        val reused = BufferPool.borrowBuilder()
        assertEquals(0, reused.builder.length)
        BufferPool.returnBuilder(reused)
    }

    @Test
    fun threadedBorrowReturnIsSafe() {
        val threads = 8
        val iterations = 100
        val barrier = CyclicBarrier(threads)
        val errors = AtomicInteger(0)

        val latch = java.util.concurrent.CountDownLatch(threads)
        repeat(threads) {
            Thread {
                barrier.await()
                repeat(iterations) {
                    try {
                        val buf = BufferPool.borrowBuilder()
                        buf.builder.append("x")
                        Thread.yield()
                        BufferPool.returnBuilder(buf)
                    } catch (e: Exception) {
                        errors.incrementAndGet()
                    }
                }
                latch.countDown()
            }.start()
        }

        latch.await(2, java.util.concurrent.TimeUnit.SECONDS)
        assertEquals("No errors in threaded pool access", 0, errors.get())
    }

    @Test
    fun borrowBytesWorks() {
        val buf = BufferPool.borrowBytes(1024)
        assertNotNull(buf)
        assertTrue(buf.array.size >= 1024)
        BufferPool.returnBytes(buf)
    }

    @Test
    fun borrowCharsWorks() {
        val buf = BufferPool.borrowChars(512)
        assertNotNull(buf)
        assertTrue(buf.array.size >= 512)
        BufferPool.returnChars(buf)
    }

    @Test
    fun clearResetsPool() {
        val buf = BufferPool.borrowBuilder()
        buf.builder.append("data")
        BufferPool.returnBuilder(buf)

        BufferPool.clear()
        val stats = BufferPool.stats()
        assertEquals(0, stats.stringBuilderCount)
        assertEquals(0, stats.byteArrayCount)
        assertEquals(0, stats.charArrayCount)
    }

    @Test
    fun statsReported() {
        val stats = BufferPool.stats()
        assertTrue("stringBuilderCount >= 0", stats.stringBuilderCount >= 0)
        assertTrue("maxStringBuilders > 0", stats.maxStringBuilders > 0)
        assertTrue("summary is non-empty", stats.summary().isNotEmpty())
    }
}
