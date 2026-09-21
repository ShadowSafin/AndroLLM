package io.androllm.core.network.identity

import io.androllm.core.network.identity.IdentityApi.IdentityResult
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import timber.log.Timber

/** In-app surfaces that may produce synced analytics. */
object SourcePage {
    const val CLOUD_USAGE_DASHBOARD = "cloud_usage_dashboard"
    const val DEVELOPER_PAGE = "developer_page"
}

@Serializable
data class SyncDevice(
    @SerialName("device_identifier") val deviceIdentifier: String,
    @SerialName("device_name") val deviceName: String? = null,
    val platform: String = "android",
    @SerialName("app_version") val appVersion: String? = null,
    val metadata: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class SyncEvent(
    @SerialName("event_id") val eventId: String,
    @SerialName("event_type") val eventType: String,
    @SerialName("session_id") val sessionId: String? = null,
    @SerialName("source_page") val sourcePage: String? = null,
    @SerialName("model_name") val modelName: String? = null,
    @SerialName("provider_name") val providerName: String? = null,
    @SerialName("engine_type") val engineType: String? = null,
    @SerialName("tokens_input") val tokensInput: Int = 0,
    @SerialName("tokens_output") val tokensOutput: Int = 0,
    @SerialName("total_tokens") val totalTokens: Int = 0,
    @SerialName("latency_ms") val latencyMs: Long? = null,
    @SerialName("time_to_first_token_ms") val timeToFirstTokenMs: Long? = null,
    val success: Boolean = true,
    @SerialName("error_code") val errorCode: String? = null,
    @SerialName("error_message") val errorMessage: String? = null,
    val metadata: JsonObject = JsonObject(emptyMap()),
    @SerialName("created_at") val createdAt: String? = null,
)

@Serializable
data class EventsBatchRequest(
    val device: SyncDevice,
    val events: List<SyncEvent>,
)

@Serializable
data class BatchRejection(
    val index: Int,
    @SerialName("event_id") val eventId: String? = null,
    val reason: String? = null,
)

@Serializable
data class EventsBatchResponse(
    val accepted: Int = 0,
    val duplicates: Int = 0,
    val rejected: List<BatchRejection> = emptyList(),
    @SerialName("device_id") val deviceId: String? = null,
)

@Serializable
data class SnapshotRequest(
    val device: SyncDevice,
    @SerialName("source_page") val sourcePage: String,
    @SerialName("snapshot_type") val snapshotType: String,
    @SerialName("snapshot_id") val snapshotId: String,
    @SerialName("session_id") val sessionId: String? = null,
    val payload: JsonObject,
)

@Serializable
data class SnapshotResponse(
    val stored: Boolean = false,
    val duplicate: Boolean = false,
    @SerialName("device_id") val deviceId: String? = null,
)

@Serializable
data class SessionStartRequest(
    val device: SyncDevice,
    @SerialName("engine_type") val engineType: String? = null,
    @SerialName("app_version") val appVersion: String? = null,
    val platform: String? = null,
    val metadata: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class SessionEndRequest(
    @SerialName("session_id") val sessionId: String,
)

@Serializable
data class BackendSession(
    val id: String,
    @SerialName("started_at") val startedAt: String? = null,
    @SerialName("ended_at") val endedAt: String? = null,
    @SerialName("engine_type") val engineType: String? = null,
)

/**
 * Phase 3 (revised) — usage + snapshot ingestion client.
 *
 * Callers MUST gate on [UserProfile.canSyncUsage] (verified via [IdentityApi.me])
 * before invoking anything here: the backend rejects unconnected users with
 * 403 `web_not_connected`, surfaced as [IdentityResult.Failure].
 * Retries are safe — [SyncEvent.eventId] and [SnapshotRequest.snapshotId] are
 * stable dedup keys, so re-sending never double-stores.
 */
class SyncApi(
    private val baseUrl: String,
    private val httpClient: HttpClient,
    private val idTokenProvider: suspend (forceRefresh: Boolean) -> String?,
    /** False when no backend URL is configured — every call short-circuits. */
    val isConfigured: Boolean = true,
) {

    companion object {
        const val NOT_CONNECTED = "web_not_connected"
        const val MAX_BATCH_SIZE = 500
    }

    private fun trimmedBase(): String = baseUrl.trim().trimEnd('/')

    private suspend fun tokenOrUnauthorized(): String? {
        val token = runCatching { idTokenProvider(false) }.getOrNull()
        return token?.takeIf { it.isNotBlank() }
    }

    /** Upload up to [MAX_BATCH_SIZE] events; partial success per item. */
    suspend fun postBatch(request: EventsBatchRequest): IdentityResult<EventsBatchResponse> {
        if (!isConfigured) return IdentityResult.Failure("backend not configured")
        val token = tokenOrUnauthorized() ?: return IdentityResult.Unauthorized("no id token")
        return try {
            val response = httpClient.post {
                url("${trimmedBase()}/events/batch")
                bearerAuth(token)
                contentType(ContentType.Application.Json)
                setBody(request)
            }
            when (response.status.value) {
                in 200..299 -> IdentityResult.Success(response.body())
                401 -> IdentityResult.Unauthorized()
                403 -> IdentityResult.Failure(NOT_CONNECTED)
                400 -> IdentityResult.Failure("invalid payload")
                else -> IdentityResult.Failure("batch failed: HTTP ${response.status.value}")
            }
        } catch (e: Exception) {
            Timber.w(e, "[Sync] batch failed")
            IdentityResult.Failure("batch failed: ${e.message}", e)
        }
    }

    /** Upload one page-level snapshot (cloud dashboard / developer page rollup). */
    suspend fun postSnapshot(request: SnapshotRequest): IdentityResult<SnapshotResponse> {
        if (!isConfigured) return IdentityResult.Failure("backend not configured")
        val token = tokenOrUnauthorized() ?: return IdentityResult.Unauthorized("no id token")
        return try {
            val response = httpClient.post {
                url("${trimmedBase()}/analytics/snapshot")
                bearerAuth(token)
                contentType(ContentType.Application.Json)
                setBody(request)
            }
            when (response.status.value) {
                in 200..299 -> IdentityResult.Success(response.body())
                401 -> IdentityResult.Unauthorized()
                403 -> IdentityResult.Failure(NOT_CONNECTED)
                400 -> IdentityResult.Failure("invalid payload")
                else -> IdentityResult.Failure("snapshot failed: HTTP ${response.status.value}")
            }
        } catch (e: Exception) {
            Timber.w(e, "[Sync] snapshot failed")
            IdentityResult.Failure("snapshot failed: ${e.message}", e)
        }
    }

    /** Open a backend usage session (one per day per device). Idempotent by day on the caller side. */
    suspend fun startSession(device: SyncDevice): IdentityResult<BackendSession> {
        if (!isConfigured) return IdentityResult.Failure("backend not configured")
        val token = tokenOrUnauthorized() ?: return IdentityResult.Unauthorized("no id token")
        return try {
            val response = httpClient.post {
                url("${trimmedBase()}/sessions/start")
                bearerAuth(token)
                contentType(ContentType.Application.Json)
                setBody(SessionStartRequest(device = device))
            }
            when (response.status.value) {
                in 200..299 -> IdentityResult.Success(response.body())
                401 -> IdentityResult.Unauthorized()
                403 -> IdentityResult.Failure(NOT_CONNECTED)
                400 -> IdentityResult.Failure("invalid payload")
                else -> IdentityResult.Failure("session start failed: HTTP ${response.status.value}")
            }
        } catch (e: Exception) {
            Timber.w(e, "[Sync] session start failed")
            IdentityResult.Failure("session start failed: ${e.message}", e)
        }
    }

    /** Close a backend usage session. Idempotent server-side; 404 means nothing to close. */
    suspend fun endSession(sessionId: String): IdentityResult<BackendSession> {
        if (!isConfigured) return IdentityResult.Failure("backend not configured")
        val token = tokenOrUnauthorized() ?: return IdentityResult.Unauthorized("no id token")
        return try {
            val response = httpClient.post {
                url("${trimmedBase()}/sessions/end")
                bearerAuth(token)
                contentType(ContentType.Application.Json)
                setBody(SessionEndRequest(sessionId))
            }
            when (response.status.value) {
                in 200..299 -> IdentityResult.Success(response.body())
                401 -> IdentityResult.Unauthorized()
                403 -> IdentityResult.Failure(NOT_CONNECTED)
                404 -> IdentityResult.Failure("session not found")
                400 -> IdentityResult.Failure("invalid payload")
                else -> IdentityResult.Failure("session end failed: HTTP ${response.status.value}")
            }
        } catch (e: Exception) {
            Timber.w(e, "[Sync] session end failed")
            IdentityResult.Failure("session end failed: ${e.message}", e)
        }
    }
}
