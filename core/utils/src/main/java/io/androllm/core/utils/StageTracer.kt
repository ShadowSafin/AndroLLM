package io.androllm.core.utils

import android.util.Log

/**
 * Lightweight wall-clock stage timer for the post-generation pipeline.
 *
 * Usage:
 * ```
 * val tracer = StageTracer("generation finished")
 * ...
 * tracer.mark("append message")   // elapsed since creation / last mark
 * ...
 * tracer.finish()                 // logs every stage + the total
 * ```
 *
 * Log output (tag `AndroLLM.Perf`) matches the format requested for the
 * post-generation audit:
 * ```
 * [PostGen] generation finished
 * [PostGen]   append message         12 ms
 * [PostGen]   room upsert             3 ms
 * [PostGen] TOTAL                    15 ms
 * ```
 *
 * A total above [warnThresholdMs] (default 100 ms) is flagged — the target is
 * that the UI stays responsive immediately after a generation finishes.
 *
 * NOTE: stages measure wall-clock time on the CURRENT thread. Markers must be
 * called from the thread(s) doing the measured work to be meaningful (a mark
 * from a background coroutine measures that coroutine's span).
 */
class StageTracer(
    private val label: String,
    private val warnThresholdMs: Long = 100L
) {

    private val startedAt: Long = System.nanoTime()
    private val stages = mutableListOf<Pair<String, Long>>()
    private var lastMark: Long = startedAt

    /** Records the elapsed time since the previous mark (or creation). */
    fun mark(stage: String) {
        val now = System.nanoTime()
        stages += stage to (now - lastMark) / 1_000_000L
        lastMark = now
    }

    /**
     * Logs all stages plus the total and returns the total duration in ms.
     * Idempotent — subsequent calls no-op.
     */
    fun finish(): Long {
        if (finished) return totalMs
        finished = true
        totalMs = (System.nanoTime() - startedAt) / 1_000_000L

        val sb = StringBuilder(128)
        sb.append("[PostGen] ").append(label).append('\n')
        for ((stage, ms) in stages) {
            sb.append("[PostGen]   ")
                .append(stage.padEnd(20))
                .append(String.format("%6d ms", ms))
                .append('\n')
        }
        sb.append("[PostGen] TOTAL ")
            .append(totalMs)
            .append(" ms")
        if (totalMs > warnThresholdMs) {
            sb.append("  ⚠ exceeds ").append(warnThresholdMs).append(" ms budget")
        }
        Log.i(TAG, sb.toString())
        return totalMs
    }

    private var finished: Boolean = false
    private var totalMs: Long = 0L

    private companion object {
        const val TAG = "AndroLLM.Perf"
    }
}
