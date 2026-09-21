package io.androllm.app.sync

import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.androllm.core.cloud.usage.CloudUsageMeter
import io.androllm.core.telemetry.TelemetryRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext

/** Hilt access to WorkManager for the analytics sync trigger. */
@Module
@InstallIn(SingletonComponent::class)
object AnalyticsSyncModule {

    @Provides
    @Singleton
    fun provideWorkManager(@ApplicationContext context: Context): WorkManager =
        WorkManager.getInstance(context)
}

/**
 * Near-real-time sync trigger: every recorded cloud request and every
 * completed local generation schedules an upload ~[debounceMs] later, so
 * usage lands in Supabase within ~10 seconds of happening.
 *
 * Bursts coalesce — each new record restarts the timer, and WorkManager's
 * REPLACE policy collapses overlapping requests into one run. The worker
 * itself still gates on signed-in + web-connected and dedupes server-side,
 * so triggers are always safe (and cheap no-ops when offline/guest).
 */
@Singleton
class AnalyticsSyncTrigger @Inject constructor(
    private val workManager: WorkManager,
    private val cloudUsageMeter: CloudUsageMeter,
    private val telemetryRepository: TelemetryRepository,
) {
    /** Overridable in tests (keep production at 8s so end-to-end stays <10s). */
    var debounceMs: Long = DEBOUNCE_MS

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var pending: Job? = null

    /** Hook the existing dashboard data sources. Idempotent. */
    fun bind() {
        cloudUsageMeter.onRecorded = { requestSoon() }
        telemetryRepository.onGenerationRecorded = { requestSoon() }
    }

    /** Schedule an upload [debounceMs] from now, cancelling any pending one. */
    fun requestSoon() {
        pending?.cancel()
        pending = scope.launch {
            delay(debounceMs)
            runCatching { enqueue() }.onFailure {
                Timber.w(it, "[AnalyticsSync] trigger enqueue failed")
            }
        }
    }

    private fun enqueue() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val once = OneTimeWorkRequestBuilder<AnalyticsSyncWorker>()
            .setConstraints(constraints)
            .build()
        workManager.enqueueUniqueWork(UNIQUE_SOON, ExistingWorkPolicy.REPLACE, once)
    }

    companion object {
        const val UNIQUE_SOON = "analytics_sync_soon"
        const val DEBOUNCE_MS = 8_000L
    }
}
