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
        assertEquals(0, stats.recoveryCount)
        assertEquals("", stats.lastRecoveryReason)
        assertFalse(stats.cpuSessionFallback)
    }

    @Test
    fun `runtime recovery fields are parsed and exposed`() {
        val stats = json.decodeFromString(
            MemoryStats.serializer(),
            """
            {
              "gpuLayersOffloaded": 14,
              "totalLayers": 18,
              "backend": "vulkan",
              "backendReason": "Vulkan active (14/18 layers)",
              "gpuInferenceVerified": true,
              "recoveryCount": 2,
              "lastRecoveryReason": "prefill logits corrupted (NaN/INF) at idx 4123 (NaN)",
              "cpuSessionFallback": false
            }
            """.trimIndent()
        )

        // Still GPU/Hybrid (recovery happened but the backend stayed Vulkan).
        assertTrue(stats.isGpuAccelerated)
        assertFalse(stats.isCpuSessionFallback)
        assertEquals(2, stats.recoveryCount)
        assertTrue(stats.lastRecoveryReason.contains("NaN/INF"))
    }

    @Test
    fun `cpu session fallback is reported separately from validation`() {
        val stats = json.decodeFromString(
            MemoryStats.serializer(),
            """
            {
              "gpuLayersOffloaded": 0,
              "totalLayers": 18,
              "backend": "cpu",
              "backendReason": "CPU fallback after GPU runtime corruption (init failed during recovery)",
              "vulkanValidationStatus": "skipped",
              "recoveryCount": 3,
              "lastRecoveryReason": "logits corrupted (NaN/INF) at step 5",
              "cpuSessionFallback": true
            }
            """.trimIndent()
        )

        assertFalse(stats.isGpuAccelerated)
        assertTrue(stats.isCpuFallback)
        assertTrue(stats.isCpuSessionFallback)
        assertEquals("CPU only", stats.executionMode)
        assertEquals(3, stats.recoveryCount)
    }

    @Test
    fun `liteRT gpu delegate is reported as gpu accelerated`() {
        val stats = json.decodeFromString(
            MemoryStats.serializer(),
            """
            {
              "backend": "gpu",
              "backendReason": "LiteRT GPU delegate active",
              "gpuName": "LiteRT GPU",
              "gpuInferenceVerified": true
            }
            """.trimIndent()
        )

        // LiteRT has no per-layer offload; the "gpu" backend alone is the signal.
        assertTrue(stats.isGpuAccelerated)
        assertEquals("LiteRT GPU", stats.gpuBackendLabel)
        assertEquals("GPU only", stats.executionMode)
        assertEquals("All ops (delegate)", stats.gpuLayersDisplay)
        assertFalse(stats.isCpuFallback)
    }

    @Test
    fun `cpu backend without reason is not a gpu fallback`() {
        val stats = json.decodeFromString(
            MemoryStats.serializer(),
            """
            {
              "backend": "cpu",
              "backendReason": "CPU (XNNPACK)"
            }
            """.trimIndent()
        )

        assertFalse(stats.isGpuAccelerated)
        assertEquals("", stats.gpuBackendLabel)
        assertEquals("CPU only", stats.executionMode)
        assertFalse(stats.isCpuFallback)
    }
}
