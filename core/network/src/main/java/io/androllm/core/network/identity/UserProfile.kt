package io.androllm.core.network.identity

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Phase 1 backend user profile. Mirrors the private backend `users` row.
 *
 * Identity key is [firebaseUid] (verified server-side). The app must never
 * invent or forward a user id — the backend derives it from the ID token.
 */
@Serializable
data class UserProfile(
    val id: String,
    @SerialName("firebase_uid") val firebaseUid: String,
    val email: String? = null,
    @SerialName("display_name") val displayName: String? = null,
    @SerialName("photo_url") val photoUrl: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("last_seen_at") val lastSeenAt: String,
    @SerialName("sync_enabled") val syncEnabled: Boolean = false,
    val settings: JsonObject = JsonObject(emptyMap()),
    // Phase 2 consent flags (always false in Phase 1; read-only here).
    @SerialName("web_connected") val webConnected: Boolean = false,
    @SerialName("web_connected_at") val webConnectedAt: String? = null,
)

@Serializable
data class AuthVerifyRequest(
    @SerialName("display_name") val displayName: String? = null,
    @SerialName("photo_url") val photoUrl: String? = null,
    val device: DeviceInfo? = null,
)

@Serializable
data class DeviceInfo(
    val platform: String = "android",
    @SerialName("app_version") val appVersion: String? = null,
)

@Serializable
data class AuthSessionResponse(
    val authenticated: Boolean,
    val user: UserProfile,
)

/** Extra settings payload stays opaque — auth layer never interprets it. */
typealias SettingsJson = Map<String, JsonElement>

/**
 * Phase 2 sync gate. Phase 3 event ingestion must check this before uploading
 * ANY usage data: only an explicitly connected account may sync. The flag is
 * backend-driven (`web_connected` set via POST /auth/connect); the local
 * DataStore copy is a display cache only and must never bypass this check.
 */
fun UserProfile.canSyncUsage(): Boolean = webConnected
