package io.androllm.core.telemetry

import io.androllm.core.utils.DeviceHardwareInfo
import io.androllm.core.utils.DeviceInfoCollector
import io.androllm.core.utils.StorageStats
import io.androllm.core.utils.StorageUtils
import io.androllm.engine.api.EngineRepository
import io.androllm.engine.api.EngineState
import io.androllm.engine.api.GenerationState
import io.androllm.engine.models.EngineStats
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The sync trigger hook: every completed generation must fire
 * [TelemetryRepository.onGenerationRecorded] without ever breaking recording.
 */
class TelemetryListenerTest {

    @Before
    fun setUp() {
        mockkObject(DeviceInfoCollector, StorageUtils)
        every { DeviceInfoCollector.collectDeviceInfo(any()) } returns DeviceHardwareInfo(
            deviceName = "Test",
            manufacturer = "Test",
            androidVersion = "14",
            apiLevel = 34,
            abi = "arm64-v8a",
            cpuCores = 8,
            totalRamBytes = 8L * 1024 * 1024 * 1024,
            availableRamBytes = 4L * 1024 * 1024 * 1024,
            totalRamGb = 8f,
            freeStorageBytes = 0L,
            isVulkanSupported = false,
        )
        every { StorageUtils.getStorageStats(any()) } returns StorageStats(
            totalBytes = 100L,
            usedBytes = 40L,
            freeBytes = 60L,
        )
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun repository(performanceStats: MutableStateFlow<EngineStats?>): TelemetryRepository {
        val engine = mockk<EngineRepository> {
            every { engineState } returns MutableStateFlow(EngineState.Unloaded)
            every { generationState } returns MutableStateFlow(GenerationState.Idle)
            every { memoryStats } returns MutableStateFlow(null)
            every { this@mockk.performanceStats } returns performanceStats
        }
        return TelemetryRepository(mockk(), engine)
    }

    @Test
    fun `completed generation fires onGenerationRecorded`() {
        val performanceStats = MutableStateFlow<EngineStats?>(null)
        val repo = repository(performanceStats)
        val latch = CountDownLatch(1)
        repo.onGenerationRecorded = { latch.countDown() }

        performanceStats.value = EngineStats(promptTokens = 10, generatedTokens = 20)

        assertTrue(latch.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun `empty stats never fire the listener`() {
        val performanceStats = MutableStateFlow<EngineStats?>(null)
        val repo = repository(performanceStats)
        var calls = 0
        repo.onGenerationRecorded = { calls++ }

        performanceStats.value = EngineStats(promptTokens = 0, generatedTokens = 0)
        Thread.sleep(300)

        // Zero-token stats are filtered before recording (and before the callback).
        assertTrue(calls == 0)
    }
}
