package io.androllm.core.telemetry

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.androllm.core.utils.DeviceInfoCollector
import io.androllm.core.utils.StorageUtils
import io.androllm.engine.api.EngineRepository
import io.androllm.engine.api.EngineState
import io.androllm.engine.api.GenerationState
import io.androllm.engine.models.EngineStats
import io.androllm.engine.models.MemoryStats
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Session-scoped telemetry hub backing every dashboard in the app (Home,
 * Developer Mode, Chat stats). All data is real runtime state:
 *
 * - Device RAM / CPU / Vulkan support from [DeviceInfoCollector].
 * - Model storage usage from [StorageUtils].
 * - Engine lifecycle, memory and performance from [EngineRepository]
 *   (native llama.cpp telemetry).
 *
 * The [history] ring buffer keeps a rolling window of 1-second samples during
 * the session, so Developer Mode graphs are backed by genuine runtime data.
 */
@Singleton
class TelemetryRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val engineRepository: EngineRepository
) {

    companion object {
        /** Rolling window length (1 sample/second -> ~5 minutes of history). */
        const val MAX_HISTORY_SAMPLES = 300

        /** How many completed generations are kept for the latency chart. */
        const val MAX_GENERATION_STATS = 60    /** Sampling interval in milliseconds. */
    const val SAMPLE_INTERVAL_MS = 1_000L

    /** How often the (expensive) storage directory walk runs, in ticks. */
    const val STORAGE_REFRESH_EVERY_TICKS = 15
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _deviceMetrics = MutableStateFlow<DeviceMetrics?>(null)
    val deviceMetrics: StateFlow<DeviceMetrics?> = _deviceMetrics.asStateFlow()

    private val _history = MutableStateFlow<List<TelemetrySample>>(emptyList())
    val history: StateFlow<List<TelemetrySample>> = _history.asStateFlow()

    private val _generationHistory = MutableStateFlow<List<GenerationStat>>(emptyList())
    val generationHistory: StateFlow<List<GenerationStat>> = _generationHistory.asStateFlow()

    private val _currentModelName = MutableStateFlow("")
    val currentModelName: StateFlow<String> = _currentModelName.asStateFlow()

    private val _isSampling = MutableStateFlow(false)
    val isSampling: StateFlow<Boolean> = _isSampling.asStateFlow()

    private var samplerJob: Job? = null
    private var clientCount = 0
    private var tickCount = 0
    private var lastStorage: io.androllm.core.utils.StorageStats? = null

    init {
        // Track the loaded model name for generation history entries.
        scope.launch {
            engineRepository.engineState.collect { state ->
                when (state) {
                    is EngineState.Ready ->
                        _currentModelName.value = state.model.generalName.ifBlank { state.model.id }
                    EngineState.Unloaded ->
                        _currentModelName.value = ""
                    else -> Unit
                }
            }
        }

        // Record every completed generation for the Developer Mode latency chart.
        scope.launch {
            engineRepository.performanceStats.collect { stats ->
                stats?.let { recordGeneration(it) }
            }
        }

        refreshDeviceMetrics()
    }

    private fun recordGeneration(stats: EngineStats) {
        if (stats.promptTokens <= 0 && stats.generatedTokens <= 0) return
        _generationHistory.update { history ->
            appendToHistory(
                history = history,
                sample = GenerationStat(
                    timestampMs = System.currentTimeMillis(),
                    promptTokens = stats.promptTokens,
                    generatedTokens = stats.generatedTokens,
                    tokensPerSecond = stats.tokensPerSecond,
                    totalTimeMs = stats.totalTimeMs,
                    firstTokenMs = stats.firstTokenMs,
                    stopReason = stats.stopReason,
                    modelName = _currentModelName.value
                ),
                max = MAX_GENERATION_STATS
            )
        }
    }

    /**
     * Starts the 1-second sampler. Reference counted: call [stopSampling] once
     * per [startSampling] call. Safe to call from multiple screens.
     */
    @Synchronized
    fun startSampling() {
        clientCount++
        if (samplerJob != null) return
        _isSampling.value = true
        samplerJob = scope.launch {
            while (isActive) {
                sample()
                delay(SAMPLE_INTERVAL_MS)
            }
        }
    }

    /**
     * Stops the sampler when the last client releases it.
     */
    @Synchronized
    fun stopSampling() {
        clientCount = (clientCount - 1).coerceAtLeast(0)
        if (clientCount == 0) {
            samplerJob?.cancel()
            samplerJob = null
            _isSampling.value = false
        }
    }

    /**
     * Re-reads device metrics immediately (full refresh, including storage).
     * The 1Hz sampler calls this with storage throttled to every 15s — walking
     * the models directory on every tick would be wasteful on battery.
     */
    fun refreshDeviceMetrics(refreshStorage: Boolean = true) {
        val info = DeviceInfoCollector.collectDeviceInfo(context)
        if (refreshStorage) {
            lastStorage = StorageUtils.getStorageStats(context)
        }
        val storage = lastStorage
        _deviceMetrics.value = DeviceMetrics(
            totalRamMb = info.totalRamBytes / (1024L * 1024L),
            availableRamMb = info.availableRamBytes / (1024L * 1024L),
            cpuCores = info.cpuCores,
            deviceModel = "${info.manufacturer} ${info.deviceName}".trim(),
            androidVersion = info.androidVersion,
            isVulkanSupported = info.isVulkanSupported,
            totalStorageBytes = storage?.totalBytes ?: 0L,
            usedStorageBytes = storage?.usedBytes ?: 0L,
            freeStorageBytes = storage?.availableBytes ?: 0L
        )
    }

    private fun sample() {
        // RAM is cheap to read every tick; the storage directory walk is
        // throttled so large model folders don't get walked 60x/minute.
        tickCount++
        refreshDeviceMetrics(refreshStorage = tickCount % STORAGE_REFRESH_EVERY_TICKS == 0)

        val device = _deviceMetrics.value
        val engineState = engineRepository.engineState.value
        val memory = engineRepository.memoryStats.value
        val stats = engineRepository.performanceStats.value
        val generating = engineRepository.generationState.value is GenerationState.Generating

        val sample = TelemetrySample(
            timestampMs = System.currentTimeMillis(),
            tokensPerSecond = stats?.tokensPerSecond ?: 0f,
            ramUsedMb = device?.usedRamMb?.toFloat() ?: 0f,
            ramTotalMb = device?.totalRamMb?.toFloat() ?: 0f,
            gpuMemoryMb = memory?.gpuMemoryMb() ?: 0f,
            kvCacheMb = memory?.contextSizeMb() ?: 0f,
            promptTokens = stats?.promptTokens ?: 0L,
            generatedTokens = stats?.generatedTokens ?: 0L,
            isGenerating = generating
        )

        _history.update { appendToHistory(it, sample, MAX_HISTORY_SAMPLES) }
    }

    /**
     * True when a model is currently loaded into the engine.
     */
    fun isModelLoaded(): Boolean = engineRepository.engineState.value is EngineState.Ready

    /**
     * Context length of the currently loaded model, or 0 when nothing is loaded.
     */
    fun currentContextLength(): Int =
        (engineRepository.engineState.value as? EngineState.Ready)?.model?.contextLength ?: 0
}
