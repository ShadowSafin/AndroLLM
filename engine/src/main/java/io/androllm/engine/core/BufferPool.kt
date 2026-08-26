package io.androllm.engine.core

import java.util.concurrent.ConcurrentLinkedDeque

/**
 * Bounded pool of reusable buffers for the inference pipeline.
 *
 * Replaces per-token allocations with pooled buffer reuse. Every buffer is
 * returned to the pool after use; the pool is bounded to prevent unbounded
 * memory growth.
 *
 * Thread-safe: multiple coroutine dispatchers may borrow/return concurrently.
 */
object BufferPool {

    private const val MAX_STRING_BUILDERS = 8
    private const val MAX_BYTE_ARRAYS = 8
    private const val MAX_CHAR_ARRAYS = 4

    /** Common buffer sizes for the inference pipeline. */
    const val SMALL = 256
    const val MEDIUM = 1024
    const val LARGE = 2048
    const val XLARGE = 4096

    private val stringBuilders = ConcurrentLinkedDeque<PooledStringBuilder>()
    private val byteArrays = ConcurrentLinkedDeque<PooledByteArray>()
    private val charArrays = ConcurrentLinkedDeque<PooledCharArray>()

    /** Total borrows minus returns — should be <= pool size at all times. */
    private val outstandingBuffers = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * Borrows a [StringBuilder] from the pool with at least [minCapacity]
     * chars of capacity. The caller MUST call [returnBuilder] when done.
     */
    fun borrowBuilder(minCapacity: Int = MEDIUM): PooledStringBuilder {
        val pooled = stringBuilders.pollFirst()
            ?: PooledStringBuilder(StringBuilder(minCapacity.coerceAtLeast(MEDIUM)))
        pooled.builder.setLength(0)
        if (pooled.builder.capacity() < minCapacity) {
            pooled.builder = StringBuilder(minCapacity)
        }
        outstandingBuffers.incrementAndGet()
        return pooled
    }

    /**
     * Returns a [StringBuilder] to the pool. Clears it for next use.
     */
    fun returnBuilder(builder: PooledStringBuilder) {
        builder.builder.setLength(0)
        if (stringBuilders.size < MAX_STRING_BUILDERS) {
            stringBuilders.addFirst(builder)
        }
        outstandingBuffers.decrementAndGet()
    }

    /**
     * Borrows a [ByteArray] from the pool with at least [minSize] bytes.
     * The caller MUST call [returnBytes] when done.
     */
    fun borrowBytes(minSize: Int = MEDIUM): PooledByteArray {
        val pooled = byteArrays.pollFirst()
            ?: PooledByteArray(ByteArray(minSize.coerceAtLeast(MEDIUM)))
        if (pooled.array.size < minSize) {
            pooled.array = ByteArray(minSize)
        }
        outstandingBuffers.incrementAndGet()
        return pooled
    }

    /**
     * Returns a [ByteArray] to the pool.
     */
    fun returnBytes(bytes: PooledByteArray) {
        if (byteArrays.size < MAX_BYTE_ARRAYS) {
            byteArrays.addFirst(bytes)
        }
        outstandingBuffers.decrementAndGet()
    }

    /**
     * Borrows a [CharArray] from the pool with at least [minSize] chars.
     * The caller MUST call [returnChars] when done.
     */
    fun borrowChars(minSize: Int = MEDIUM): PooledCharArray {
        val pooled = charArrays.pollFirst()
            ?: PooledCharArray(CharArray(minSize.coerceAtLeast(MEDIUM)))
        if (pooled.array.size < minSize) {
            pooled.array = CharArray(minSize)
        }
        outstandingBuffers.incrementAndGet()
        return pooled
    }

    /**
     * Returns a [CharArray] to the pool.
     */
    fun returnChars(chars: PooledCharArray) {
        if (charArrays.size < MAX_CHAR_ARRAYS) {
            charArrays.addFirst(chars)
        }
        outstandingBuffers.decrementAndGet()
    }

    /**
     * Clears the entire pool (called on engine release).
     */
    fun clear() {
        stringBuilders.clear()
        byteArrays.clear()
        charArrays.clear()
        outstandingBuffers.set(0)
    }

    /**
     * Aggressive-fit: trim pooled buffers to minimum to free RAM before
     * loading a large model. Keeps at most 2 builders and clears all byte arrays.
     */
    fun trimForLowMemory() {
        synchronized(stringBuilders) {
            while (stringBuilders.size > 2) stringBuilders.pollLast()
        }
        byteArrays.clear()
        charArrays.clear()
    }

    /**
     * Hint that buffers should be released promptly after each prompt when
     * the model is in aggressive-fit mode (compact, no lingering caches).
     */
    fun releaseAggressively() {
        // Keep pool small under memory pressure — reuse still works, but we avoid holding large arrays.
        if (stringBuilders.size > 4) {
            while (stringBuilders.size > 2) stringBuilders.pollLast()
        }
    }

    /**
     * Returns pool usage statistics for diagnostics.
     */
    fun stats(): PoolStats = PoolStats(
        stringBuilderCount = stringBuilders.size,
        byteArrayCount = byteArrays.size,
        charArrayCount = charArrays.size,
        outstandingBuffers = outstandingBuffers.get(),
        maxStringBuilders = MAX_STRING_BUILDERS,
        maxByteArrays = MAX_BYTE_ARRAYS,
        maxCharArrays = MAX_CHAR_ARRAYS
    )

    /** Borrowed StringBuilder with a pool tag for safe return. */
    class PooledStringBuilder(var builder: StringBuilder) {
        fun string(): String = builder.toString()
    }

    /** Borrowed ByteArray with a pool tag for safe return. */
    class PooledByteArray(var array: ByteArray)

    /** Borrowed CharArray with a pool tag for safe return. */
    class PooledCharArray(var array: CharArray)

    data class PoolStats(
        val stringBuilderCount: Int,
        val byteArrayCount: Int,
        val charArrayCount: Int,
        val outstandingBuffers: Int,
        val maxStringBuilders: Int,
        val maxByteArrays: Int,
        val maxCharArrays: Int
    ) {
        fun summary(): String = buildString {
            append("buffer_pool: ")
            append("builders=$stringBuilderCount/$maxStringBuilders ")
            append("bytes=$byteArrayCount/$maxByteArrays ")
            append("chars=$charArrayCount/$maxCharArrays ")
            append("outstanding=$outstandingBuffers")
        }
    }
}
