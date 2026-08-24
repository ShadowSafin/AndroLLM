package io.androllm.engine.diagnostics

import android.util.Log
import timber.log.Timber

/**
 * Tag-scoped logger for the engine package. Every log line carries the
 * component tag plus a stable engine tag so logcat filters can isolate all
 * engine activity.
 *
 * Optimization: conditional verbose/debug logging and rate-limited warnings
 * to reduce logcat overhead during hot-path streaming.
 */
class RuntimeLogger(private val componentTag: String) {

    companion object {
        private const val ENGINE_TAG = "AndroLLM-Engine"

        /** Minimum interval between repeated rate-limited warnings (ms). */
        private const val RATE_LIMIT_WINDOW_MS = 5_000L

        /** Global rate-limit state per warning key. */
        private val rateLimitTimestamps = HashMap<String, Long>(16)
    }

    fun v(message: String) {
        if (Log.isLoggable(ENGINE_TAG, Log.VERBOSE)) {
            Log.v(ENGINE_TAG, "[$componentTag] $message")
        }
    }

    fun d(message: String) {
        if (Log.isLoggable(ENGINE_TAG, Log.DEBUG)) {
            Log.d(ENGINE_TAG, "[$componentTag] $message")
        }
    }

    fun i(message: String) = Log.i(ENGINE_TAG, "[$componentTag] $message")

    fun w(message: String) = Log.w(ENGINE_TAG, "[$componentTag] $message")

    /**
     * Rate-limited warning: fires at most once per [RATE_LIMIT_WINDOW_MS]
     * for the same [key]. Use for warnings that can fire repeatedly during
     * streaming (e.g. per-fragment overflow recovery).
     */
    fun wRateLimited(key: String, message: String) {
        val now = System.currentTimeMillis()
        val lastFired = rateLimitTimestamps[key] ?: 0L
        if (now - lastFired >= RATE_LIMIT_WINDOW_MS) {
            rateLimitTimestamps[key] = now
            Log.w(ENGINE_TAG, "[$componentTag] $message")
        }
    }

    fun e(message: String, throwable: Throwable? = null) {
        if (throwable != null) Log.e(ENGINE_TAG, "[$componentTag] $message", throwable)
        else Log.e(ENGINE_TAG, "[$componentTag] $message")
    }

    fun timber(message: String, throwable: Throwable? = null) {
        if (throwable != null) Timber.e(throwable, "[$componentTag] $message")
        else Timber.e("[$componentTag] $message")
    }
}