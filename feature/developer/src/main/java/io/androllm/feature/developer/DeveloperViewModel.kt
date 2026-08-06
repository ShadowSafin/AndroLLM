package io.androllm.feature.developer

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.androllm.core.common.BaseViewModel
import io.androllm.core.common.UiState
import io.androllm.core.common.getOrNull
import io.androllm.core.telemetry.DeviceMetrics
import io.androllm.core.telemetry.GenerationStat
import io.androllm.core.memory.MemoryManager
import io.androllm.core.memory.model.Memory
import io.androllm.core.memory.model.MemoryInspectorStats
import io.androllm.core.telemetry.TelemetryRepository
import io.androllm.core.telemetry.TelemetrySample
import io.androllm.engine.api.EngineRepository
import io.androllm.engine.api.EngineState
import io.androllm.engine.models.EngineDebugInfo
import io.androllm.engine.models.EngineStats
import io.androllm.engine.models.MemoryStats
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Developer Mode ViewModel â€” every graph is backed by real session telemetry
 * from [TelemetryRepository] and the native engine. No fabricated data.
 */
@HiltViewModel
class DeveloperViewModel @Inject constructor(
    private val telemetryRepository: TelemetryRepository,
    private val engineRepository: EngineRepository,
    private val memoryManager: MemoryManager
) : BaseViewModel() {

    private val _debugInfo = MutableStateFlow<EngineDebugInfo?>(null)
    val debugInfo: StateFlow<EngineDebugInfo?> = _debugInfo.asStateFlow()

    val uiState: StateFlow<UiState<DeveloperData>> = combine(
        telemetryRepository.history,
        telemetryRepository.generationHistory,
        telemetryRepository.deviceMetrics,
        telemetryRepository.currentModelName,
        engineRepository.engineState,
        engineRepository.memoryStats,
        engineRepository.performanceStats
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val history = values[0] as List<TelemetrySample>
        @Suppress("UNCHECKED_CAST")
        val generations = values[1] as List<GenerationStat>
        @Suppress("UNCHECKED_CAST")
        val device = values[2] as DeviceMetrics?
        @Suppress("UNCHECKED_CAST")
        val modelName = values[3] as String
        @Suppress("UNCHECKED_CAST")
        val engineState = values[4] as EngineState
        @Suppress("UNCHECKED_CAST")
        val memory = values[5] as MemoryStats?
        @Suppress("UNCHECKED_CAST")
        val stats = values[6] as EngineStats?

        UiState.Success(
            DeveloperData(
                history = history,
                generations = generations,
                deviceMetrics = device,
                modelName = modelName,
                engineState = engineState,
                memoryStats = memory,
                lastStats = stats,
                contextLength = (engineState as? EngineState.Ready)?.model?.contextLength ?: 0,
                backendLabel = memory?.backend?.uppercase()
                    ?: engineRepository.capabilities.backend.name
            )
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = UiState.Loading()
    )

    private val _memoryStats = MutableStateFlow<MemoryInspectorStats?>(null)
    val memoryStats: StateFlow<MemoryInspectorStats?> = _memoryStats.asStateFlow()

    private val _recentMemories = MutableStateFlow<List<Memory>>(emptyList())
    val recentMemories: StateFlow<List<Memory>> = _recentMemories.asStateFlow()

    init {
        telemetryRepository.startSampling()
        refreshMemoryInspector()
    }

    override fun onCleared() {
        telemetryRepository.stopSampling()
        super.onCleared()
    }

    fun refresh() {
        telemetryRepository.refreshDeviceMetrics()
        refreshMemoryInspector()
    }

    /**
     * Refreshes the Memory Inspector snapshot (counts, timings, logs) and the
     * most recently updated memories for the context preview.
     */
    fun refreshMemoryInspector() {
        viewModelScope.launch {
            _memoryStats.value = memoryManager.getInspectorStats()
            _recentMemories.value = memoryManager.getMemories().take(6)
        }
    }

    fun refreshDebugInfo() {
        viewModelScope.launch {
            _debugInfo.value = engineRepository.getDebugInfo().getOrNull()
        }
    }
}

/**
 * Snapshot of everything the developer dashboard renders.
 */
data class DeveloperData(
    val history: List<TelemetrySample> = emptyList(),
    val generations: List<GenerationStat> = emptyList(),
    val deviceMetrics: DeviceMetrics? = null,
    val modelName: String = "",
    val engineState: EngineState = EngineState.Unloaded,
    val memoryStats: MemoryStats? = null,
    val lastStats: EngineStats? = null,
    val contextLength: Int = 0,
    val backendLabel: String = "cpu"
) {
    val speedHistory: List<Float> get() = history.map { it.tokensPerSecond }
    val ramHistory: List<Float> get() = history.map { it.ramUsedMb }
    val gpuHistory: List<Float> get() = history.map { it.gpuMemoryMb }
    val kvCacheHistory: List<Float> get() = history.map { it.kvCacheMb }

    val peakTokensPerSecond: Float get() = speedHistory.maxOrNull() ?: 0f
    val avgTokensPerSecond: Float
        get() {
            val positive = speedHistory.filter { it > 0f }
            return if (positive.isEmpty()) 0f else positive.average().toFloat()
        }

    val lastTokensPerSecond: Float get() = history.lastOrNull()?.tokensPerSecond ?: 0f

    /** Total tokens in the current/last generation (context usage denominator). */
    val contextTokensUsed: Long
        get() = (lastStats?.promptTokens ?: 0L) + (lastStats?.generatedTokens ?: 0L)

    val contextUsageFraction: Float
        get() = if (contextLength > 0) contextTokensUsed.toFloat() / contextLength else 0f

    val isModelLoaded: Boolean get() = engineState is EngineState.Ready

    val generationLatencies: List<Float> get() = generations.map { it.totalTimeMs.toFloat() }
    val generationSpeeds: List<Float> get() = generations.map { it.tokensPerSecond }
}

