package io.androllm.core.network.identity

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 3 (revised) — sync payload contracts. The JSON keys here must match
 * the private backend (POST /events/batch, POST /analytics/snapshot) exactly.
 */
class SyncApiSerializationTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    @Test
    fun `sync event round-trips with source page`() {
        val event = SyncEvent(
            eventId = "evt-1",
            eventType = "chat_completed",
            sourcePage = SourcePage.CLOUD_USAGE_DASHBOARD,
            modelName = "gpt-4o",
            providerName = "openai",
            engineType = "cloud",
            tokensInput = 10,
            tokensOutput = 20,
            totalTokens = 30,
            latencyMs = 2500L,
            timeToFirstTokenMs = 800L,
            success = true,
            createdAt = "2026-09-21T13:00:00Z",
        )
        val decoded = json.decodeFromString(SyncEvent.serializer(), json.encodeToString(SyncEvent.serializer(), event))
        assertEquals(event, decoded)
    }

    @Test
    fun `batch request serializes device and events`() {
        val body = EventsBatchRequest(
            device = SyncDevice(deviceIdentifier = "android:abc", deviceName = "Pixel", appVersion = "1.1.6"),
            events = listOf(SyncEvent(eventId = "e1", eventType = "chat_completed")),
        )
        val encoded = json.encodeToString(EventsBatchRequest.serializer(), body)
        assertTrue(encoded.contains("device_identifier"))
        assertTrue(encoded.contains("chat_completed"))
        // Never a user id — identity travels in the Bearer token.
        assertTrue(!encoded.contains("firebase_uid") && !encoded.contains("user_id"))
    }

    @Test
    fun `snapshot request round-trips`() {
        val payload: JsonObject = buildJsonObject { put("requests", 10) }
        val req = SnapshotRequest(
            device = SyncDevice(deviceIdentifier = "android:abc"),
            sourcePage = SourcePage.DEVELOPER_PAGE,
            snapshotType = "telemetry_summary",
            snapshotId = "snap-developer_page:2026-09-21-14",
            payload = payload,
        )
        val decoded = json.decodeFromString(SnapshotRequest.serializer(), json.encodeToString(SnapshotRequest.serializer(), req))
        assertEquals(req, decoded)
    }

    @Test
    fun `batch response defaults tolerate empty backend replies`() {
        val decoded = json.decodeFromString(EventsBatchResponse.serializer(), "{}")
        assertEquals(0, decoded.accepted)
        assertTrue(decoded.rejected.isEmpty())
    }
}
