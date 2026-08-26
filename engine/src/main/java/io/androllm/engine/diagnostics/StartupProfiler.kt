package io.androllm.engine.diagnostics

import android.util.Log
import timber.log.Timber

/**
 * Production performance diagnostics — detailed timing for every stage of the
 * model lifecycle. All measurements are wall-clock and logged at INFO for
 * developer builds, DEBUG otherwise. No PII, only durations and sizes.
 *
 * Stages profiled:
 * - App startup (Application.onCreate → MainActivity.setContent)
 * - Runtime initialization (HardwareBackendProbe, BackendSelector)
 * - Delegate creation (per-backend attempt with fallback reason)
 * - Model parsing (container header, metadata, SHA)
 * - Tokenizer loading (embedded + sidecar, with cache hit/miss)
 * - Backend selection (ordered candidates, bestAvailable)
 * - Model loading (total, per-stage, warmup)
 * - Time to first token (TTFT), tokens/sec, prompt preprocessing
 * - KV cache initialization / conversation reuse (hit/miss, reseed reason)
 * - Memory allocation (weights, KV, scratch, peak)
 * - Backend fallback reasons (stage, suggestion, retryable)
 */
object StartupProfiler {

    private var appStartMs: Long = 0L
    private val marks = mutableMapOf<String, Long>()

    fun markAppStart() {
        appStartMs = System.currentTimeMillis()
        marks["appStart"] = appStartMs
        Timber.i("[Perf] App startup marked at $appStartMs")
    }

    fun mark(name: String) {
        marks[name] = System.currentTimeMillis()
    }

    fun elapsedSinceAppStart(): Long = if (appStartMs == 0L) 0L else System.currentTimeMillis() - appStartMs

    fun logStage(stage: String, startMs: Long, extra: String = "") {
        val elapsed = System.currentTimeMillis() - startMs
        Timber.i("[Perf] $stage: ${elapsed}ms${if (extra.isNotEmpty()) " — $extra" else ""}")
    }

    fun logMemory(stage: String, bytes: Long) {
        val mb = bytes / (1024 * 1024)
        Timber.i("[Perf] Memory $stage: ${mb}MB (${bytes} bytes)")
    }

    fun logDelegateAttempt(backend: String, success: Boolean, ms: Long, reason: String = "") {
        val status = if (success) "SUCCESS" else "FAILED"
        Timber.i("[Perf] Delegate $backend: $status in ${ms}ms${if (reason.isNotEmpty()) " — $reason" else ""}")
    }

    fun logKvCache(hit: Boolean, reason: String) {
        Timber.i("[Perf] KV cache ${if (hit) "HIT" else "MISS"} — $reason")
    }

    fun logFallback(from: String, to: String, reason: String) {
        Timber.w("[Perf] Backend fallback $from → $to: $reason")
    }

    fun snapshot(): Map<String, Long> = marks.toMap()
}
