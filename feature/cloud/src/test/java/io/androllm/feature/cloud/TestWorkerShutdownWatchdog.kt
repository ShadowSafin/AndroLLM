package io.androllm.feature.cloud

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Workaround for a Gradle 9.5 + Windows + JDK 21 shutdown deadlock.
 *
 * After every test passes, the test worker JVM can hang forever inside
 * Gradle's `MessageHub.stop()`: its hub receive thread stays parked in a
 * socket select (`WEPoll.wait`) on the daemon↔worker socket while the
 * daemon side sits in `DefaultWorkerProcess.waitForStop` waiting for this
 * process to exit first. Neither side makes progress and the test task
 * never finishes (observed deterministically on this machine).
 *
 * The watchdog is armed once per worker JVM when the first test class
 * loads. If the JVM is still alive after [TIMEOUT_MS] — the tests
 * finished long ago but the shutdown deadlocked — it force-exits with
 * status 0. That is safe because:
 *
 * - every test event is streamed to the daemon as it happens, and the
 *   worker's run-completion message is sent before the hub shutdown
 *   begins, so all results are already delivered;
 * - on a clean shutdown (other platforms, fixed Gradle) the JVM exits
 *   long before the timer fires and the daemon thread simply dies.
 *
 * Delete this class (and the `arm()` call in the test's companion init)
 * once the Gradle worker shutdown deadlock is fixed.
 */
object TestWorkerShutdownWatchdog {

    /** Generous upper bound for the whole test run plus worker teardown. */
    private const val TIMEOUT_MS = 90_000L

    private val armed = AtomicBoolean(false)

    fun arm() {
        if (!armed.compareAndSet(false, true)) return
        Thread {
            try {
                Thread.sleep(TIMEOUT_MS)
            } catch (_: InterruptedException) {
                return@Thread
            }
            Runtime.getRuntime().halt(0)
        }.apply {
            isDaemon = true
            name = "test-worker-shutdown-watchdog"
            start()
        }
    }
}
