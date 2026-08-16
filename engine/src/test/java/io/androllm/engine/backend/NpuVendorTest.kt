package io.androllm.engine.backend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for SoC → NPU vendor detection (Qualcomm / MediaTek / Google Tensor /
 * Samsung) from Android system-property values.
 */
class NpuVendorTest {

    @Test
    fun `Qualcomm detected from manufacturer`() {
        assertEquals(NpuVendor.QUALCOMM, NpuVendor.detect(socManufacturer = "Qualcomm"))
    }

    @Test
    fun `Qualcomm detected from Snapdragon model code`() {
        assertEquals(NpuVendor.QUALCOMM, NpuVendor.detect(socModel = "SM8550"))
        assertEquals(NpuVendor.QUALCOMM, NpuVendor.detect(socModel = "sm8750"))
    }

    @Test
    fun `Qualcomm detected from board platform`() {
        assertEquals(NpuVendor.QUALCOMM, NpuVendor.detect(boardPlatform = "sm8550"))
        assertEquals(NpuVendor.QUALCOMM, NpuVendor.detect(hardware = "qcom"))
    }

    @Test
    fun `MediaTek detected from Dimensity model code`() {
        assertEquals(NpuVendor.MEDIATEK, NpuVendor.detect(socModel = "MT6989"))
        assertEquals(NpuVendor.MEDIATEK, NpuVendor.detect(socModel = "mt6991"))
    }

    @Test
    fun `MediaTek detected from manufacturer`() {
        assertEquals(NpuVendor.MEDIATEK, NpuVendor.detect(socManufacturer = "MediaTek"))
    }

    @Test
    fun `Google Tensor detected from Pixel model code`() {
        assertEquals(NpuVendor.GOOGLE_TENSOR, NpuVendor.detect(socModel = "gs201"))
        assertEquals(NpuVendor.GOOGLE_TENSOR, NpuVendor.detect(socModel = "tensor g3"))
    }

    @Test
    fun `Google Tensor detected from manufacturer`() {
        assertEquals(NpuVendor.GOOGLE_TENSOR, NpuVendor.detect(socManufacturer = "Google"))
    }

    @Test
    fun `Samsung detected from Exynos model`() {
        assertEquals(NpuVendor.SAMSUNG, NpuVendor.detect(socModel = "exynos2200"))
        assertEquals(NpuVendor.SAMSUNG, NpuVendor.detect(socManufacturer = "Samsung"))
    }

    @Test
    fun `unknown SoC maps to UNKNOWN`() {
        assertEquals(NpuVendor.UNKNOWN, NpuVendor.detect())
        assertEquals(NpuVendor.UNKNOWN, NpuVendor.detect(socModel = "generic"))
        assertEquals(NpuVendor.UNKNOWN, NpuVendor.detect(hardware = "ranchu"))
    }

    @Test
    fun `dispatch library name matches vendor`() {
        assertEquals("libLiteRtDispatch_qualcomm.so", NpuVendor.QUALCOMM.dispatchLibraryName)
        assertEquals("libLiteRtDispatch_mediatek.so", NpuVendor.MEDIATEK.dispatchLibraryName)
        assertEquals("libLiteRtDispatch_google_tensor.so", NpuVendor.GOOGLE_TENSOR.dispatchLibraryName)
    }

    @Test
    fun `google tensor uses the dedicated backend type`() {
        assertTrue(NpuVendor.isGoogleTensor(NpuVendor.GOOGLE_TENSOR))
        assertFalse(NpuVendor.isGoogleTensor(NpuVendor.QUALCOMM))
    }
}
