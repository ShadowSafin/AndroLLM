package io.androllm.core.cloud.model

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CloudChatMessageSerializerTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `plain text message serializes as string content`() {
        val encoded = json.encodeToString(
            CloudChatMessage.serializer(),
            CloudChatMessage(role = "user", content = "hi")
        )
        assertEquals("""{"role":"user","content":"hi"}""", encoded)
    }

    @Test
    fun `vision message serializes as content part array`() {
        val message = CloudChatMessage.withImage("What is this?", "https://example.com/cat.png")
        val encoded = json.encodeToString(CloudChatMessage.serializer(), message)
        assertEquals(
            """{"role":"user","content":[{"type":"text","text":"What is this?"},""" +
                """{"type":"image_url","image_url":{"url":"https://example.com/cat.png"}}]}""",
            encoded
        )
    }

    @Test
    fun `tool call message serializes tool_calls and null content`() {
        val message = CloudChatMessage(
            role = "assistant",
            content = null,
            toolCalls = listOf(
                CloudToolCall(
                    index = 0,
                    id = "call_1",
                    function = CloudToolCallFunction(name = "get_weather", arguments = "{\"city\":\"Berlin\"}")
                )
            )
        )
        val encoded = json.encodeToString(CloudChatMessage.serializer(), message)
        assertEquals(
            """{"role":"assistant","content":null,"tool_calls":[""" +
                """{"index":0,"id":"call_1","type":"function","function":{"name":"get_weather","arguments":"{\"city\":\"Berlin\"}"}}]}""",
            encoded
        )
    }

    @Test
    fun `decodes string content and role`() {
        val decoded = json.decodeFromString(
            CloudChatMessage.serializer(),
            """{"role":"assistant","content":"Hello!"}"""
        )
        assertEquals("assistant", decoded.role)
        assertEquals("Hello!", decoded.content)
        assertNull(decoded.contentParts)
    }

    @Test
    fun `decodes content part array skipping unknown types`() {
        val decoded = json.decodeFromString(
            CloudChatMessage.serializer(),
            """{"role":"user","content":[""" +
                """{"type":"text","text":"Look"},""" +
                """{"type":"image_url","image_url":{"url":"data:image/png;base64,AAA"}},""" +
                """{"type":"audio","audio":{"data":"xyz"}}]}"""
        )
        assertEquals(
            listOf(CloudContentPart.Text("Look"), CloudContentPart.Image("data:image/png;base64,AAA")),
            decoded.contentParts
        )
        assertNull(decoded.content)
    }

    @Test
    fun `decodes assistant message with tool calls`() {
        val decoded = json.decodeFromString(
            CloudChatMessage.serializer(),
            """{"role":"assistant","content":null,"tool_calls":[""" +
                """{"index":0,"id":"call_1","function":{"name":"get_weather","arguments":"{}"}}]}"""
        )
        assertEquals("call_1", decoded.toolCalls?.first()?.id)
        assertEquals("get_weather", decoded.toolCalls?.first()?.function?.name)
    }

    @Test
    fun `full chat request with tools and schema round-trips`() {
        val request = CloudChatRequest(
            model = "openai/gpt-4o",
            messages = listOf(
                CloudChatMessage(role = "user", content = "hi"),
                CloudChatMessage.withImage(null, "https://example.com/pic.jpg")
            ),
            tools = listOf(
                CloudTool(
                    function = CloudToolFunction(
                        name = "get_weather",
                        description = "Get weather",
                        parameters = emptyMap()
                    )
                )
            ),
            response_format = CloudResponseFormat(type = "json_schema", json_schema = null),
            stream = false
        )
        val encoded = json.encodeToString(CloudChatRequest.serializer(), request)
        val decoded = json.decodeFromString(CloudChatRequest.serializer(), encoded)
        assertEquals(2, decoded.messages.size)
        assertEquals("get_weather", decoded.tools.first().function.name)
        assertEquals("json_schema", decoded.response_format?.type)
    }

    @Test
    fun `chat response with tool call decodes`() {
        val response = json.decodeFromString(
            CloudChatResponse.serializer(),
            """{"id":"1","choices":[{"index":0,"message":{"role":"assistant","content":null,"tool_calls":[""" +
                """{"id":"call_1","function":{"name":"f","arguments":"{}"}}]},"finish_reason":"tool_calls"}]}"""
        )
        assertEquals("tool_calls", response.choices.first().finish_reason)
        assertEquals("f", response.choices.first().message?.toolCalls?.first()?.function?.name)
    }

    @Test
    fun `list serializer round-trips messages`() {
        val messages = listOf(
            CloudChatMessage(role = "user", content = "a"),
            CloudChatMessage(role = "assistant", content = "b")
        )
        val encoded = json.encodeToString(ListSerializer(CloudChatMessage.serializer()), messages)
        val decoded = json.decodeFromString(ListSerializer(CloudChatMessage.serializer()), encoded)
        assertEquals(messages, decoded)
    }
}
