package io.androllm.app.sync

import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.firebase.auth.FirebaseAuth
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import io.androllm.app.BuildConfig
import io.androllm.core.cloud.usage.CloudUsageMeter
import io.androllm.core.datastore.PreferencesDataStore
import io.androllm.core.network.identity.EventsBatchRequest
import io.androllm.core.network.identity.IdentityApi
import io.androllm.core.network.identity.IdentityApi.IdentityResult
import io.androllm.core.network.identity.SnapshotRequest
import io.androllm.core.network.identity.SyncApi
import io.androllm.core.network.identity.SyncDevice
import io.androllm.core.network.identity.canSyncUsage
import io.androllm.core.telemetry.TelemetryRepository
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonObject
import timber.log.Timber

/**
 * Phase 3 (revised) — uploads analytics from the existing in-app surfaces:
 * cloud usage dashboard records ([CloudUsageMeter]) and developer-page
 * generations ([TelemetryRepository]), plus one page-level snapshot each.
 *
 * Hard gates (checked in order, cheapest first — no network until all pass):
 * 1. Backend configured, Firebase signed in, cached `web_connected`.
 * 2. Fresh `GET /me` confirms [canSyncUsage]; anything else stops quietly.
 *
 * Uploads are idempotent (stable event/snapshot ids) and bounded (recent
 * records only); failures return [Result.retry] with scheduler backoff.
 * No UI is touched — collection only.
 */
