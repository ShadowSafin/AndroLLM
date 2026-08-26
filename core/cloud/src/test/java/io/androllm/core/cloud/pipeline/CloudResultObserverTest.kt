package io.androllm.core.cloud.pipeline

import io.androllm.core.cloud.model.CloudException
import io.androllm.core.cloud.model.CloudStreamEvent
import io.androllm.core.cloud.usage.CloudErrorKind
import java.net.SocketTimeoutException
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Result observation: event folding, streaming tool-call accumulation,
 * usage capture, latency measurement, error normalization and fallback
 * eligibility.
 */
class CloudResultObserverTest {

    private var now = 1_000L
    private val observer = CloudResultObserver(clock = { now })

    @Test
    fun `accumulates content deltas and measures first token`() = runBlocking {
        val events = flow {
            emit(CloudStreamEvent.Delta("Hello"))
            emit(CloudStreamEvent.Delta(" world"))
            emit(CloudStreamEvent.Finish("stop"))
            emit(CloudStreamEvent.Done)
        }
        val result = observer.observe(events)
        assertEquals("Hello world", result.text)
        assertEquals("stop", result.finishReason)
        assertTrue(result.success)
        assertNotNull(result.firstTokenMs)
        assertTrue(result.receivedAnyEvent)
    }

    @Test
    fun `merges streaming tool call fragments by index`() = runBlocking {
        val events = flow {
            emit(CloudStreamEvent.ToolCallDelta(index = 0, id = "call_1", name = "get_weather", arguments = "{\"loc"))
            emit(CloudStreamEvent.ToolCallDelta(index = 0, id = null, name = null, arguments = "ation\":\"Berlin\"}"))
            emit(CloudStreamEvent.ToolCallDelta(index = 1, id = "call_2", name = "calculate", arguments = "{\"expression\":\"2+2\"}"))
            emit(CloudStreamEvent.Finish("tool_calls"))
            emit(CloudStreamEvent.Done)
        }
        val result = observer.observe(events)
        assertEquals(2, result.toolCalls.size)
        val weather = result.toolCalls.find { it.name == "get_weather" }!!
        assertEquals("call_1", weather.id)
        assertEquals("{\"location\":\"Berlin\"}", weather.argumentsJson)
        assertEquals("tool_calls", result.finishReason)
    }

    @Test
    fun `malformed tool arguments are neutralized to empty object`() = runBlocking {
        val events = flow {
            emit(CloudStreamEvent.ToolCallDelta(index = 0, id = "c", name = "get_weather", arguments = "{\"location\": "))
            emit(CloudStreamEvent.Done)
        }
        val result = observer.observe(events)
        assertEquals(1, result.toolCalls.size)
        assertEquals("{}", result.toolCalls[0].argumentsJson)
    }

    @Test
    fun `tool fragment without name is dropped`() = runBlocking {
        val events = flow {
            emit(CloudStreamEvent.ToolCallDelta(index = 0, id = "c", name = null, arguments = "{\"a\":1}"))
            emit(CloudStreamEvent.Done)
        }
        val result = observer.observe(events)
        assertTrue(result.toolCalls.isEmpty())
    }

    @Test
    fun `usage event is captured`() = runBlocking {
        val events = flow {
            emit(CloudStreamEvent.Delta("hi"))
            emit(CloudStreamEvent.Usage(promptTokens = 10, completionTokens = 5, totalTokens = 15))
            emit(CloudStreamEvent.Done)
        }
        val result = observer.observe(events)
        assertNotNull(result.usage)
        assertEquals(10L, result.usage!!.promptTokens)
        assertEquals(15L, result.usage!!.totalTokens)
    }

    @Test
    fun `every event is forwarded to onEvent in order`() = runBlocking {
        val seen = mutableListOf<CloudStreamEvent>()
        val events = flowOf(
            CloudStreamEvent.Delta("a"),
            CloudStreamEvent.Usage(1, 1, 2),
            CloudStreamEvent.Done
        )
        observer.observe(events) { seen += it }
        assertEquals(3, seen.size)
        assertTrue(seen[0] is CloudStreamEvent.Delta)
        assertTrue(seen[2] is CloudStreamEvent.Done)
    }

    @Test
    fun `rate limit error is normalized`() = runBlocking {
        val events = flow<CloudStreamEvent> {
            throw CloudException("Rate limit exceeded", statusCode = 429)
        }
        val result = observer.observe(events)
        assertFalse(result.success)
        assertEquals(CloudErrorKind.RATE_LIMIT, result.errorKind)
        assertTrue(result.errorMessage.contains("Rate limit"))
    }

    @Test
    fun `server error maps to http error`() = runBlocking {
        val events = flow<CloudStreamEvent> {
            throw CloudException("Server exploded", statusCode = 503)
        }
        assertEquals(CloudErrorKind.HTTP_ERROR, observer.observe(events).errorKind)
    }

    @Test
    fun `timeout maps to timeout`() = runBlocking {
        val events = flow<CloudStreamEvent> {
            throw SocketTimeoutException("read timed out")
        }
        assertEquals(CloudErrorKind.TIMEOUT, observer.observe(events).errorKind)
    }

    @Test
    fun `partial stream failure keeps received text`() = runBlocking {
        val events = flow<CloudStreamEvent> {
            emit(CloudStreamEvent.Delta("partial answer"))
            throw CloudException("stream died", statusCode = 500)
        }
        val result = observer.observe(events)
        assertEquals("partial answer", result.text)
        assertFalse(result.success)
        assertTrue(result.receivedAnyEvent)
    }

    @Test
    fun `fallback eligibility requires failure before any event`() = runBlocking {
        val failedEarly = flow<CloudStreamEvent> { throw CloudException("down", statusCode = 503) }
        val early = observer.observe(failedEarly)
        assertTrue(observer.isFallbackEligible(early))

        val failedLate = flow<CloudStreamEvent> {
            emit(CloudStreamEvent.Delta("x"))
            throw CloudException("died mid-stream", statusCode = 500)
        }
        val late = observer.observe(failedLate)
        assertFalse(observer.isFallbackEligible(late))

        val authFailure = observer.observe(
            flow<CloudStreamEvent> { throw CloudException("auth", statusCode = 401) }
        )
        // 401 → HTTP_ERROR which is fallback-eligible by category, but the
        // same credentials usually fail everywhere; category-wise it passes.
        assertEquals(CloudErrorKind.HTTP_ERROR, authFailure.errorKind)
    }

    @Test
    fun `empty stream yields empty successful result`() = runBlocking {
        val result = observer.observe(flowOf(CloudStreamEvent.Done))
        assertTrue(result.success)
        assertEquals("", result.text)
        assertNull(result.firstTokenMs)
    }
}
