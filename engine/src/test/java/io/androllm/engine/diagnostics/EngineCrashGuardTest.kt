package io.androllm.engine.diagnostics

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class EngineCrashGuardTest {

    @Before
    fun setUp() {
        EngineCrashGuard.reset()
    }

    @Test
    fun `guard records crash event on failure`() {
        val exception = RuntimeException("test error")
        try {
            EngineCrashGuard.guard("test_stage") {
                throw exception
            }
            fail("Should have thrown")
        } catch (e: Exception) {
            // Expected
        }

        val events = EngineCrashGuard.getRecentEvents()
        assertEquals(1, events.size)
        assertEquals("test_stage", events[0].stage)
        assertEquals("test error", events[0].message)
    }

    @Test
    fun `guardOrNull returns null on failure`() {
        val result = EngineCrashGuard.guardOrNull("test") {
            throw RuntimeException("fail")
        }
        assertNull(result)
        assertEquals(1, EngineCrashGuard.getRecentEvents().size)
    }

    @Test
    fun `guardOrNull returns value on success`() {
        val result = EngineCrashGuard.guardOrNull("test") {
            42
        }
        assertEquals(42, result)
        assertEquals(0, EngineCrashGuard.getRecentEvents().size)
    }

    @Test
    fun `guard returns value on success`() {
        val result = EngineCrashGuard.guard("test") {
            "ok"
        }
        assertEquals("ok", result)
    }

    @Test
    fun `guard does not catch CancellationException`() {
        try {
            EngineCrashGuard.guard("test") {
                throw kotlinx.coroutines.CancellationException("cancel")
            }
            fail("Should have thrown")
        } catch (e: kotlinx.coroutines.CancellationException) {
            // CancellationException must propagate
            assertEquals(0, EngineCrashGuard.getRecentEvents().size)
        }
    }

    @Test
    fun `backend disabled after consecutive failures`() {
        assertFalse(EngineCrashGuard.isBackendDisabled("GPU"))

        EngineCrashGuard.recordCrash("load", "GPU", RuntimeException("fail1"))
        assertFalse(EngineCrashGuard.isBackendDisabled("GPU"))

        EngineCrashGuard.recordCrash("load", "GPU", RuntimeException("fail2"))
        assertFalse(EngineCrashGuard.isBackendDisabled("GPU"))

        EngineCrashGuard.recordCrash("load", "GPU", RuntimeException("fail3"))
        assertTrue(EngineCrashGuard.isBackendDisabled("GPU"))
    }

    @Test
    fun `backend re-enabled after success`() {
        EngineCrashGuard.recordCrash("load", "NPU", RuntimeException("fail1"))
        EngineCrashGuard.recordCrash("load", "NPU", RuntimeException("fail2"))
        assertFalse(EngineCrashGuard.isBackendDisabled("NPU"))

        EngineCrashGuard.recordSuccess("NPU")
        assertFalse(EngineCrashGuard.isBackendDisabled("NPU"))
    }

    @Test
    fun `crash summary reports correctly`() {
        val summary = EngineCrashGuard.crashSummary()
        assertEquals("No crashes recorded", summary)
    }

    @Test
    fun `crash summary with events`() {
        EngineCrashGuard.recordCrash("load", "GPU", RuntimeException("load failed"))
        EngineCrashGuard.recordCrash("generate", "CPU", RuntimeException("gen failed"))

        val summary = EngineCrashGuard.crashSummary()
        assertTrue(summary.contains("2 error(s)"))
    }

    @Test
    fun `reset clears all events and counters`() {
        EngineCrashGuard.recordCrash("test", "GPU", RuntimeException("fail"))
        assertEquals(1, EngineCrashGuard.getRecentEvents().size)

        EngineCrashGuard.reset()
        assertEquals(0, EngineCrashGuard.getRecentEvents().size)
        assertFalse(EngineCrashGuard.isBackendDisabled("GPU"))
    }

    @Test
    fun `recent events bounded to max`() {
        for (i in 1..30) {
            EngineCrashGuard.recordCrash("stage_$i", "", RuntimeException("error_$i"))
        }
        assertTrue(EngineCrashGuard.getRecentEvents().size <= 20)
    }

    @Test
    fun `crash event records native crash type`() {
        val event = EngineCrashGuard.recordCrash(
            "native", "GPU",
            UnsatisfiedLinkError("libfoo.so not found")
        )
        assertTrue(event.isNative)
    }

    @Test
    fun `crash event records non-native crash type`() {
        val event = EngineCrashGuard.recordCrash(
            "parse", "",
            IllegalArgumentException("bad input")
        )
        assertFalse(event.isNative)
    }
}
