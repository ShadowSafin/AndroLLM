package io.androllm.engine

import io.androllm.engine.models.ModelLoadConfig
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract test for the Kotlin → native model-load-config handoff.
 *
 * The native mini-JSON parser reads a fixed set of camelCase keys; the
 * critical one here is `runBackendValidation` — the opt-in gate for the
 * load-time CPU-vs-GPU correctness probe that otherwise loads a SECOND full
 * copy of the model (OOM risk, multi-minute loads). It must default to OFF so
 * a normal model load never pays that cost, and it must round-trip when the
 * developer explicitly enables it.
 */
class ModelLoadConfigSerializationTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `runBackendValidation defaults to off`() {
        val config = ModelLoadConfig()
        assertFalse(
            "load-time backend validation is diagnostic-only and doubles peak RAM — must default OFF",
            config.runBackendValidation
        )
        val encoded = json.encodeToString(ModelLoadConfig.serializer(), config)
        assertFalse("default must not be serialized as true: $encoded", encoded.contains("\"runBackendValidation\":true"))
    }

    @Test
    fun `runBackendValidation round trips when explicitly enabled`() {
        val config = ModelLoadConfig(runBackendValidation = true, contextLength = 8192, gpuLayers = 40)
        val encoded = json.encodeToString(ModelLoadConfig.serializer(), config)
        assertTrue("explicit opt-in must serialize: $encoded", encoded.contains("\"runBackendValidation\":true"))
        assertTrue(encoded.contains("\"contextLength\":8192"))

        val decoded = json.decodeFromString(ModelLoadConfig.serializer(), encoded)
        assertTrue(decoded.runBackendValidation)
        assertEquals(8192, decoded.contextLength)
        assertEquals(40, decoded.gpuLayers)
    }

    @Test
    fun `legacy config without the field decodes with validation off`() {
        val legacy = """{"contextLength":4096,"gpuLayers":-1,"batchSize":2048}"""
        val decoded = json.decodeFromString(ModelLoadConfig.serializer(), legacy)
        assertFalse(decoded.runBackendValidation)
        assertEquals(4096, decoded.contextLength)
    }

    @Test
    fun `runSelfTest defaults to on`() {
        // A model is not advertised as Ready until the coherence probe passes.
        assertTrue(ModelLoadConfig().runSelfTest)
        val encoded = json.encodeToString(ModelLoadConfig.serializer(), ModelLoadConfig())
        assertFalse("default true must not be serialized as false", encoded.contains("\"runSelfTest\":false"))
    }

    @Test
    fun `runSelfTest round trips when disabled`() {
        val encoded = json.encodeToString(
            ModelLoadConfig.serializer(),
            ModelLoadConfig(runSelfTest = false)
        )
        assertTrue("explicit opt-out must serialize: $encoded", encoded.contains("\"runSelfTest\":false"))
        val decoded = json.decodeFromString(ModelLoadConfig.serializer(), encoded)
        assertFalse(decoded.runSelfTest)
    }
}
