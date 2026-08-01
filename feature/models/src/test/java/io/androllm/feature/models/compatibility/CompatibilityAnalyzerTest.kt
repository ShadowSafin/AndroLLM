package io.androllm.feature.models.compatibility

import io.androllm.core.models.Model
import io.androllm.core.utils.DeviceHardwareInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompatibilityAnalyzerTest {

    @Test
    fun `analyze rates EXCELLENT when device RAM exceeds recommended RAM`() {
        val model = Model(
            id = "gemma-3-1b",
            name = "Gemma 3 1B",
            minRamGb = 4.0f,
            recommendedRamGb = 6.0f
        )
        val hardware = DeviceHardwareInfo(
            deviceName = "Pixel 8 Pro",
            manufacturer = "Google",
            androidVersion = "14",
            apiLevel = 34,
            abi = "arm64-v8a",
            cpuCores = 8,
            totalRamBytes = 12L * 1024 * 1024 * 1024,
            availableRamBytes = 6L * 1024 * 1024 * 1024,
            totalRamGb = 12.0f,
            freeStorageBytes = 50L * 1024 * 1024 * 1024,
            isVulkanSupported = true
        )

        val analysis = CompatibilityAnalyzer.analyze(model, hardware)
        assertEquals(CompatibilityRating.EXCELLENT, analysis.rating)
        assertTrue(analysis.isRamSufficient)
    }

    @Test
    fun `analyze rates INSUFFICIENT_RAM when total RAM is below minimum`() {
        val model = Model(
            id = "gemma-3-4b",
            name = "Gemma 3 4B",
            minRamGb = 6.0f,
            recommendedRamGb = 8.0f
        )
        val hardware = DeviceHardwareInfo(
            deviceName = "Budget Phone",
            manufacturer = "Generic",
            androidVersion = "11",
            apiLevel = 30,
            abi = "arm64-v8a",
            cpuCores = 4,
            totalRamBytes = 4L * 1024 * 1024 * 1024,
            availableRamBytes = 1L * 1024 * 1024 * 1024,
            totalRamGb = 4.0f,
            freeStorageBytes = 10L * 1024 * 1024 * 1024,
            isVulkanSupported = false
        )

        val analysis = CompatibilityAnalyzer.analyze(model, hardware)
        assertEquals(CompatibilityRating.INSUFFICIENT_RAM, analysis.rating)
        assertFalse(analysis.isRamSufficient)
    }
}
