package io.androllm.core.cloud

import io.androllm.core.cloud.model.CloudChatMessage
import io.androllm.core.cloud.model.CloudException
import io.androllm.core.cloud.model.CloudGenerationConfig
import io.androllm.core.cloud.model.CloudProvider
import io.androllm.core.cloud.model.CloudSettings
import io.androllm.core.cloud.model.CloudStreamEvent
import io.androllm.core.cloud.network.LiteLLMClient
import io.androllm.core.cloud.network.RetryPolicy
import io.androllm.core.cloud.pipeline.CloudRequestPlanner
import io.androllm.core.cloud.pipeline.CloudResultObserver
import io.androllm.core.cloud.cache.PromptCache
import io.androllm.core.cloud.security.KeyCipher
import io.androllm.core.cloud.usage.CloudErrorKind
import io.androllm.core.cloud.usage.CloudUsageMeter
import io.androllm.core.cloud.usage.InMemoryCloudUsageStore
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Gateway-level pipeline behavior: provider fallback on pre-stream failures,
 * rate-limit handling, usage recording (tokens, cache, tools), validation
 * rejection, and mid-stream failure surfacing.
 */
class CloudGatewayPipelineTest {

    private lateinit var primaryServer: MockWebServer
    private lateinit var fallbackServer: MockWebServer
    private lateinit var gateway: CloudGateway
    private lateinit var meter: CloudUsageMeter
    private val store = InMemoryCloudUsageStore()

    /** Plain-text cipher for tests (no Android Keystore on the JVM). */
    private val plainCipher = object : KeyCipher {
        override fun encrypt(plaintext: String) = plaintext
        override fun decrypt(ciphertext: String) = ciphertext
        override fun delete() = Unit
    }

    private inner class FakeSettingsRepository(
        initial: CloudSettings
    ) : CloudSettingsRepository {
        private val flow = MutableStateFlow(initial)
        override val settings: Flow<CloudSettings> = flow
        override suspend fun current(): CloudSettings = flow.value
        override suspend fun update(transform: (CloudSettings) -> CloudSettings) {
            flow.value = transform(flow.value)
        }
    }

