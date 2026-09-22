package io.androllm.app.sync

import android.content.Context
import android.provider.Settings
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import io.androllm.core.cloud.usage.CloudRequestKind
import io.androllm.core.cloud.usage.CloudUsageMeter
import io.androllm.core.cloud.usage.CloudUsageRecord
import io.androllm.core.cloud.usage.CloudUsageSnapshot
import io.androllm.core.datastore.PreferencesDataStore
import io.androllm.core.network.identity.BackendSession
import io.androllm.core.network.identity.BatchRejection
import io.androllm.core.network.identity.EventsBatchRequest
import io.androllm.core.network.identity.EventsBatchResponse
import io.androllm.core.network.identity.IdentityApi
import io.androllm.core.network.identity.SnapshotResponse
import io.androllm.core.network.identity.SyncApi
import io.androllm.core.network.identity.UserProfile
import io.androllm.core.telemetry.TelemetryRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * A batch rejected for an unknown session (e.g. backend database was reset
 * while the app still caches yesterday's session id) must drop the cached id,
 * open a fresh session, and retry once — already-accepted events dedupe, so
 * the retry can safely resend the whole batch.
 */
class AnalyticsSyncRetryTest {

    private lateinit var prefs: PreferencesDataStore
    private lateinit var identityApi: IdentityApi
    private lateinit var syncApi: SyncApi
    private lateinit var meter: CloudUsageMeter
    private lateinit var telemetry: TelemetryRepository

    private var storedSessionId: String? = "sess-stale"
    private var storedSessionDay: String? = utcDayKey(System.currentTimeMillis())

    private fun cloudRecord() = CloudUsageRecord(
        id = "rec-1",
        timestampMs = 1_720_000_000_000,
        providerId = "openai",
        providerName = "OpenAI",
        modelId = "gpt-4o",
        kind = CloudRequestKind.CHAT,
        inputTokens = 10,
        outputTokens = 20,
    )

    private fun profile() = UserProfile(
        id = "uid-1",
        firebaseUid = "test-uid",
        createdAt = "2026-09-21T00:00:00Z",
        lastSeenAt = "2026-09-21T13:00:00Z",
        webConnected = true,
        webConnectedAt = "2026-09-21T00:00:00Z",
    )

    @Before
    fun setUp() {
        mockkStatic(FirebaseAuth::class)
        mockkStatic(Settings.Secure::class)
        val user = mockk<FirebaseUser>(relaxed = true)
        every { FirebaseAuth.getInstance().currentUser } returns user
        every { Settings.Secure.getString(any(), any()) } returns "test-device"

        prefs = mockk(relaxed = true)
        every { prefs.webConnected } answers { kotlinx.coroutines.flow.flow { emit(true) } }
        every { prefs.syncSessionId } answers { kotlinx.coroutines.flow.flow { emit(storedSessionId) } }
        every { prefs.syncSessionDay } answers { kotlinx.coroutines.flow.flow { emit(storedSessionDay) } }
        every { prefs.lastCloudRecordId } answers { kotlinx.coroutines.flow.flow { emit(null as String?) } }
        every { prefs.lastGenerationKey } answers { kotlinx.coroutines.flow.flow { emit(null as String?) } }
        every { prefs.lastSnapshotBucket } answers { kotlinx.coroutines.flow.flow { emit("done|done") } }
        coEvery { prefs.setSyncSession(any(), any()) } answers {
            storedSessionId = firstArg()
            storedSessionDay = secondArg()
        }

        identityApi = mockk()
        coEvery { identityApi.me() } returns IdentityApi.IdentityResult.Success(profile())

        syncApi = mockk()
        every { syncApi.isConfigured } returns true
        coEvery { syncApi.startSession(any()) } returns
            IdentityApi.IdentityResult.Success(BackendSession(id = "sess-fresh"))

        meter = mockk()
        coEvery { meter.init() } returns Unit
        every { meter.snapshot() } returns CloudUsageSnapshot(
            generatedAtMs = 1,
            recentRecords = listOf(cloudRecord()),
        )

        telemetry = mockk()
        every { telemetry.generationHistory } returns MutableStateFlow(emptyList())
        every { telemetry.deviceMetrics } returns MutableStateFlow(null)
        every { telemetry.currentModelName } returns MutableStateFlow("")

        coEvery { syncApi.postSnapshot(any()) } returns
            IdentityApi.IdentityResult.Success(SnapshotResponse(stored = true))
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `stale session triggers reset and single retry with fresh id`() = runTest {
        val sessionsUsed = mutableListOf<String?>()
        coEvery { syncApi.postBatch(any()) } answers {
            val request = firstArg<EventsBatchRequest>()
            val sessionId = request.events.firstOrNull()?.sessionId
            sessionsUsed.add(sessionId)
            if (sessionId == "sess-stale") {
                IdentityApi.IdentityResult.Success(
                    EventsBatchResponse(
                        accepted = 0,
                        duplicates = 0,
                        rejected = listOf(BatchRejection(0, "rec-1", "unknown or foreign session_id")),
                    )
                )
            } else {
                IdentityApi.IdentityResult.Success(EventsBatchResponse(accepted = 1, duplicates = 0))
            }
        }

        val worker = AnalyticsSyncWorker(
            appContext = mockk<Context>(relaxed = true),
            params = mockk<WorkerParameters>(relaxed = true),
            identityApi = identityApi,
            syncApi = syncApi,
            preferencesDataStore = prefs,
            cloudUsageMeter = meter,
            telemetryRepository = telemetry,
        )

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        coVerify(exactly = 2) { syncApi.postBatch(any()) }
        coVerify { prefs.setSyncSession(null, null) }
        // First attempt used the stale cached id, retry used the fresh one.
        assertEquals(listOf("sess-stale", "sess-fresh"), sessionsUsed)
    }

    @Test
    fun `clean run posts once with the resolved session`() = runTest {
        storedSessionId = null
        coEvery { syncApi.postBatch(any()) } returns
            IdentityApi.IdentityResult.Success(EventsBatchResponse(accepted = 1, duplicates = 0))

        val worker = AnalyticsSyncWorker(
            appContext = mockk<Context>(relaxed = true),
            params = mockk<WorkerParameters>(relaxed = true),
            identityApi = identityApi,
            syncApi = syncApi,
            preferencesDataStore = prefs,
            cloudUsageMeter = meter,
            telemetryRepository = telemetry,
        )

        worker.doWork()

        coVerify(exactly = 1) { syncApi.postBatch(any()) }
        coVerify(exactly = 0) { prefs.setSyncSession(null, null) }
    }
}
