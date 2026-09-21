package io.androllm.core.network.identity

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 1 identity contract tests — UserProfile JSON must match the private
 * backend (`POST /auth/verify`, `GET /me`) exactly.
 * Phase 2 adds the sync-gate rule: nothing may sync before `web_connected`.
 */
class UserProfileSerializationTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    @Test
    fun `user profile deserializes backend payload`() {
        val payload = """
            {
              "id": "550e8400-e29b-41d4-a716-446655440000",
              "firebase_uid": "firebaseUid123",
              "email": "user@example.com",
              "display_name": "Ada",
              "photo_url": "https://example.com/a.png",
              "created_at": "2026-09-21T00:00:00.000Z",
              "last_seen_at": "2026-09-21T00:00:00.000Z",
              "sync_enabled": false,
              "settings": {},
              "web_connected": false,
              "web_connected_at": null
            }
        """.trimIndent()
        val profile = json.decodeFromString(UserProfile.serializer(), payload)
        assertEquals("firebaseUid123", profile.firebaseUid)
        assertEquals("user@example.com", profile.email)
        assertFalse(profile.syncEnabled)
        assertFalse(profile.webConnected)
    }

    @Test
    fun `verify request omits null hints`() {
        val encoded = json.encodeToString(
            AuthVerifyRequest.serializer(),
            AuthVerifyRequest(displayName = null, photoUrl = null, device = DeviceInfo(platform = "android")),
        )
        // No user id may ever be sent — identity comes from the Bearer token.
        assertFalse(encoded.contains("firebase_uid"))
        assertFalse(encoded.contains("display_name"))
    }

    @Test
    fun `sync gate denies unconnected profiles`() {
        val payload = """
            {
              "id": "550e8400-e29b-41d4-a716-446655440000",
              "firebase_uid": "firebaseUid123",
              "email": "user@example.com",
              "display_name": "Ada",
              "photo_url": null,
              "created_at": "2026-09-21T00:00:00.000Z",
              "last_seen_at": "2026-09-21T00:00:00.000Z",
              "sync_enabled": false,
              "settings": {},
              "web_connected": false,
              "web_connected_at": null
            }
        """.trimIndent()
        val unconnected = json.decodeFromString(UserProfile.serializer(), payload)
        assertFalse(unconnected.canSyncUsage())

        val connected = unconnected.copy(
            webConnected = true,
            webConnectedAt = "2026-09-22T00:00:00.000Z",
        )
        assertTrue(connected.canSyncUsage())
    }
}
