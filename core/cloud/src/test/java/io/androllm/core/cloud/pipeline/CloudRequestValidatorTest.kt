package io.androllm.core.cloud.pipeline

import io.androllm.core.cloud.model.CloudChatMessage
import io.androllm.core.cloud.model.CloudChatRequest
import io.androllm.core.cloud.model.CloudTool
import io.androllm.core.cloud.model.CloudToolCall
import io.androllm.core.cloud.model.CloudToolCallFunction
import io.androllm.core.cloud.model.CloudToolFunction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Request validation: catches provider-400-class failures before send. */
class CloudRequestValidatorTest {

    private fun request(
        model: String = "openai/gpt-4o",
        messages: List<CloudChatMessage> = listOf(CloudChatMessage("user", "hi")),
        tools: List<CloudTool> = emptyList(),
        maxTokens: Int? = null,
        temperature: Double = 0.8
    ) = CloudChatRequest(
        model = model,
        messages = messages,
        tools = tools,
        max_tokens = maxTokens,
        temperature = temperature
    )

    @Test
    fun `valid request passes`() {
        val result = CloudRequestValidator.validate(request())
        assertTrue(result.valid)
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun `blank model is rejected`() {
        val result = CloudRequestValidator.validate(request(model = ""))
        assertFalse(result.valid)
        assertTrue(result.errors.any { "Model" in it })
    }

    @Test
    fun `empty message list is rejected`() {
        val result = CloudRequestValidator.validate(request(messages = emptyList()))
        assertFalse(result.valid)
        assertTrue(result.errors.any { "no messages" in it })
    }

    @Test
    fun `unknown role is rejected`() {
        val result = CloudRequestValidator.validate(
            request(messages = listOf(CloudChatMessage("alien", "hi")))
        )
        assertFalse(result.valid)
        assertTrue(result.errors.any { "unknown role" in it })
    }

    @Test
    fun `tool message without tool_call_id is rejected`() {
        val result = CloudRequestValidator.validate(
            request(
                messages = listOf(
                    CloudChatMessage("user", "hi"),
                    CloudChatMessage("tool", content = "result")
                )
            )
        )
        assertFalse(result.valid)
        assertTrue(result.errors.any { "tool_call_id" in it })
    }

    @Test
    fun `assistant tool call without name is rejected`() {
        val result = CloudRequestValidator.validate(
            request(
                messages = listOf(
                    CloudChatMessage("user", "hi"),
                    CloudChatMessage(
                        "assistant",
                        toolCalls = listOf(CloudToolCall(index = 0, id = "c1", function = CloudToolCallFunction(null, "{}")))
                    )
                )
            )
        )
        assertFalse(result.valid)
        assertTrue(result.errors.any { "function name" in it })
    }

    @Test
    fun `duplicate tool names are rejected`() {
        val tool = CloudTool(function = CloudToolFunction(name = "get_weather"))
        val result = CloudRequestValidator.validate(request(tools = listOf(tool, tool)))
        assertFalse(result.valid)
        assertTrue(result.errors.any { "Duplicate" in it })
    }

    @Test
    fun `malformed tool names are rejected`() {
        val tool = CloudTool(function = CloudToolFunction(name = "bad name with spaces!"))
        val result = CloudRequestValidator.validate(request(tools = listOf(tool)))
        assertFalse(result.valid)
        assertTrue(result.errors.any { "not valid" in it })
    }

    @Test
    fun `oversized request is rejected`() {
        val huge = "x".repeat(CloudRequestValidator.MAX_REQUEST_CHARS + 1)
        val result = CloudRequestValidator.validate(
            request(messages = listOf(CloudChatMessage("user", huge)))
        )
        assertFalse(result.valid)
        assertTrue(result.errors.any { "too large" in it })
    }

    @Test
    fun `large request warns but passes`() {
        val large = "x".repeat(CloudRequestValidator.WARN_REQUEST_CHARS + 1)
        val result = CloudRequestValidator.validate(
            request(messages = listOf(CloudChatMessage("user", large)))
        )
        assertTrue(result.valid)
        assertTrue(result.warnings.any { "Large request" in it })
    }

    @Test
    fun `assistant-final message warns but passes`() {
        val result = CloudRequestValidator.validate(
            request(messages = listOf(CloudChatMessage("assistant", "thinking...")))
        )
        assertTrue(result.valid)
        assertTrue(result.warnings.any { "Last message role" in it })
    }

    @Test
    fun `non-positive max_tokens is rejected`() {
        val result = CloudRequestValidator.validate(request(maxTokens = 0))
        assertFalse(result.valid)
    }

    @Test
    fun `extreme temperature warns but passes`() {
        val result = CloudRequestValidator.validate(request(temperature = 3.5))
        assertTrue(result.valid)
        assertTrue(result.warnings.any { "Temperature" in it })
    }

    @Test
    fun `well formed tool schema passes`() {
        val result = CloudRequestValidator.validate(
            request(
                tools = listOf(
                    CloudTool(function = CloudToolFunction(name = "get_weather", description = "weather")),
                    CloudTool(function = CloudToolFunction(name = "send_sms", description = "sms"))
                )
            )
        )
        assertTrue(result.valid)
        assertEquals(0, result.errors.size)
    }
}
