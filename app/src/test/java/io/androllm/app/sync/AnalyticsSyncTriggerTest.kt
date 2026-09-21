package io.androllm.app.sync

import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import io.androllm.core.cloud.usage.CloudUsageMeter
import io.androllm.core.telemetry.TelemetryRepository
import io.mockk.mockk
import io.mockk.verify
import org.junit.Before
import org.junit.Test

/**
 * Sub-10s trigger contract: one record → one debounced upload; bursts
 * coalesce into a single enqueue; nothing fires before the debounce elapses.
 * Uses short real delays (no Robolectric needed — WorkManager is mocked).
 */
class AnalyticsSyncTriggerTest {

    private val workManager = mockk<WorkManager>(relaxed = true)
    private lateinit var trigger: AnalyticsSyncTrigger

    @Before
    fun setUp() {
        trigger = AnalyticsSyncTrigger(
            workManager = workManager,
            cloudUsageMeter = mockk(relaxed = true),
            telemetryRepository = mockk(relaxed = true),
        )
        trigger.debounceMs = 100
    }

    @Test
    fun `single record enqueues upload after debounce`() {
        trigger.requestSoon()
        Thread.sleep(400)

        verify(exactly = 1) {
            workManager.enqueueUniqueWork(
                AnalyticsSyncTrigger.UNIQUE_SOON,
                ExistingWorkPolicy.REPLACE,
                any<OneTimeWorkRequest>(),
            )
        }
    }

    @Test
    fun `burst coalesces into a single enqueue`() {
        repeat(5) {
            trigger.requestSoon()
            Thread.sleep(40)
        }
        Thread.sleep(400)

        verify(exactly = 1) {
            workManager.enqueueUniqueWork(
                AnalyticsSyncTrigger.UNIQUE_SOON,
                ExistingWorkPolicy.REPLACE,
                any<OneTimeWorkRequest>(),
            )
        }
    }

    @Test
    fun `nothing enqueued before debounce elapses`() {
        trigger.requestSoon()
        Thread.sleep(30)

        verify(exactly = 0) {
            workManager.enqueueUniqueWork(any<String>(), any<ExistingWorkPolicy>(), any<OneTimeWorkRequest>())
        }

        Thread.sleep(400)
        verify(exactly = 1) {
            workManager.enqueueUniqueWork(any<String>(), any<ExistingWorkPolicy>(), any<OneTimeWorkRequest>())
        }
    }
}
