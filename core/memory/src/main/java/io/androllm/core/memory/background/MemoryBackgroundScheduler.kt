package io.androllm.core.memory.background

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Schedules the background memory maintenance jobs via WorkManager.
 * All jobs are opportunistic: they never block chat and run only when the
 * device allows it. Scheduling is idempotent (unique work), so calling it at
 * app start and after enabling memory cannot create duplicates.
 */
@Singleton
class MemoryBackgroundScheduler @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val workManager: WorkManager by lazy { WorkManager.getInstance(context) }

    /** Ensures the periodic indexing loop exists and kicks off an immediate backfill. */
    fun schedule() {
        val constraints = Constraints.Builder()
            .setRequiresBatteryNotLow(true)
            .build()

        val periodic = PeriodicWorkRequestBuilder<MemoryIndexingWorker>(PERIODIC_HOURS, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()
        workManager.enqueueUniquePeriodicWork(
            UNIQUE_PERIODIC,
            ExistingPeriodicWorkPolicy.KEEP,
            periodic
        )

        // One immediate run so memories saved before the first period end are
        // indexed promptly (no-op when there is nothing pending).
        val once = OneTimeWorkRequestBuilder<MemoryIndexingWorker>()
            .setConstraints(constraints)
            .build()
        workManager.enqueueUniqueWork(UNIQUE_ONCE, ExistingWorkPolicy.KEEP, once)
    }

    companion object {
        const val UNIQUE_PERIODIC = "memory_background_indexing"
        const val UNIQUE_ONCE = "memory_background_indexing_once"
        private const val PERIODIC_HOURS = 6L
    }
}