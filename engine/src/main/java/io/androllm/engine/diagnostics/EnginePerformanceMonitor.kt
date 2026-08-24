package io.androllm.engine.diagnostics

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Lightweight pipeline-stage profiler for the LiteRT inference engine.
 *
 * Tracks wall-clock time for every stage of the inference pipeline:
 * - Model initialization
 * - Tokenizer loading
 * - Prompt formatting
 * - Prefill (prompt evaluation)
 * - First-token latency
 * - Decode per token
 * - Streaming overhead
 * - Response parsing
 * - UI update cost
 * - Backend switching
 *
 * All operations are lock-free and allocation-free in the hot path.
 * The profiler is safe to call from any thread.
 */
object EnginePerformanceMonitor {

    /** A single timing measurement. */
    data class Timing(
        val stage: String,
        val durationNs: Long,
        val metadata: Map<String, String> = emptyMap()
    ) {
        val durationMs: Double get() = durationNs / 1_000_000.0
    }

    /** Aggregated statistics for a stage across multiple measurements. */
    data class StageStats(
        val stage: String,
        val count: Long,
        val totalMs: Double,
        val minMs: Double,
        val maxMs: Double,
        val avgMs: Double,
        val lastMs: Double
    )

    /** Current state of an in-flight stage. */
    private class StageState {
        val startNs = AtomicLong(0L)
        @Volatile var active = false
    }

    /** Pre-allocated stage states — avoids map lookups in the hot path. */
    private val stages = ConcurrentHashMap<String, StageState>()

    /** Recent timings ring buffer (last 128 per stage). */
    private val recentTimings = ConcurrentHashMap<String, ArrayDeque<Long>>()

    /** Aggregate counters. */
    private val totalCounts = ConcurrentHashMap<String, AtomicLong>()
    private val totalDurations = ConcurrentHashMap<String, AtomicLong>()
    private val minDurations = ConcurrentHashMap<String, AtomicLong>()
    private val maxDurations = ConcurrentHashMap<String, AtomicLong>()
    private val lastDurations = ConcurrentHashMap<String, AtomicLong>()

    private const val MAX_RECENT = 128

    /**
     * Starts timing a pipeline stage. Must be paired with [endStage].
     * Returns immediately if the stage is already active (nested calls ignored).
     */
    fun startStage(stage: String) {
        val state = stages.getOrPut(stage) { StageState() }
        if (state.active) return // nested call — ignore
        state.startNs.set(System.nanoTime())
        state.active = true
    }

    /**
     * Ends timing a pipeline stage and records the measurement.
     * Safe to call even if the stage was not started (no-op).
     */
    fun endStage(stage: String, metadata: Map<String, String> = emptyMap()): Timing? {
        val state = stages[stage] ?: return null
        if (!state.active) return null
        val elapsed = System.nanoTime() - state.startNs.get()
        state.active = false
        recordMeasurement(stage, elapsed)
        return Timing(stage, elapsed, metadata)
    }

    /**
     * Records a timing measurement directly (for stages where start/end
     * bracketing is not practical).
     */
    fun recordTiming(stage: String, durationNs: Long, metadata: Map<String, String> = emptyMap()): Timing {
        recordMeasurement(stage, durationNs)
        return Timing(stage, durationNs, metadata)
    }

    /**
     * Convenience: measures [block] and records the elapsed time under [stage].
     */
    fun <T> measure(stage: String, metadata: Map<String, String> = emptyMap(), block: () -> T): T {
        val start = System.nanoTime()
        return try {
            block()
        } finally {
            recordMeasurement(stage, System.nanoTime() - start)
        }
    }

    /** Returns aggregated stats for a single stage. */
    fun getStats(stage: String): StageStats? {
        val count = totalCounts[stage]?.get() ?: return null
        if (count == 0L) return null
        val total = totalDurations[stage]?.get() ?: 0L
        val min = minDurations[stage]?.get() ?: Long.MAX_VALUE
        val max = maxDurations[stage]?.get() ?: 0L
        val last = lastDurations[stage]?.get() ?: 0L
        return StageStats(
            stage = stage,
            count = count,
            totalMs = total / 1_000_000.0,
            minMs = min / 1_000_000.0,
            maxMs = max / 1_000_000.0,
            avgMs = (total / count) / 1_000_000.0,
            lastMs = last / 1_000_000.0
        )
    }

    /** Returns stats for all tracked stages. */
    fun getAllStats(): List<StageStats> {
        return stages.keys.mapNotNull { getStats(it) }.sortedBy { it.stage }
    }

    /** Returns the last [count] timing measurements for a stage. */
    fun getRecentTimings(stage: String, count: Int = 10): List<Timing> {
        val deque = recentTimings[stage] ?: return emptyList()
        synchronized(deque) {
            val take = deque.toList().takeLast(count)
            return take.map { Timing(stage, it) }
        }
    }

    /** Resets all counters for a stage. */
    fun resetStage(stage: String) {
        totalCounts.remove(stage)
        totalDurations.remove(stage)
        minDurations.remove(stage)
        maxDurations.remove(stage)
        lastDurations.remove(stage)
        recentTimings.remove(stage)
    }

    /** Resets all counters. */
    fun resetAll() {
        stages.clear()
        totalCounts.clear()
        totalDurations.clear()
        minDurations.clear()
        maxDurations.clear()
        lastDurations.clear()
        recentTimings.clear()
    }

    private fun recordMeasurement(stage: String, durationNs: Long) {
        totalCounts.getOrPut(stage) { AtomicLong(0L) }.incrementAndGet()
        totalDurations.getOrPut(stage) { AtomicLong(0L) }.addAndGet(durationNs)
        lastDurations.getOrPut(stage) { AtomicLong(0L) }.set(durationNs)

        // CAS loop for min
        while (true) {
            val current = minDurations.getOrPut(stage) { AtomicLong(Long.MAX_VALUE) }.get()
            if (durationNs >= current) break
            if (minDurations[stage]!!.compareAndSet(current, durationNs)) break
        }
        // CAS loop for max
        while (true) {
            val current = maxDurations.getOrPut(stage) { AtomicLong(0L) }.get()
            if (durationNs <= current) break
            if (maxDurations[stage]!!.compareAndSet(current, durationNs)) break
        }

        // Recent ring buffer
        val deque = recentTimings.getOrPut(stage) { ArrayDeque(MAX_RECENT + 1) }
        synchronized(deque) {
            deque.addLast(durationNs)
            while (deque.size > MAX_RECENT) deque.removeFirst()
        }
    }

    /** Well-known pipeline stage names. */
    object Stages {
        const val MODEL_INIT = "model_init"
        const val TOKENIZER_LOAD = "tokenizer_load"
        const val PROMPT_FORMAT = "prompt_format"
        const val PREFILL = "prefill"
        const val FIRST_TOKEN = "first_token"
        const val DECODE_TOKEN = "decode_token"
        const val STREAMING_OVERHEAD = "streaming_overhead"
        const val RESPONSE_PARSE = "response_parse"
        const val UI_UPDATE = "ui_update"
        const val BACKEND_SWITCH = "backend_switch"
        const val CONTAINER_READ = "container_read"
        const val FAMILY_RESOLVE = "family_resolve"
        const val SELF_TEST = "self_test"
        const val WARMUP = "warmup"
        const val CONVERSATION_CREATE = "conversation_create"
        const val CONTEXT_TRIM = "context_trim"
        const val MEMORY_STATS = "memory_stats"
    }
}
