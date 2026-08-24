package io.androllm.engine.diagnostics

import android.util.Log
import io.androllm.engine.models.EngineException
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicInteger

/**
 * Centralized crash guard for the LiteRT inference engine.
 *
 * Responsibilities:
 *  1. Wraps every native/Kotlin boundary call with structured exception
 *     handling that ensures the engine transitions cleanly to FAILED
 *     instead of freezing or crashing.
 *  2. Maintains a bounded ring buffer of recent crash/error events for
 *     the diagnostics panel.
 *  3. Detects crash patterns (e.g. repeated JNI failures, backend loops)
 *     and escalates (e.g. disable a backend after N consecutive failures).
 *  4. Ensures generationActive is always released on any failure path.
 */
object EngineCrashGuard {

    private const val TAG = "EngineCrashGuard"

    /** Maximum crash events retained in the ring buffer. */
    private const val MAX_RECENT_EVENTS = 20

    /** After this many consecutive backend failures, disable the backend. */
    private const val MAX_CONSECUTIVE_BACKEND_FAILURES = 3

    /** Recent crash/error events (newest first). Thread-safe. */
    private val recentEvents = ConcurrentLinkedDeque<CrashEvent>()

    /** Per-backend failure counter (resets on successful load). */
    private val backendFailures = HashMap<String, AtomicInteger>()

    /**
     * A single crash or error event.
     */
    data class CrashEvent(
        val timestampMs: Long = System.currentTimeMillis(),
        val stage: String,
        val errorType: String,
        val message: String,
        val backend: String = "",
        val isNative: Boolean = false,
        val stackTrace: String = ""
    )

    /**
     * Wraps a [block] with structured crash handling. On failure:
     *  - Records the crash event in the ring buffer
     *  - Logs the error with full context
     *  - Returns a structured [EngineException] that the engine can
     *    transition to FAILED with
     *
     * Does NOT catch [kotlinx.coroutines.CancellationException] — that
     * must propagate for coroutine cancellation to work.
     */
    fun <T> guard(
        stage: String,
        backend: String = "",
        block: () -> T
    ): T {
        return try {
            block()
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Cancellation must propagate — never swallow it.
            throw e
        } catch (e: Throwable) {
            val event = recordCrash(stage, backend, e)
            Log.e(TAG, "[$stage] Crash guarded: ${event.message}", e)
            throw EngineException(
                "Engine error in $stage: ${e.message ?: e.javaClass.simpleName}",
                e
            )
        }
    }

    /**
     * Same as [guard] but returns null instead of throwing on failure.
     * Use for non-critical operations (memory stats, diagnostics) where
     * a failure should degrade gracefully.
     */
    fun <T> guardOrNull(
        stage: String,
        backend: String = "",
        block: () -> T
    ): T? {
        return try {
            block()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            recordCrash(stage, backend, e)
            Log.w(TAG, "[$stage] Non-critical failure: ${e.message}")
            null
        }
    }

    /**
     * Records a crash event and returns it. Also updates per-backend
     * failure counters for backend-disable logic.
     */
    fun recordCrash(
        stage: String,
        backend: String,
        error: Throwable
    ): CrashEvent {
        val event = CrashEvent(
            stage = stage,
            errorType = error.javaClass.simpleName,
            message = error.message ?: error.javaClass.simpleName,
            backend = backend,
            isNative = error.isNativeCrash(),
            stackTrace = error.stackTraceToString().take(2000)
        )

        recentEvents.addFirst(event)
        while (recentEvents.size > MAX_RECENT_EVENTS) {
            recentEvents.removeLast()
        }

        if (backend.isNotBlank()) {
            val counter = backendFailures.getOrPut(backend) { AtomicInteger(0) }
            counter.incrementAndGet()
        }

        return event
    }

    /**
     * Records a successful backend use (resets the failure counter).
     */
    fun recordSuccess(backend: String) {
        backendFailures[backend]?.set(0)
    }

    /**
     * True when the given backend has failed too many consecutive times
     * and should be disabled (skipped in the fallback chain).
     */
    fun isBackendDisabled(backend: String): Boolean {
        val count = backendFailures[backend]?.get() ?: 0
        return count >= MAX_CONSECUTIVE_BACKEND_FAILURES
    }

    /**
     * Returns the recent crash events (newest first, bounded).
     */
    fun getRecentEvents(): List<CrashEvent> = recentEvents.toList()

    /**
     * Returns a human-readable summary of the crash history.
     */
    fun crashSummary(): String {
        val events = recentEvents.toList()
        if (events.isEmpty()) return "No crashes recorded"
        val byStage = events.groupBy { it.stage }
        return buildString {
            appendLine("${events.size} error(s) recorded:")
            for ((stage, stageEvents) in byStage) {
                appendLine("  $stage: ${stageEvents.size} failure(s) — last: ${stageEvents.first().message.take(80)}")
            }
            val disabled = backendFailures.filter { it.value.get() >= MAX_CONSECUTIVE_BACKEND_FAILURES }
            if (disabled.isNotEmpty()) {
                appendLine("Disabled backends: ${disabled.keys.joinToString()}")
            }
        }
    }

    /**
     * Resets all counters and events. Called on engine release.
     */
    fun reset() {
        recentEvents.clear()
        backendFailures.clear()
    }

    /**
     * True when the throwable represents a native/JNI crash
     * (signal-based or JNI error).
     */
    private fun Throwable.isNativeCrash(): Boolean {
        return this is UnsatisfiedLinkError ||
            this is StackOverflowError ||
            this.javaClass.name.contains("JNI") ||
            this.javaClass.name.contains("native") ||
            message?.contains("JNI") == true ||
            message?.contains("native crash") == true ||
            message?.contains("signal") == true
    }
}
