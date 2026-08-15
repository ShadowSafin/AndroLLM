package io.androllm.engine

import io.androllm.engine.models.GenerationConfig
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract test for the Kotlin → native generation-config handoff.
 *
 * The native mini-JSON parser in native_api.cpp reads a fixed set of camelCase
 * keys (temperature, maxTokens, reuseKvCache, debugTokenLogging, ...). This
 * test pins the serialized shape so a rename here silently breaks the native
 * parser instead of quietly defaulting.
 */
class GenerationConfigSerializationTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `debugTokenLogging round trips`() {
        val config = GenerationConfig(temperature = 0.4f, maxTokens = 64, debugTokenLogging = true)
        val encoded = json.encodeToString(GenerationConfig.serializer(), config)

        assertTrue("field must be serialized: $encoded", encoded.contains("\"debugTokenLogging\":true"))

        val decoded = json.decodeFromString(GenerationConfig.serializer(), encoded)
        assertEquals(0.4f, decoded.temperature, 0.001f)
        assertEquals(64, decoded.maxTokens)
        assertTrue(decoded.debugTokenLogging)
    }

    @Test
    fun `old config without the field still decodes`() {
        val legacy = """{"maxTokens":256,"temperature":0.7,"reuseKvCache":true}"""
        val decoded = json.decodeFromString(GenerationConfig.serializer(), legacy)
        assertEquals(256, decoded.maxTokens)
        assertEquals(0.7f, decoded.temperature, 0.001f)
        assertFalse("new field must default to false", decoded.debugTokenLogging)
    }

    @Test
    fun `default config leaves the field absent and decodes as false`() {
        // kotlinx.serialization omits properties equal to their default, which
        // is exactly the native parser's contract: a missing key defaults to
        // false on the native side too.
        val encoded = json.encodeToString(GenerationConfig.serializer(), GenerationConfig())
        assertFalse("default must not be serialized as true", encoded.contains("\"debugTokenLogging\":true"))
        val decoded = json.decodeFromString(GenerationConfig.serializer(), encoded)
        assertFalse(decoded.debugTokenLogging)
    }

    @Test
    fun `enableThinking round trips to the native parser key`() {
        // The native Jinja renderer threads `enable_thinking` through the
        // template variable of the same name (Qwen2.5/Qwen3 thinking blocks).
        // The serialized key must survive the Kotlin → JSON handoff.
        val encoded = json.encodeToString(
            GenerationConfig.serializer(),
            GenerationConfig(enableThinking = true)
        )
        assertTrue("enableThinking must be serialized: $encoded", encoded.contains("\"enableThinking\":true"))

        val decoded = json.decodeFromString(GenerationConfig.serializer(), encoded)
        assertTrue(decoded.enableThinking)
    }

    @Test
    fun `legacy config without enableThinking defaults to false`() {
        val legacy = """{"maxTokens":128}"""
        val decoded = json.decodeFromString(GenerationConfig.serializer(), legacy)
        assertFalse("enableThinking must default to false (safe for all models)", decoded.enableThinking)
    }
}
