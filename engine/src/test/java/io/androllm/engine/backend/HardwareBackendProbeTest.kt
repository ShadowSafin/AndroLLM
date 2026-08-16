package io.androllm.engine.backend

import io.androllm.engine.models.BackendType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the pure probe logic ([HardwareBackendProbe.fromProperties]) —
 * SoC/NNAPI/dispatch-library detection without Android dependencies.
 */
class HardwareBackendProbeTest {

    @Test
    fun `Qualcomm SoC with NNAPI and dispatch library is fully usable`() {
        val caps = HardwareBackendProbe.fromProperties(
            props = mapOf("ro.soc.model" to "sm8550", "ro.hardware.egl" to "adreno (r790)"),
            nnApiAvailable = true,
            dispatchLibraries = listOf("libLiteRtDispatch_qualcomm.so", "liblitert_auth_token.so")
        )
        assertTrue(caps.npuAvailable)
        assertTrue(caps.npuUsable)
        assertTrue(caps.npuOptionVisible)
        assertEquals("Qualcomm", caps.npuVendor)
        assertEquals("Hexagon HTP", caps.npuAccelerator)
        assertEquals("Adreno", caps.gpuName)
        assertEquals("Qualcomm", caps.gpuVendor)
        assertEquals(BackendType.CPU, caps.selectedBackend)
    }

    @Test
    fun `NPU hardware without the dispatch library is not usable`() {
        val caps = HardwareBackendProbe.fromProperties(
            props = mapOf("ro.board.platform" to "mt6989"),
            nnApiAvailable = true,
            dispatchLibraries = emptyList()
        )
        assertTrue(caps.npuAvailable)
        assertFalse(caps.npuUsable)
        assertFalse(caps.npuOptionVisible)
        assertEquals("MediaTek", caps.npuVendor)
    }

    @Test
    fun `no NNAPI and no SoC means no NPU at all`() {
        val caps = HardwareBackendProbe.fromProperties(
            props = mapOf("ro.hardware" to "ranchu"),
            nnApiAvailable = false,
            dispatchLibraries = emptyList()
        )
        assertFalse(caps.npuAvailable)
        assertFalse(caps.npuUsable)
        assertNull(caps.npuVendor)
        assertTrue(caps.cpuAvailable)
        assertTrue(caps.gpuAvailable)
    }

    @Test
    fun `NPU unlocks via dispatch library even when NNAPI flag is missing`() {
        // REGRESSION (real device): many OEM builds (ColorOS/OneUI) omit the
        // neuralnetworks feature flag on NPU-equipped SoCs. The dispatch
        // library is the real gate — NNAPI must not block NPU forever.
        val caps = HardwareBackendProbe.fromProperties(
            props = mapOf("ro.soc.model" to "sm8845"),
            nnApiAvailable = false,
            dispatchLibraries = listOf("libLiteRtDispatch_qualcomm.so")
        )
        assertTrue(caps.npuAvailable)
        assertTrue(caps.npuUsable)
        assertTrue(caps.npuOptionVisible)
        assertFalse(caps.nnApiAvailable)
        assertEquals("Qualcomm", caps.npuVendor)
    }

    @Test
    fun `wrong vendor dispatch library does not unlock NPU`() {
        val caps = HardwareBackendProbe.fromProperties(
            props = mapOf("ro.soc.model" to "sm8550"),
            nnApiAvailable = true,
            dispatchLibraries = listOf("libLiteRtDispatch_mediatek.so")
        )
        assertFalse("a Qualcomm SoC must not unlock via a MediaTek dispatch lib", caps.npuUsable)
    }

    @Test
    fun `Google prebuilt dispatch lib with capital Q unlocks NPU`() {
        // REGRESSION: Google's official prebuilt is libLiteRtDispatch_Qualcomm.so
        // (capital Q); the vendor id is lowercase. The match must be
        // case-insensitive or the NPU option stays hidden even when the
        // vendor driver is bundled.
        val caps = HardwareBackendProbe.fromProperties(
            props = mapOf("ro.soc.model" to "sm8845"),
            nnApiAvailable = false,
            dispatchLibraries = listOf("libLiteRtDispatch_Qualcomm.so")
        )
        assertTrue(caps.npuAvailable)
        assertTrue(caps.npuUsable)
        assertTrue(caps.npuOptionVisible)
    }

    @Test
    fun `Mali GPU detected via egl string`() {
        val caps = HardwareBackendProbe.fromProperties(
            props = mapOf("ro.hardware.egl" to "Mali-G715")
        )
        assertEquals("Mali", caps.gpuName)
        assertEquals("ARM", caps.gpuVendor)
    }

    @Test
    fun `Xclipse GPU detected via egl string`() {
        val caps = HardwareBackendProbe.fromProperties(
            props = mapOf("ro.hardware.egl" to "Xclipse 920")
        )
        assertEquals("Xclipse", caps.gpuName)
        assertEquals("Samsung", caps.gpuVendor)
    }
}
