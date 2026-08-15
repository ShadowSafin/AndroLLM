package io.androllm.engine.diagnostics

import android.util.Log
import timber.log.Timber

/**
 * Tag-scoped logger for the engine package. Every log line carries the
 * component tag plus a stable engine tag so logcat filters can isolate all
 * engine activity.
 */
class RuntimeLogger(private val componentTag: String) {

    companion object {
        private const val ENGINE_TAG = "AndroLLM-Engine"
    }

    fun v(message: String) = Log.v(ENGINE_TAG, "[$componentTag] $message")
    fun d(message: String) = Log.d(ENGINE_TAG, "[$componentTag] $message")
    fun i(message: String) = Log.i(ENGINE_TAG, "[$componentTag] $message")
    fun w(message: String) = Log.w(ENGINE_TAG, "[$componentTag] $message")
    fun e(message: String, throwable: Throwable? = null) {
        if (throwable != null) Log.e(ENGINE_TAG, "[$componentTag] $message", throwable)
        else Log.e(ENGINE_TAG, "[$componentTag] $message")
    }

    fun timber(message: String, throwable: Throwable? = null) {
        if (throwable != null) Timber.e(throwable, "[$componentTag] $message")
        else Timber.e("[$componentTag] $message")
    }
}