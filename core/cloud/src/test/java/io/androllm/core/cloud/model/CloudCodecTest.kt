package io.androllm.core.cloud.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class CloudCodecTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    @Test
    fun `cloud settings round-trips with providers favorites and defaults`() {
        val original = CloudSettings(
            enabled = true,
            defaultProviderId = "p1",
            defaultModelId = "anthropic/claude-3-5-sonnet",
            favoriteModelIds = setOf("openai/gpt-4o", "gemini/gemini-pro"),
            providers = listOf(
                CloudProvider(
                    id = "p1",
                    name = "Prod",
                    baseUrl = "https://litellm.example.com",
                    apiKeyEncrypted = "enc:abc123",
                    apiKeyHeader = "Authorization",
                    extraHeaders = mapOf("X-Tenant" to "team-a"),
                    tags = listOf("prod", "eu"),
                    enabled = true,
                    isDefault = true,
                    modelIds = listOf("openai/gpt-4o", "anthropic/claude-3-5-sonnet"),
                    latencyMs = 42,
                    lastError = "",
                    quota = CloudQuota(remainingRequests = 99, remainingTokens = 5000, lastStatus = 200)
                )
            )
        )

        val encoded = json.encodeToString(CloudSettings.serializer(), original)
        val decoded = json.decodeFromString(CloudSettings.serializer(), encoded)

        assertEquals(original, decoded)
    }

    @Test
    fun `empty settings round-trip`() {
        val encoded = json.encodeToString(CloudSettings.serializer(), CloudSettings())
        val decoded = json.decodeFromString(CloudSettings.serializer(), encoded)
        assertEquals(CloudSettings(), decoded)
    }

    @Test
    fun `wire format uses snake_case and omits null top_k`() {
        val request = CloudChatRequest(
            model = "openai/gpt-4o",
            messages = listOf(CloudChatMessage("user", "hi")),
            max_tokens = 64,
            stream = true,
            top_k = null
        )
        val encoded = json.encodeToString(CloudChatRequest.serializer(), request)
        assertEquals(false, encoded.contains("\"top_k\""))
        assertEquals(true, encoded.contains("\"max_tokens\""))
        assertEquals(true, encoded.contains("\"stream\":true"))
    }
}
