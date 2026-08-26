package io.androllm.feature.cloud

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.androllm.core.cloud.ProviderHealthMonitor
import io.androllm.core.cloud.ProviderManager
import io.androllm.core.cloud.cache.PromptCache
import io.androllm.core.cloud.cache.PromptCacheStats
import io.androllm.core.cloud.model.CloudHealth
import io.androllm.core.cloud.model.CloudSettings
import io.androllm.core.cloud.usage.CloudUsageFilter
import io.androllm.core.cloud.usage.CloudUsageMeter
import io.androllm.core.cloud.usage.CloudUsageRecord
import io.androllm.core.cloud.usage.CloudUsageSnapshot
import io.androllm.core.common.BaseViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

/** Date-range presets for the dashboard filter. */
enum class UsageDateRange(val label: String, val days: Int?) {
    TODAY("Today", 1),
    WEEK("7 days", 7),
    MONTH("30 days", 30),
    ALL("All", null)
}

/**
 * ViewModel for the Cloud Usage dashboard: usage snapshot, prompt-cache
 * diagnostics, provider health, filters, export and clear actions.
 */
@HiltViewModel
class CloudUsageDashboardViewModel @Inject constructor(
    private val usageMeter: CloudUsageMeter,
    private val promptCache: PromptCache,
    private val providerManager: ProviderManager,
    private val healthMonitor: ProviderHealthMonitor,
    @ApplicationContext private val context: Context
) : BaseViewModel() {

    data class UiState(
        val snapshot: CloudUsageSnapshot? = null,
        val cacheStats: PromptCacheStats = PromptCacheStats(),
        val settings: CloudSettings = CloudSettings(),
        val health: Map<String, CloudHealth> = emptyMap(),
        val dateRange: UsageDateRange = UsageDateRange.WEEK,
        val providerFilter: String? = null,
        val modelFilter: String? = null,
        val refreshing: Boolean = false,
        val history: List<CloudUsageRecord> = emptyList(),
        val historyExpanded: Boolean = false
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        viewModelScope.launch {
            usageMeter.init()
            promptCache.init()
            refresh()
        }
        viewModelScope.launch {
            usageMeter.snapshots.collect { snapshot ->
                _uiState.update { it.copy(snapshot = usageMeter.snapshot(currentFilter())) }
            }
        }
        viewModelScope.launch {
            promptCache.stats.collect { stats ->
                _uiState.update { it.copy(cacheStats = stats) }
            }
        }
        viewModelScope.launch {
            providerManager.settings.collect { settings ->
                _uiState.update { it.copy(settings = settings) }
            }
        }
        viewModelScope.launch {
            healthMonitor.status.collect { health ->
                _uiState.update { it.copy(health = health) }
            }
        }
    }

    fun clearMessage() {
        _message.value = null
    }

    /** Recomputes the snapshot with the current filters and probes health. */
    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(refreshing = true) }
            runCatching {
                usageMeter.init()
                healthMonitor.checkAll()
            }.onFailure { e -> Timber.w(e, "CloudUsageDashboard: refresh failed") }
            _uiState.update {
                it.copy(
                    snapshot = usageMeter.snapshot(currentFilter()),
                    history = usageMeter.records(currentFilter(), limit = 100),
                    refreshing = false
                )
            }
        }
    }

    fun setDateRange(range: UsageDateRange) {
        _uiState.update { it.copy(dateRange = range) }
        applyFilter()
    }

    fun setProviderFilter(providerId: String?) {
        _uiState.update { it.copy(providerFilter = providerId) }
        applyFilter()
    }

    fun setModelFilter(modelId: String?) {
        _uiState.update { it.copy(modelFilter = modelId) }
        applyFilter()
    }

    fun toggleHistory() {
        _uiState.update {
            it.copy(
                historyExpanded = !it.historyExpanded,
                history = if (!it.historyExpanded) usageMeter.records(currentFilter(), limit = 100) else it.history
            )
        }
    }

    /** Clears all recorded usage (dashboard action, requires confirmation). */
    fun clearUsage() {
        viewModelScope.launch {
            usageMeter.clear()
            promptCache.clear()
            _uiState.update {
                it.copy(
                    snapshot = usageMeter.snapshot(CloudUsageFilter.NONE),
                    history = emptyList(),
                    cacheStats = promptCache.stats.value
                )
            }
            _message.value = "Cloud usage data cleared"
        }
    }

    /** Exports the filtered usage as CSV and shares it. */
    fun exportUsage() {
        viewModelScope.launch {
            val dir = java.io.File(context.cacheDir, "exports")
            val file = usageMeter.exportCsvTo(dir, currentFilter())
            if (file == null) {
                _message.value = "Export failed — try again"
                return@launch
            }
            runCatching {
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file
                )
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/csv"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, "AndroLLM Cloud Usage Export")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(send, "Export cloud usage").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                _message.value = "Exported ${file.name}"
            }.onFailure { e ->
                Timber.w(e, "CloudUsageDashboard: share failed")
                _message.value = "Saved to ${file.absolutePath}"
            }
        }
    }

    private fun applyFilter() {
        _uiState.update {
            it.copy(
                snapshot = usageMeter.snapshot(currentFilter()),
                history = usageMeter.records(currentFilter(), limit = 100)
            )
        }
    }

    private fun currentFilter(): CloudUsageFilter {
        val state = _uiState.value
        val now = System.currentTimeMillis()
        val fromMs = state.dateRange.days?.let { days -> now - days * 24L * 3600 * 1000 }
        return CloudUsageFilter(
            fromMs = fromMs,
            toMs = null,
            providerId = state.providerFilter,
            modelId = state.modelFilter
        )
    }
}
