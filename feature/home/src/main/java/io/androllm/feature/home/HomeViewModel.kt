package io.androllm.feature.home

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.androllm.core.common.BaseViewModel
import io.androllm.core.common.UiState
import io.androllm.core.database.repository.ConversationRepository
import io.androllm.core.database.repository.SettingsRepository
import io.androllm.core.models.Conversation
import io.androllm.core.telemetry.DeviceMetrics
import io.androllm.core.telemetry.GenerationStat
import io.androllm.core.telemetry.TelemetryRepository
import io.androllm.core.telemetry.TelemetrySample
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * ViewModel for the home dashboard.
 *
 * Loads recent conversations, the current model state, and — most importantly —
 * streams *real* runtime telemetry from [TelemetryRepository] (device RAM,
 * native engine tokens/sec, GPU/KV cache memory, storage) so the dashboard
 * never displays fabricated metrics.
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val conversationRepository: ConversationRepository,
    private val settingsRepository: SettingsRepository,
    private val telemetryRepository: TelemetryRepository
) : BaseViewModel() {

    private val _uiState = MutableStateFlow<UiState<HomeData>>(UiState.Loading())
    val uiState: StateFlow<UiState<HomeData>> = _uiState

    /**
     * Live telemetry streamed at 1Hz while the home screen is visible.
     */
    val telemetry: StateFlow<HomeTelemetry> = combine(
        telemetryRepository.deviceMetrics,
        telemetryRepository.history,
        telemetryRepository.generationHistory,
        telemetryRepository.currentModelName,
        telemetryRepository.isSampling
    ) { device, history, generations, modelName, sampling ->
        HomeTelemetry(
            deviceMetrics = device,
            history = history,
            generationHistory = generations,
            currentModelName = modelName,
            isSampling = sampling,
            isModelLoaded = telemetryRepository.isModelLoaded()
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = HomeTelemetry()
    )

    init {
        observeRecentConversations()
        observeSettings()
        telemetryRepository.startSampling()
    }

    override fun onCleared() {
        telemetryRepository.stopSampling()
        super.onCleared()
    }

    private fun observeRecentConversations() {
        conversationRepository.observeRecent()
            .onEach { conversations ->
                _uiState.value = UiState.Success(
                    HomeData(
                        recentConversations = conversations,
                        isModelLoaded = (_uiState.value as? UiState.Success)?.data?.isModelLoaded ?: false
                    )
                )
            }
            .launchIn(viewModelScope)
    }

    private fun observeSettings() {
        viewModelScope.launch {
            settingsRepository.observeSettings().collect { settings ->
                _uiState.value = UiState.Success(
                    HomeData(
                        recentConversations = (_uiState.value as? UiState.Success)?.data?.recentConversations ?: emptyList(),
                        isModelLoaded = settings.modelPath != null
                    )
                )
            }
        }
    }

    /**
     * Re-reads device metrics immediately (also refreshed on every sample tick).
     */
    fun refresh() {
        telemetryRepository.refreshDeviceMetrics()
    }
}

/**
 * Data loaded on the home screen.
 */
data class HomeData(
    val recentConversations: List<Conversation> = emptyList(),
    val isModelLoaded: Boolean = false
)

/**
 * Live telemetry snapshot for the dashboard.
 */
data class HomeTelemetry(
    val deviceMetrics: DeviceMetrics? = null,
    val history: List<TelemetrySample> = emptyList(),
    val generationHistory: List<GenerationStat> = emptyList(),
    val currentModelName: String = "",
    val isSampling: Boolean = false,
    val isModelLoaded: Boolean = false
) {
    /** Most recent 1-second sample. */
    val latest: TelemetrySample? get() = history.lastOrNull()

    val ramUsedMb: Float get() = latest?.ramUsedMb ?: 0f
    val ramTotalMb: Float get() = latest?.ramTotalMb ?: deviceMetrics?.totalRamMb?.toFloat() ?: 0f
    val tokensPerSecond: Float get() = latest?.tokensPerSecond ?: 0f
    val gpuMemoryMb: Float get() = latest?.gpuMemoryMb ?: 0f
    val kvCacheMb: Float get() = latest?.kvCacheMb ?: 0f
    val isGenerating: Boolean get() = latest?.isGenerating == true

    /** Tokens/sec history for the live chart (most recent first not required; keep chronological). */
    val speedHistory: List<Float> get() = history.map { it.tokensPerSecond }
    val ramHistory: List<Float> get() = history.map { it.ramUsedMb }
    val gpuHistory: List<Float> get() = history.map { it.gpuMemoryMb }

    /** True when the native engine reports Vulkan acceleration available. */
    val vulkanSupported: Boolean get() = deviceMetrics?.isVulkanSupported == true
}
