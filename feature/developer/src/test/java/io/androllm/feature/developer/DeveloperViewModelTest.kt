package io.androllm.feature.developer

import io.androllm.core.common.UiState
import io.androllm.core.memory.MemoryManager
import io.androllm.core.memory.model.MemoryInspectorStats
import io.androllm.core.telemetry.TelemetryRepository
import io.androllm.core.telemetry.TelemetrySample
import io.androllm.engine.api.EngineRepository
import io.androllm.engine.api.EngineState
import io.androllm.engine.models.BackendType
import io.androllm.engine.models.EngineCapabilities
import io.androllm.engine.models.EngineStats
import io.androllm.engine.models.MemoryStats
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for the developer ViewModel aggregation over real telemetry flows.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeveloperViewModelTest {

    private val telemetryRepository: TelemetryRepository = mockk(relaxed = true)
    private val engineRepository: EngineRepository = mockk(relaxed = true)
    private val memoryManager: MemoryManager = mockk(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())

        coEvery { memoryManager.getInspectorStats() } returns MemoryInspectorStats()
        coEvery { memoryManager.getMemories() } returns emptyList()

        every { telemetryRepository.deviceMetrics } returns MutableStateFlow(null)
        every { telemetryRepository.history } returns MutableStateFlow(
            listOf(
                sample(1L, 20f, 4096f),
                sample(2L, 30f, 5120f),
                sample(3L, 25f, 4608f)
            )
        )
        every { telemetryRepository.generationHistory } returns MutableStateFlow(emptyList())
        every { telemetryRepository.currentModelName } returns MutableStateFlow("Test Model")
        every { telemetryRepository.isSampling } returns MutableStateFlow(true)

        every { engineRepository.engineState } returns MutableStateFlow(EngineState.Unloaded)
        every { engineRepository.memoryStats } returns MutableStateFlow(null)
        every { engineRepository.performanceStats } returns MutableStateFlow(
            EngineStats(
                promptTokens = 120L,
                generatedTokens = 300L,
                tokensPerSecond = 24f,
                totalTimeMs = 15000L,
                firstTokenMs = 800L,
                stopReason = "eos"
            )
        )
        every { engineRepository.capabilities } returns EngineCapabilities(
            name = "Test",
            version = "1.0",
            backend = BackendType.VULKAN
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `aggregates history into chart series`() = runTest {
        val viewModel = DeveloperViewModel(telemetryRepository, engineRepository, memoryManager)
        val state = viewModel.uiState.value
        assertTrue(state is UiState.Success)
        val data = (state as UiState.Success).data
        assertEquals(3, data.speedHistory.size)
        assertEquals(30f, data.peakTokensPerSecond)
        assertEquals(25f, data.avgTokensPerSecond, 0.01f)
    }

    @Test
    fun `computes context usage from real stats`() = runTest {
        every { engineRepository.engineState } returns MutableStateFlow(
            EngineState.Ready(
                model = io.androllm.engine.models.EngineModelInfo(
                    id = "test",
                    filePath = "/tmp/model.gguf",
                    contextLength = 2048,
                    vocabSize = 32000,
                    backend = BackendType.VULKAN
                )
            )
        )
        val viewModel = DeveloperViewModel(telemetryRepository, engineRepository, memoryManager)
        val data = (viewModel.uiState.value as UiState.Success).data
        assertEquals(420L, data.contextTokensUsed)
        assertEquals(0.205f, data.contextUsageFraction, 0.001f)
        assertEquals(true, data.isModelLoaded)
    }

    @Test
    fun `reflects engine state`() = runTest {
        val viewModel = DeveloperViewModel(telemetryRepository, engineRepository, memoryManager)
        val data = (viewModel.uiState.value as UiState.Success).data
        assertEquals(false, data.isModelLoaded)
        assertEquals("VULKAN", data.backendLabel)
    }

    private fun sample(timestampMs: Long, tps: Float, ramMb: Float): TelemetrySample =
        TelemetrySample(
            timestampMs = timestampMs,
            tokensPerSecond = tps,
            ramUsedMb = ramMb,
            ramTotalMb = 8192f,
            gpuMemoryMb = 512f,
            kvCacheMb = 64f,
            promptTokens = 0L,
            generatedTokens = 0L,
            isGenerating = false
        )
}