    @Before
    fun setUp() {
        primaryServer = MockWebServer()
        fallbackServer = MockWebServer()
        val client = LiteLLMClient(
            OkHttpClient.Builder()
                .connectTimeout(2, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .build(),
            RetryPolicy(maxAttempts = 1, initialDelayMs = 1, maxDelayMs = 5, jitterMs = 0)
        )
        val settings = CloudSettings(
            enabled = true,
            providers = listOf(
                CloudProvider(
                    id = "primary",
                    name = "Primary",
                    baseUrl = primaryServer.url("/").toString(),
                    apiKeyEncrypted = "sk-primary",
                    modelIds = listOf("openai/gpt-4o"),
                    enabled = true,
                    isDefault = true
                ),
                CloudProvider(
                    id = "fallback",
                    name = "Fallback",
                    baseUrl = fallbackServer.url("/").toString(),
                    apiKeyEncrypted = "sk-fallback",
                    modelIds = listOf("openai/gpt-4o"),
                    enabled = true
                )
            ),
            defaultProviderId = "primary",
            defaultModelId = "openai/gpt-4o"
        )
        val manager = ProviderManager(FakeSettingsRepository(settings), client, plainCipher)
        meter = CloudUsageMeter(store, persistDebounceMs = 5)
        gateway = CloudGateway(
            client = client,
            manager = manager,
            usageMeter = meter,
            requestPlanner = CloudRequestPlanner(PromptCache(diskFile = null)),
            resultObserver = CloudResultObserver()
        )
    }

    @After
    fun tearDown() {
        primaryServer.shutdown()
        fallbackServer.shutdown()
    }

    private fun sse(body: String) = buildString {
        append("""data: {"choices":[{"delta":{"content":"$body"}}]}""").append("\n\n")
        append("""data: {"choices":[{"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":7,"completion_tokens":3,"total_tokens":10}}""").append("\n\n")
        append("data: [DONE]").append("\n\n")
    }

    private val config = CloudGenerationConfig(temperature = 0.5)
    private val messages = listOf(
        CloudChatMessage("system", "You are helpful."),
        CloudChatMessage("user", "hi")
    )

    @Test
    fun `primary failure before first token falls back to second provider`() = runBlocking {
        meter.init()
        primaryServer.enqueue(MockResponse().setResponseCode(500).setBody("{\"error\":\"boom\"}"))
        fallbackServer.enqueue(
            MockResponse().setResponseCode(200).setHeader("Content-Type", "text/event-stream").setBody(sse("from fallback"))
        )

        val events = gateway.streamChat(messages, config, retries = 0).toList()
        val deltas = events.filterIsInstance<CloudStreamEvent.Delta>().joinToString("") { it.text }
        assertEquals("from fallback", deltas)

        // Both attempts are recorded; the successful one used the fallback.
        val records = meter.records()
        assertEquals(2, records.size)
        val success = records.find { it.success }!!
        assertTrue(success.usedFallbackProvider)
        assertEquals("fallback", success.providerId)
        assertEquals(10L, success.totalTokens)
        val failure = records.find { !it.success }!!
        assertEquals("primary", failure.providerId)
        assertEquals(CloudErrorKind.HTTP_ERROR, failure.errorKind)
    }

    @Test
    fun `rate limited primary falls back`() = runBlocking {
        meter.init()
        primaryServer.enqueue(MockResponse().setResponseCode(429).setBody("{\"error\":\"slow down\"}"))
        fallbackServer.enqueue(
            MockResponse().setResponseCode(200).setHeader("Content-Type", "text/event-stream").setBody(sse("ok"))
        )

        val events = gateway.streamChat(messages, config, retries = 0).toList()
        assertTrue(events.any { it is CloudStreamEvent.Delta })
        val success = meter.records().find { it.success }!!
        assertTrue(success.usedFallbackProvider)
        // The 429 is recorded as a rate-limit hit for the dashboard.
        assertEquals(1, meter.snapshot().total.rateLimitHits)
    }

    @Test
    fun `healthy primary serves directly without fallback`() = runBlocking {
        meter.init()
        primaryServer.enqueue(
            MockResponse().setResponseCode(200).setHeader("Content-Type", "text/event-stream").setBody(sse("primary answer"))
        )

        val events = gateway.streamChat(messages, config).toList()
        val text = events.filterIsInstance<CloudStreamEvent.Delta>().joinToString("") { it.text }
        assertEquals("primary answer", text)
        assertEquals(0, fallbackServer.requestCount)

        val record = meter.records().single()
        assertTrue(record.success)
        assertFalse(record.usedFallbackProvider)
        assertEquals(7L, record.inputTokens)
        assertEquals(3L, record.outputTokens)
        assertTrue(record.latencyMs >= 0)
    }

    @Test
    fun `cache hit is recorded on the second conversation turn`() = runBlocking {
        meter.init()
        primaryServer.enqueue(
            MockResponse().setResponseCode(200).setHeader("Content-Type", "text/event-stream").setBody(sse("a"))
        )
        primaryServer.enqueue(
            MockResponse().setResponseCode(200).setHeader("Content-Type", "text/event-stream").setBody(sse("b"))
        )

        gateway.streamChat(messages, config, sessionId = "conv-1").toList()
        gateway.streamChat(messages + CloudChatMessage("user", "more"), config, sessionId = "conv-1").toList()

        val records = meter.records()
        assertEquals(2, records.size)
        // Newest first: the second turn hit the prompt cache.
        assertTrue(records[0].cacheHit)
        assertTrue(records[0].cacheSavedTokens > 0)
        assertFalse(records[1].cacheHit)
    }

    @Test
    fun `all providers failing surfaces cloud exception`() = runBlocking {
        meter.init()
        primaryServer.enqueue(MockResponse().setResponseCode(500).setBody("{}"))
        fallbackServer.enqueue(MockResponse().setResponseCode(503).setBody("{}"))

        try {
            gateway.streamChat(messages, config, retries = 0).toList()
            fail("expected CloudException")
        } catch (e: CloudException) {
            assertTrue(e.message!!.isNotBlank())
        }
        // Both failures are recorded.
        assertEquals(2, meter.records().count { !it.success })
    }

    @Test
    fun `validation failure rejects before any network call`() = runBlocking {
        meter.init()
        try {
            gateway.streamChat(emptyList(), config).toList()
            fail("expected CloudException")
        } catch (e: CloudException) {
            assertTrue(e.message!!.contains("rejected"))
        }
        assertEquals(0, primaryServer.requestCount)
        val record = meter.records().single()
        assertFalse(record.success)
        assertEquals(CloudErrorKind.MALFORMED, record.errorKind)
    }

    @Test
    fun `chatOnce falls back and records usage`() = runBlocking {
        meter.init()
        // 401 is non-retryable at the transport layer, so the failure surfaces
        // immediately and the GATEWAY-level fallback (next provider) handles it.
        primaryServer.enqueue(MockResponse().setResponseCode(401).setBody("{}"))
        fallbackServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"id":"1","object":"chat.completion","model":"gpt-4o",""" +
                        """"choices":[{"index":0,"message":{"role":"assistant","content":"fallback once"},"finish_reason":"stop"}],""" +
                        """"usage":{"prompt_tokens":4,"completion_tokens":2,"total_tokens":6}}"""
                )
        )

        val answer = gateway.chatOnce(messages, config)
        assertEquals("fallback once", answer)
        val success = meter.records().find { it.success }!!
        assertTrue(success.usedFallbackProvider)
        assertEquals(6L, success.totalTokens)
        assertFalse(success.streamed)
    }

    @Test
    fun `tool calls in response are counted in usage`() = runBlocking {
        meter.init()
        primaryServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(buildString {
                    append("""data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"c1","function":{"name":"get_weather","arguments":"{\"city\":\"Berlin\"}"}}]}}]}""").append("\n\n")
                    append("""data: {"choices":[{"delta":{},"finish_reason":"tool_calls"}],"usage":{"prompt_tokens":5,"completion_tokens":4,"total_tokens":9}}""").append("\n\n")
                    append("data: [DONE]").append("\n\n")
                })
        )

        gateway.streamChat(messages, config).toList()
        val record = meter.records().single()
        assertEquals(1, record.toolCallsCount)
        assertEquals("tool_calls", record.finishReason)
    }
}
