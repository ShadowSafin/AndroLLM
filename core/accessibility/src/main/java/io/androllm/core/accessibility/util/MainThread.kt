package io.androllm.core.accessibility.util

import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.suspendCancellableCoroutine
import timber.log.Timber
import kotlin.coroutines.resume

/**
 * AccessibilityNodeInfo instances are only valid on the thread that created
 * them, and the framework requires actions on the main thread. Every node
 * read/write in the engine funnels through [node]; gesture dispatch and
 * callbacks are handled by the components directly.
 */
object MainThread {

    private val handler = Handler(Looper.getMainLooper())

    /**
     * Runs [block] on the main thread and suspends until it completes.
     * Exceptions become [default] so a flaky node never crashes a tool call.
     */
    suspend fun <T> node(block: () -> T, default: T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return try {
                block()
            } catch (t: Throwable) {
                Timber.w(t, "MainThread: node action failed")
                default
            }
        }
        return suspendCancellableCoroutine { cont ->
            handler.post {
                try {
                    cont.resume(block())
                } catch (t: Throwable) {
                    Timber.w(t, "MainThread: node action failed")
                    cont.resume(default)
                }
            }
        }
    }

    /**
     * Runs [block] on the main thread, returning null when it threw (the
     * common "node vanished" case) — used for reads.
     */
    suspend fun <T> node(block: () -> T): T? = node(block, null)

    /** Boolean variant — the common case for performAction results. */
    suspend fun action(block: () -> Boolean): Boolean = node(block, false)

    /** Fire-and-forget main-thread post (for async callback APIs). */
    fun post(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else handler.post(block)
    }
}
