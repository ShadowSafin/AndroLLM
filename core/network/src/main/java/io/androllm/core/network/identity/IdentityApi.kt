package io.androllm.core.network.identity

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import timber.log.Timber

/**
 * Phase 1 identity API — thin Ktor wrapper over the private backend.
 *
 * - Auth is always `Authorization: Bearer <Firebase ID token>`.
 * - [idTokenProvider] is injected so this module stays Firebase-free and testable.
 * - Identity comes from the verified token server-side; request bodies carry
 *   display hints only (never a user id).
 *
 * Endpoints: POST /auth/verify, GET /auth/session, GET /me.
 * Phase 2 adds: POST /auth/connect, DELETE /auth/connect (explicit
 * account-linking consent; the private backend flips `web_connected`).
 * Still no analytics/sync methods here — Phase 3 owns event ingestion, and it
 * must only run for profiles where [UserProfile.canSyncUsage] is true.
 */
class IdentityApi(
    private val baseUrl: String,
    private val httpClient: HttpClient,
    private val idTokenProvider: suspend (forceRefresh: Boolean) -> String?,
    /** False when no backend URL is configured — every call short-circuits. */
    val isConfigured: Boolean = true,
) {

    sealed interface IdentityResult<out T> {
        data class Success<T>(val value: T) : IdentityResult<T>
        data class Unauthorized(val message: String = "unauthorized") : IdentityResult<Nothing>
        data class Failure(val message: String, val cause: Throwable? = null) : IdentityResult<Nothing>
    }

    private fun trimmedBase(): String = baseUrl.trim().trimEnd('/')

    private suspend fun tokenOrUnauthorized(): String? {
        val token = runCatching { idTokenProvider(false) }.getOrNull()
        if (token.isNullOrBlank()) {
            Timber.w("[Identity] no Firebase ID token (signed out?)")
        }
        return token?.takeIf { it.isNotBlank() }
    }

    /** Verify session with backend; upserts the profile server-side. */
    suspend fun verify(
        displayName: String? = null,
        photoUrl: String? = null,
        appVersion: String? = null,
    ): IdentityResult<UserProfile> {
        val token = tokenOrUnauthorized() ?: return IdentityResult.Unauthorized("no id token")
        return try {
            val response = httpClient.post {
                url("${trimmedBase()}/auth/verify")
                bearerAuth(token)
                contentType(ContentType.Application.Json)
                setBody(AuthVerifyRequest(displayName, photoUrl, DeviceInfo(appVersion = appVersion)))
            }
            when {
                response.status.isSuccess() -> IdentityResult.Success(response.body())
                response.status.value == 401 -> IdentityResult.Unauthorized()
                else -> IdentityResult.Failure("verify failed: HTTP ${response.status.value}")
            }
        } catch (e: Exception) {
            Timber.w(e, "[Identity] verify failed")
            IdentityResult.Failure("verify failed: ${e.message}", e)
        }
    }

    /** Lightweight session check used on app startup. */
    suspend fun session(): IdentityResult<AuthSessionResponse> {
        val token = tokenOrUnauthorized() ?: return IdentityResult.Unauthorized("no id token")
        return try {
            val response = httpClient.get {
                url("${trimmedBase()}/auth/session")
                bearerAuth(token)
            }
            when {
                response.status.isSuccess() -> IdentityResult.Success(response.body())
                response.status.value == 401 -> IdentityResult.Unauthorized()
                else -> IdentityResult.Failure("session failed: HTTP ${response.status.value}")
            }
        } catch (e: Exception) {
            Timber.w(e, "[Identity] session failed")
            IdentityResult.Failure("session failed: ${e.message}", e)
        }
    }

    /** Fetch the current backend profile for the verified UID. */
    suspend fun me(): IdentityResult<UserProfile> {
        val token = tokenOrUnauthorized() ?: return IdentityResult.Unauthorized("no id token")
        return try {
            val response = httpClient.get {
                url("${trimmedBase()}/me")
                bearerAuth(token)
            }
            when {
                response.status.isSuccess() -> IdentityResult.Success(response.body())
                response.status.value == 401 -> IdentityResult.Unauthorized()
                else -> IdentityResult.Failure("me failed: HTTP ${response.status.value}")
            }
        } catch (e: Exception) {
            Timber.w(e, "[Identity] me failed")
            IdentityResult.Failure("me failed: ${e.message}", e)
        }
    }

    /**
     * Phase 2 — explicit account linking. Call ONLY after the user taps
     * "Connect Web Dashboard" and confirms. The private backend verifies the
     * ID token, sets `web_connected = true` / `web_connected_at = now()` for
     * the verified UID (idempotent), and returns the updated profile.
     * No usage data is uploaded by this call.
     */
    suspend fun connect(): IdentityResult<UserProfile> {
        if (!isConfigured) return IdentityResult.Failure("backend not configured")
        val token = tokenOrUnauthorized() ?: return IdentityResult.Unauthorized("no id token")
        return try {
            val response = httpClient.post {
                url("${trimmedBase()}/auth/connect")
                bearerAuth(token)
                contentType(ContentType.Application.Json)
                setBody("{}")
            }
            when {
                response.status.isSuccess() -> IdentityResult.Success(response.body())
                response.status.value == 401 -> IdentityResult.Unauthorized()
                else -> IdentityResult.Failure("connect failed: HTTP ${response.status.value}")
            }
        } catch (e: Exception) {
            Timber.w(e, "[Identity] connect failed")
            IdentityResult.Failure("connect failed: ${e.message}", e)
        }
    }

    /**
     * Phase 2 — revoke account linking. Idempotent; clears `web_connected`
     * for the verified UID and returns the updated profile.
     */
    suspend fun disconnect(): IdentityResult<UserProfile> {
        if (!isConfigured) return IdentityResult.Failure("backend not configured")
        val token = tokenOrUnauthorized() ?: return IdentityResult.Unauthorized("no id token")
        return try {
            val response = httpClient.delete {
                url("${trimmedBase()}/auth/connect")
                bearerAuth(token)
            }
            when {
                response.status.isSuccess() -> IdentityResult.Success(response.body())
                response.status.value == 401 -> IdentityResult.Unauthorized()
                else -> IdentityResult.Failure("disconnect failed: HTTP ${response.status.value}")
            }
        } catch (e: Exception) {
            Timber.w(e, "[Identity] disconnect failed")
            IdentityResult.Failure("disconnect failed: ${e.message}", e)
        }
    }
}
