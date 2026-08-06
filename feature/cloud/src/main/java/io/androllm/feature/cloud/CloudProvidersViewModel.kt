package io.androllm.feature.cloud

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.androllm.core.cloud.ProviderHealthMonitor
import io.androllm.core.cloud.ProviderManager
import io.androllm.core.cloud.model.CloudHealth
import io.androllm.core.cloud.model.CloudSettings
import io.androllm.core.common.BaseViewModel
import io.androllm.core.common.onError
import io.androllm.core.common.onSuccess
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ViewModel for the Cloud Providers screen: provider CRUD, enable/disable,
 * default selection, connection testing, model refresh, and live health
 * status from [ProviderHealthMonitor].
 */
@HiltViewModel
class CloudProvidersViewModel @Inject constructor(
    private val providerManager: ProviderManager,
    private val healthMonitor: ProviderHealthMonitor
) : BaseViewModel() {

    data class UiState(
        val settings: CloudSettings = CloudSettings(),
        val testingId: String? = null,
        val refreshingId: String? = null
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** Latest per-provider health snapshot (liveliness/readiness/latency). */
    val healthStatus: StateFlow<Map<String, CloudHealth>> = healthMonitor.status

    init {
        viewModelScope.launch {
            providerManager.settings.collect { settings ->
                _uiState.update { it.copy(settings = settings) }
            }
        }
        // Probe all providers once when the screen opens, then rely on the
        // background cadence configured in ProviderSettings.
        viewModelScope.launch {
            healthMonitor.checkAll()
        }
    }

    fun addProvider(
        name: String,
        baseUrl: String,
        apiKey: String,
        apiKeyHeader: String,
        extraHeaders: Map<String, String>,
        description: String,
        tags: List<String>
    ) {
        viewModelScope.launch {
            io.androllm.core.common.runCatching {
                providerManager.addProvider(
                    name = name,
                    baseUrl = baseUrl,
                    apiKey = apiKey,
                    apiKeyHeader = apiKeyHeader,
                    extraHeaders = extraHeaders,
                    description = description,
                    tags = tags
                )
            }.onSuccess { provider ->
                _message.value = "Provider '${provider.name}' added"
            }.onError { e ->
                _message.value = "Could not add provider: ${e.message}"
            }
        }
    }

    fun updateProvider(
        id: String,
        name: String,
        baseUrl: String,
        apiKey: String?,
        apiKeyHeader: String,
        extraHeaders: Map<String, String>,
        description: String,
        tags: List<String>
    ) {
        viewModelScope.launch {
            io.androllm.core.common.runCatching {
                providerManager.updateProvider(
                    id = id,
                    name = name,
                    baseUrl = baseUrl,
                    apiKey = apiKey,
                    apiKeyHeader = apiKeyHeader,
                    extraHeaders = extraHeaders,
                    description = description,
                    tags = tags
                )
            }.onSuccess {
                _message.value = "Provider updated"
            }.onError { e ->
                _message.value = "Could not update provider: ${e.message}"
            }
        }
    }

    fun deleteProvider(id: String) {
        viewModelScope.launch {
            providerManager.deleteProvider(id)
            _message.value = "Provider deleted"
        }
    }

    fun toggleEnabled(id: String) {
        viewModelScope.launch {
            val provider = _uiState.value.settings.providers.find { it.id == id } ?: return@launch
            providerManager.setEnabled(id, !provider.enabled)
        }
    }

    fun setDefault(id: String) {
        viewModelScope.launch {
            providerManager.setDefaultProvider(id)
            _message.value = "Default provider set"
        }
    }

    fun toggleCloudMode() {
        viewModelScope.launch {
            providerManager.setCloudModeEnabled(!_uiState.value.settings.enabled)
        }
    }

    fun testConnection(id: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(testingId = id) }
            val result = io.androllm.core.common.runCatching { providerManager.testConnection(id) }
            _uiState.update { it.copy(testingId = null) }
            result.onSuccess { r ->
                _message.value = if (r.ok) {
                    "OK — ${r.modelCount} models discovered (${r.latencyMs} ms)"
                } else {
                    "Connection failed: ${r.error.ifBlank { "proxy unreachable" }}"
                }
            }.onError { e ->
                _message.value = "Connection failed: ${e.message}"
            }
        }
    }

    fun refreshModels(id: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(refreshingId = id) }
            val result = io.androllm.core.common.runCatching { providerManager.refreshModels(id) }
            _uiState.update { it.copy(refreshingId = null) }
            result.onSuccess { count ->
                _message.value = "Model list refreshed ($count models)"
            }.onError { e ->
                _message.value = "Refresh failed: ${e.message}"
            }
        }
    }

    fun clearMessage() {
        _message.value = null
    }
}
