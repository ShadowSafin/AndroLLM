package io.androllm.engine

import io.androllm.engine.models.MemoryStats
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [MemoryStats] execution-backend semantics.
 *
 * The whole point of the backend-reporting fix: validation status is
 * diagnostic only and must NEVER influence the reported execution backend.
 * A "failed" self-test on a Vulkan-active engine must still report Vulkan /
 * Hybrid execution, while a genuinely CPU-fallback engine reports CPU.
 */
class MemoryStatsValidationTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val vulkanActiveJson = """
        {
          "modelSizeBytes": 209715200,
          "contextSizeBytes": 20971520,
          "gpuLayersOffloaded": 14,
          "totalLayers": 18,
          "backend": "vulkan",
          "backendReason": "Vulkan active (14/18 layers)",
          "gpuInferenceVerified": true,
          "vulkanValidationStatus": "failed",
          "vulkanValidationDetail": "greedy[hello]: step=2 cpu=198 gpu=49151 logits=0.0421"
        }
    """.trimIndent()

    @Test
    fun `validation failure does not change the reported execution backend`() {
        val stats = json.decodeFromString(MemoryStats.serializer(), vulkanActiveJson)

        // Runtime state wins: still Vulkan, still Hybrid execution.
        assertTrue(stats.isGpuAccelerated)
        assertTrue(stats.vulkanValidationFailed)
        assertFalse(stats.isCpuFallback)
        assertEquals("Hybrid", stats.executionMode)
    }

    @Test
    fun `validation passed keeps Vulkan and hybrid execution`() {
        val stats = json.decodeFromString(
            MemoryStats.serializer(),
            vulkanActiveJson.replace("\"failed\"", "\"passed\"").replace(
                "\"greedy[hello]: step=2 cpu=198 gpu=49151 logits=0.0421\"",
                "\"\""
            )
        )

        assertTrue(stats.isGpuAccelerated)
        assertTrue(stats.vulkanValidationPassed)
        assertFalse(stats.vulkanValidationFailed)
        assertFalse(stats.isCpuFallback)
        assertEquals("Hybrid", stats.executionMode)
    }

    @Test
    fun `genuine CPU fallback is detected from runtime reason only`() {
        val stats = json.decodeFromString(
            MemoryStats.serializer(),
            """
            {
              "gpuLayersOffloaded": 0,
              "totalLayers": 18,
              "backend": "cpu",
              "backendReason": "Vulkan unavailable: no Vulkan devices",
              "vulkanValidationStatus": "skipped"
            }
            """.trimIndent()
        )

        assertFalse(stats.isGpuAccelerated)
        assertTrue(stats.isCpuFallback)
        assertEquals("CPU only", stats.executionMode)
        assertFalse(stats.vulkanValidationFailed)
    }

    @Test
    fun `deliberate CPU mode is not flagged as a fallback`() {
        val stats = json.decodeFromString(
            MemoryStats.serializer(),
            """
            {
              "gpuLayersOffloaded": 0,
              "totalLayers": 18,
              "backend": "cpu",
              "backendReason": "CPU backend (no GPU offload)",
              "vulkanValidationStatus": "skipped"
            }
            """.trimIndent()
        )

        assertFalse(stats.isGpuAccelerated)
        assertFalse(stats.isCpuFallback)
        assertEquals("CPU only", stats.executionMode)
    }

    @Test
    fun `defaults are safe when fields are absent`() {
        val stats = json.decodeFromString(
            MemoryStats.serializer(),
            """{"backend": "vulkan", "gpuLayersOffloaded": 10, "totalLayers": 20}"""
        )

        assertTrue(stats.isGpuAccelerated)
        assertEquals("skipped", stats.vulkanValidationStatus)
        assertEquals("", stats.vulkanValidationDetail)
        assertEquals("Hybrid", stats.executionMode)
        assertFalse(stats.isCpuFallback)
    }
}