@HiltWorker
class AnalyticsSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val identityApi: IdentityApi,
    private val syncApi: SyncApi,
    private val preferencesDataStore: PreferencesDataStore,
    private val cloudUsageMeter: CloudUsageMeter,
    private val telemetryRepository: TelemetryRepository,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (!syncApi.isConfigured) return Result.success()
        if (runCatching { FirebaseAuth.getInstance().currentUser == null }.getOrDefault(true)) {
            return Result.success()
        }
        if (!preferencesDataStore.webConnected.first()) {
            // Never synced before explicit Connect Web Dashboard approval.
            return Result.success()
        }
        return try {
            when (val me = identityApi.me()) {
                is IdentityResult.Success -> {
                    if (!me.value.canSyncUsage()) {
                        preferencesDataStore.setWebConnection(false, null)
                        return Result.success()
                    }
                }
                is IdentityResult.Unauthorized -> return Result.success()
                is IdentityResult.Failure -> return Result.retry()
            }
            syncOnce()
            Result.success()
        } catch (e: Exception) {
            Timber.w(e, "[AnalyticsSync] run failed")
            Result.retry()
        }
    }

    private suspend fun syncOnce() {
        cloudUsageMeter.init()
        val device = buildDevice()

        // ── Backend session: one per UTC day per device. Events and snapshots
        // attach to it, so the dashboard's session counts/durations are real.
        // The previous day's session is closed best-effort on rollover; the
        // current one stays open (shown as Active) until then.
        var sessionId = resolveSession(device)

        // ── Events: cloud records + local generations since the watermarks ──
        val lastCloudId = preferencesDataStore.lastCloudRecordId.first()
        val cloudRecords = cloudUsageMeter.snapshot().recentRecords
        val newCloud = if (lastCloudId == null) cloudRecords
        else cloudRecords.takeWhile { it.id != lastCloudId }

        val lastGenKey = preferencesDataStore.lastGenerationKey.first()
        val generations = telemetryRepository.generationHistory.value
        val newGenerations = if (lastGenKey == null) generations
        else generations.takeLastWhile { generationKey(it) != lastGenKey }

        val baseEvents = (newCloud.map { it.toSyncEvent() } + newGenerations.map { it.toSyncEvent() })
            .take(SyncApi.MAX_BATCH_SIZE)
        if (baseEvents.isNotEmpty()) {
            var outcome = postBatchEvents(device, baseEvents, sessionId)
            if (outcome == BatchOutcome.SessionStale && sessionId != null) {
                // The cached session is unknown to the backend (fresh/wiped
                // database). Drop it, open a new one, and retry once — already
                // accepted events dedupe by event_id, so this is safe.
                Timber.w("[AnalyticsSync] cached session unknown to backend, retrying with a fresh one")
                preferencesDataStore.setSyncSession(null, null)
                sessionId = resolveSession(device)
                outcome = postBatchEvents(device, baseEvents, sessionId)
            }
            when (outcome) {
                BatchOutcome.Uploaded -> {
                    newCloud.firstOrNull()?.let { preferencesDataStore.setLastCloudRecordId(it.id) }
                    newGenerations.lastOrNull()?.let { preferencesDataStore.setLastGenerationKey(generationKey(it)) }
                }
                BatchOutcome.Unauthorized, BatchOutcome.NotConnected -> return
                // Still stale after a fresh session (shouldn't happen) or a
                // plain failure: retry the whole run later with backoff.
                is BatchOutcome.Failed -> throw IllegalStateException("batch upload failed: ${outcome.message}")
                BatchOutcome.SessionStale ->
                    throw IllegalStateException("batch upload failed: session still unknown after refresh")
            }
        }

        // ── Snapshots: one hourly-bucketed rollup per surface ──
        val nowMs = System.currentTimeMillis()
        val lastBucket = preferencesDataStore.lastSnapshotBucket.first()
        val cloudBucket = snapshotBucket(io.androllm.core.network.identity.SourcePage.CLOUD_USAGE_DASHBOARD, nowMs)
        val devBucket = snapshotBucket(io.androllm.core.network.identity.SourcePage.DEVELOPER_PAGE, nowMs)
        if (lastBucket != "$cloudBucket|$devBucket") {
            val cloudSent = postSnapshot(
                device = device,
                sourcePage = io.androllm.core.network.identity.SourcePage.CLOUD_USAGE_DASHBOARD,
                snapshotType = "dashboard_rollup",
                snapshotId = "snap-$cloudBucket",
                sessionId = sessionId,
                payload = buildCloudSnapshotPayload(cloudUsageMeter.snapshot()),
            )
            val devSent = postSnapshot(
                device = device,
                sourcePage = io.androllm.core.network.identity.SourcePage.DEVELOPER_PAGE,
                snapshotType = "telemetry_summary",
                snapshotId = "snap-$devBucket",
                sessionId = sessionId,
                payload = buildDeveloperSnapshotPayload(
                    generations = telemetryRepository.generationHistory.value,
                    deviceMetrics = telemetryRepository.deviceMetrics.value,
                    currentModel = telemetryRepository.currentModelName.value,
                ),
            )
            if (cloudSent && devSent) {
                preferencesDataStore.setLastSnapshotBucket("$cloudBucket|$devBucket")
            }
        }
    }

    /**
     * Outcome of one batch post, normalized so callers handle auth, gating,
     * and stale sessions without re-reading HTTP semantics.
     */
    private sealed interface BatchOutcome {
        data object Uploaded : BatchOutcome
        data object Unauthorized : BatchOutcome
        data object NotConnected : BatchOutcome
        /** The attached session is unknown to the backend — refresh and retry once. */
        data object SessionStale : BatchOutcome
        data class Failed(val message: String) : BatchOutcome
    }

    private suspend fun postBatchEvents(
        device: SyncDevice,
        baseEvents: List<io.androllm.core.network.identity.SyncEvent>,
        sessionId: String?,
    ): BatchOutcome {
        val events = if (sessionId == null) baseEvents else baseEvents.map { it.copy(sessionId = sessionId) }
        return when (val batch = syncApi.postBatch(EventsBatchRequest(device, events))) {
            is IdentityResult.Success -> {
                Timber.d("[AnalyticsSync] batch accepted=${batch.value.accepted} dup=${batch.value.duplicates}")
                if (hasSessionRejection(batch.value.rejected)) BatchOutcome.SessionStale
                else BatchOutcome.Uploaded
            }
            is IdentityResult.Unauthorized -> BatchOutcome.Unauthorized
            is IdentityResult.Failure -> when (batch.message) {
                SyncApi.NOT_CONNECTED -> {
                    preferencesDataStore.setWebConnection(false, null)
                    BatchOutcome.NotConnected
                }
                else -> BatchOutcome.Failed(batch.message)
            }
        }
    }

    /**
     * Returns today's backend session id, creating it (and closing any stale
     * one from a previous day) as needed. Returns null when sessions are
     * unavailable — callers must still upload events/snapshots without one.
     */
    private suspend fun resolveSession(device: SyncDevice): String? {
        val today = utcDayKey(System.currentTimeMillis())
        val storedId = preferencesDataStore.syncSessionId.first()
        val storedDay = preferencesDataStore.syncSessionDay.first()
        if (storedId != null && storedDay == today) return storedId
        if (storedId != null) {
            // Best-effort close of yesterday's session; a 404 (or any failure)
            // just means there is nothing to close.
            runCatching { syncApi.endSession(storedId) }
        }
        return when (val started = syncApi.startSession(device)) {
            is IdentityResult.Success -> {
                preferencesDataStore.setSyncSession(started.value.id, today)
                started.value.id
            }
            is IdentityResult.Unauthorized -> null
            is IdentityResult.Failure -> {
                if (started.message == SyncApi.NOT_CONNECTED) {
                    preferencesDataStore.setWebConnection(false, null)
                } else {
                    Timber.w("[AnalyticsSync] session start failed: ${started.message}")
                }
                null
            }
        }
    }

    private suspend fun postSnapshot(
        device: SyncDevice,
        sourcePage: String,
        snapshotType: String,
        snapshotId: String,
        sessionId: String?,
        payload: JsonObject,
    ): Boolean {
        when (val res = syncApi.postSnapshot(SnapshotRequest(device, sourcePage, snapshotType, snapshotId, sessionId, payload))) {
            is IdentityResult.Success -> return true
            is IdentityResult.Unauthorized -> return false
            is IdentityResult.Failure -> {
                if (res.message == SyncApi.NOT_CONNECTED) {
                    preferencesDataStore.setWebConnection(false, null)
                    return false
                }
                throw IllegalStateException("snapshot upload failed: ${res.message}")
            }
        }
    }

    private fun buildDevice(): SyncDevice {
        val androidId = runCatching {
            Settings.Secure.getString(applicationContext.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull().takeIf { !it.isNullOrBlank() } ?: "unknown"
        return SyncDevice(
            deviceIdentifier = "android:$androidId",
            deviceName = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
            platform = "android",
            appVersion = BuildConfig.VERSION_NAME,
        )
    }
}

/**
 * Opportunistic schedule: every 6h on any network, exponential backoff.
 * Enqueue is idempotent (KEEP) and cheap — call at app start. The worker
 * itself no-ops unless the user is signed in AND web-connected.
 */
@Singleton
class AnalyticsSyncScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun schedule() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()
        val periodic = PeriodicWorkRequestBuilder<AnalyticsSyncWorker>(6, TimeUnit.HOURS)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            periodic,
        )
    }

    companion object {
        const val UNIQUE_WORK = "analytics_sync"
    }
}
