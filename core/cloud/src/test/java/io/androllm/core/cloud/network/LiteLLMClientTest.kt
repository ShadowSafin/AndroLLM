package io.androllm.core.cloud.network

import io.androllm.core.cloud.model.CloudChatMessage
import io.androllm.core.cloud.model.CloudChatRequest
import io.androllm.core.cloud.model.CloudException
import io.androllm.core.cloud.model.CloudProvider
import io.androllm.core.cloud.model.CloudStreamEvent
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LiteLLMClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: LiteLLMClient
    private val retryPolicy = RetryPolicy(maxAttempts = 3, initialDelayMs = 1, maxDelayMs = 10, jitterMs = 0)

    /** Points at the live MockWebServer; server.url() requires a started server. */
    private val provider: CloudProvider
        get() = CloudProvider(
            id = "p1",
            name = "Proxy",
            baseUrl = server.url("/proxy").toString(),
            apiKeyHeader = "Authorization"
        )

    @Before
    fun setUp() {
        server = MockWebServer()
        client = LiteLLMClient(
            OkHttpClient.Builder()
                .connectTimeout(2, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .build(),
            retryPolicy
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `chat posts to v1 chat completions and parses response`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"id":"chatcmpl-1","object":"chat.completion","model":"gpt-4o",""" +
                        """"choices":[{"index":0,"message":{"role":"assistant","content":"Hello!"},"finish_reason":"stop"}],""" +
                        """"usage":{"prompt_tokens":3,"completion_tokens":2,"total_tokens":5}}"""
                )
        )
        val response = client.chat(
            provider, "sk-test",
            CloudChatRequest(model = "openai/gpt-4o", messages = listOf(CloudChatMessage("user", "hi")), stream = false)
        )

        assertEquals("Hello!", response.choices.first().message?.content)
        assertEquals(5L, response.usage?.total_tokens)
        val request = server.takeRequest()
        assertEquals("/proxy/v1/chat/completions", request.path)
        assertEquals("Bearer sk-test", request.getHeader("Authorization"))
        assertTrue(request.body.readUtf8().contains("\"stream\":false"))
    }

    @Test
    fun `streamChat emits deltas usage and done`() = runTest {
        val sse = buildString {
            append("""data: {"choices":[{"delta":{"role":"assistant"}}]}""").append("\n\n")
            append("""data: {"choices":[{"delta":{"content":"Hello"}}]}""").append("\n\n")
            append("""data: {"choices":[{"delta":{"content":" world"}}]}""").append("\n\n")
            append("""data: {"choices":[{"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":3,"completion_tokens":2,"total_tokens":5}}""").append("\n\n")
            append("data: [DONE]").append("\n\n")
        }
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(sse)
        )

        val events = client.streamChat(
            provider, "sk-test",
            CloudChatRequest(model = "openai/gpt-4o", messages = listOf(CloudChatMessage("user", "hi")), stream = true)
        ).toList()

        assertEquals(
            listOf(
                CloudStreamEvent.Delta("Hello"),
                CloudStreamEvent.Delta(" world"),
                CloudStreamEvent.Usage(3, 2, 5),
                // The terminal chunk carries finish_reason="stop" — surfaced
                // so callers can tell a natural stop from a "length" truncation.
                CloudStreamEvent.Finish("stop"),
                CloudStreamEvent.Done
            ),
            events
        )
    }

    @Test
    fun `streamChat emits reasoning and tool call deltas`() = runTest {
        val sse = buildString {
            append("""data: {"choices":[{"delta":{"reasoning_content":"think"}}]}""").append("\n\n")
            append("""data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"get_weather","arguments":"{\"city\":"}}]}}]}""").append("\n\n")
            append("""data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"Berlin\"}"}}]}}]}""").append("\n\n")
            append("data: [DONE]").append("\n\n")
        }
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(sse)
        )

        val events = client.streamChat(
            provider, "sk-test",
            CloudChatRequest(model = "deepseek/deepseek-r1", messages = listOf(CloudChatMessage("user", "hi")), stream = true)
        ).toList()

        assertEquals(
            listOf(
                CloudStreamEvent.Reasoning("think"),
                CloudStreamEvent.ToolCallDelta(0, "call_1", "get_weather", "{\"city\":"),
                CloudStreamEvent.ToolCallDelta(0, null, null, "\"Berlin\"}"),
                CloudStreamEvent.Done
            ),
            events
        )
    }

    @Test
    fun `stream deltas arrive progressively on a byte-throttled connection`() = runBlocking {
        val sse = buildString {
            repeat(3) { i ->
                append("""data: {"choices":[{"delta":{"content":"token-$i"}}]}""").append("\n\n")
            }
            append("data: [DONE]").append("\n\n")
        }
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(sse)
                .throttleBody(1L, 20L, TimeUnit.MILLISECONDS)
        )
        val slowClient = LiteLLMClient(
            OkHttpClient.Builder()
                .connectTimeout(2, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build(),
            retryPolicy
        )

        val arrivalNs = java.util.Collections.synchronizedList(mutableListOf<Long>())
        slowClient.streamChat(
            provider, "sk-test",
            CloudChatRequest(model = "openai/gpt-4o", messages = listOf(CloudChatMessage("user", "hi")), stream = true)
        ).collect { event ->
            if (event is CloudStreamEvent.Delta) arrivalNs.add(System.nanoTime())
        }

        assertEquals(3, arrivalNs.size)
        val firstToLast = (arrivalNs.last() - arrivalNs.first()) / 1_000_000L
        val maxGapMs = arrivalNs.zipWithNext().maxOf { (it.second - it.first) } / 1_000_000L
        // 1 byte every 20ms plus line framing: real tokens must be widely spaced.
        assertTrue("deltas arrived together in ${firstToLast}ms", firstToLast >= 150)
        assertTrue("no inter-delta gap (max ${maxGapMs}ms)", maxGapMs >= 40)

        val request = server.takeRequest()
        assertEquals("text/event-stream", request.getHeader("Accept"))
        assertEquals("no-cache", request.getHeader("Cache-Control"))
        assertEquals("no", request.getHeader("X-Accel-Buffering"))
        assertTrue(request.body.readUtf8().contains("\"stream\":true"))
    }

    @Test
    fun `streamChat retries on 429 then succeeds`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":{"message":"rate limited"}}"""))
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody("""data: {"choices":[{"delta":{"content":"Recovered"}}]}""" + "\n\n" + "data: [DONE]\n\n")
        )

        val events = client.streamChat(
            provider, null,
            CloudChatRequest(model = "openai/gpt-4o", messages = listOf(CloudChatMessage("user", "hi")), stream = true)
        ).toList()

        assertEquals(listOf(CloudStreamEvent.Delta("Recovered"), CloudStreamEvent.Done), events)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `auth failure maps to readable message`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"Invalid key"}}"""))

        val thrown = runCatching {
            client.chat(
                provider, "sk-bad",
                CloudChatRequest(model = "openai/gpt-4o", messages = listOf(CloudChatMessage("user", "hi")))
            )
        }.exceptionOrNull()

        assertTrue(thrown is CloudException)
        assertTrue(thrown!!.message!!.contains("Authentication failed"))
        assertEquals(401, (thrown as CloudException).statusCode)
    }

    @Test
    fun `non retryable 404 fails without retrying`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404).setBody("not found"))

        val thrown = runCatching {
            client.chat(provider, "sk-test", CloudChatRequest(model = "openai/gpt-4o", messages = emptyList()))
        }.exceptionOrNull()

        assertTrue(thrown is CloudException)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `listModels parses model list and captures quota headers`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .addHeader("x-ratelimit-remaining-requests", "10")
                .addHeader("x-ratelimit-remaining-tokens", "500")
                .setBody(
                    """{"object":"list","data":[""" +
                        """{"id":"openai/gpt-4o","object":"model","created":123,"owned_by":"openai"},""" +
                        """{"id":"anthropic/claude-3-5-sonnet","object":"model","created":124,"owned_by":"anthropic"}]}"""
                )
        )

        val (models, quota) = client.listModelsWithQuota(provider, "sk-test")

        assertEquals(listOf("openai/gpt-4o", "anthropic/claude-3-5-sonnet"), models.map { it.id })
        assertEquals(10L, quota.remainingRequests)
        assertEquals(500L, quota.remainingTokens)
    }

    @Test
    fun `listModelMetadata maps context windows and max output tokens from model info`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"data":[""" +
                        """{"model_name":"openai/gpt-4o","model_info":{"id":"gpt-4o","context_window":128000,"max_input_tokens":128000,"max_output_tokens":16384,"mode":"chat"}},""" +
                        """{"model_name":"gemini/gemini-pro","model_info":{"id":"gemini-pro","max_input_tokens":1000000,"max_output_tokens":65536,"mode":"chat"}}]}"""
                )
        )

        val metadata = client.listModelMetadata(provider, "sk-test")

        assertEquals(128_000L, metadata.contextWindows["openai/gpt-4o"])
        assertEquals(1_000_000L, metadata.contextWindows["gemini/gemini-pro"])
        assertEquals(16_384L, metadata.maxOutputTokens["openai/gpt-4o"])
        assertEquals(65_536L, metadata.maxOutputTokens["gemini/gemini-pro"])
        assertEquals("/proxy/v1/model/info", server.takeRequest().path)
    }

    @Test
    fun `embeddings returns vectors`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"object":"list","data":[{"object":"embedding","index":0,"embedding":[0.1,0.2,0.3]}],""" +
                        """"model":"openai/text-embedding-3-small","usage":{"prompt_tokens":2,"total_tokens":2}}"""
                )
        )

        val response = client.embeddings(provider, "sk-test", "openai/text-embedding-3-small", listOf("hello"))

        assertEquals(listOf(0.1f, 0.2f, 0.3f), response.data.first().embedding)
    }

    @Test
    fun `health reports alive but not ready when readiness fails`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":"alive"}"""))
        server.enqueue(MockResponse().setResponseCode(503).setBody("""{"status":"unhealthy"}"""))

        val health = client.health(provider, null)

        assertTrue(health.reachable)
        assertTrue(health.alive)
        assertTrue(!health.ready)
        assertTrue(health.supportsHealthEndpoints)
    }

    @Test
    fun `health treats missing endpoints as reachable OpenAI-compatible router`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404).setBody("not found"))
        server.enqueue(MockResponse().setResponseCode(404).setBody("not found"))

        val health = client.health(provider, null)

        assertTrue(health.reachable)
        assertTrue(!health.alive)
        assertTrue(!health.ready)
        assertTrue(!health.supportsHealthEndpoints)
    }

    @Test
    fun `health reports unreachable when probes fail on transport`() = runTest {
        server.shutdown()

        val health = client.health(provider, null, retries = 0)

        assertTrue(!health.reachable)
        assertTrue(!health.supportsHealthEndpoints)
    }

    @Test
    fun `custom auth header name is honored`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"object":"list","data":[]}"""))
        val customProvider = provider.copy(apiKeyHeader = "X-Litellm-Key")

        client.listModels(customProvider, "sk-custom")

        assertEquals("sk-custom", server.takeRequest().getHeader("X-Litellm-Key"))
    }

    @Test
    fun `custom model overrides route to alternate server with own key`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"id":"x","choices":[{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}""")
        )
        val overrides = io.androllm.core.cloud.model.CloudModelOverrides(
            apiBaseUrl = server.url("/custom").toString(),
            apiKey = "sk-custom-model",
            apiKeyHeader = "X-Custom-Key"
        )

        client.chat(provider, "sk-provider", CloudChatRequest("custom-model", messages = emptyList()), overrides = overrides)

        val request = server.takeRequest()
        assertEquals("/custom/v1/chat/completions", request.path)
        assertEquals("sk-custom-model", request.getHeader("X-Custom-Key"))
    }
}