package io.androllm.feature.cloud

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.androllm.core.cloud.ProviderManager
import io.androllm.core.cloud.model.CloudCustomModel
import io.androllm.core.cloud.model.CloudModelProvider
import io.androllm.core.cloud.model.CloudProvider
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
 * ViewModel for the Cloud Models screen: browse the cached model list of a
 * provider (discovered + custom), refresh discovery, favorite models, pick
 * the default model, and manage custom LiteLLM models.
 */
@HiltViewModel
class CloudModelsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val providerManager: ProviderManager
) : BaseViewModel() {

    data class UiState(
        val settings: CloudSettings = CloudSettings(),
        val provider: CloudProvider? = null,
        val refreshing: Boolean = false
    )

    /** A merged, UI-ready model entry (discovered or custom). */
    data class ModelEntry(
        val model: CloudModelProvider,
        val custom: CloudCustomModel? = null
    )

    private val requestedProviderId: String = savedStateHandle["providerId"] ?: ""

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        viewModelScope.launch {
            providerManager.settings.collect { settings ->
                val provider = settings.providers.find { it.id == requestedProviderId }
                    ?: settings.providers.find { it.id == settings.defaultProviderId }
                    ?: settings.providers.firstOrNull()
                _uiState.update { it.copy(settings = settings, provider = provider) }
            }
        }
    }

    /** Discovered + custom models for the active provider, ready to render. */
    fun entriesFor(provider: CloudProvider?, settings: CloudSettings): List<ModelEntry> {
        if (provider == null) return emptyList()
        return buildList {
            provider.modelIds.forEach { id ->
                add(
                    ModelEntry(
                        model = CloudModelProvider(
                            id = id,
                            providerId = provider.id,
                            providerName = provider.name,
                            isCustom = false,
                            contextWindow = provider.modelContextWindows[id],
                            isFavorite = id in settings.favoriteModelIds,
                            isDefault = settings.defaultModelId == id,
                            enabled = provider.enabled
                        )
                    )
                )
            }
            provider.customModels.forEach { custom ->
                add(
                    ModelEntry(
                        model = CloudModelProvider(
                            id = custom.modelId,
                            providerId = provider.id,
                            providerName = provider.name,
                            displayName = custom.modelName,
                            isCustom = true,
                            description = custom.description,
                            tags = custom.tags,
                            contextWindow = provider.modelContextWindows[custom.modelId],
                            isFavorite = custom.modelId in settings.favoriteModelIds,
                            isDefault = settings.defaultModelId == custom.modelId,
                            enabled = provider.enabled
                        ),
                        custom = custom
                    )
                )
            }
        }
    }

    fun refresh() {
        val provider = _uiState.value.provider ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(refreshing = true) }
            val result = io.androllm.core.common.runCatching { providerManager.refreshModels(provider.id) }
            _uiState.update { it.copy(refreshing = false) }
            result.onSuccess { count ->
                _message.value = "Discovered $count models"
            }.onError { e ->
                _message.value = "Refresh failed: ${e.message}"
            }
        }
    }

    fun toggleFavorite(modelId: String) {
        viewModelScope.launch {
            providerManager.toggleFavorite(modelId)
        }
    }

    fun setDefaultModel(modelId: String) {
        viewModelScope.launch {
            providerManager.setDefaultModel(modelId)
            _message.value = "Default model set"
        }
    }

    fun addCustomModel(
        modelName: String,
        modelId: String,
        apiBaseUrl: String,
        apiKey: String,
        apiKeyHeader: String,
        headersText: String,
        description: String,
        tagsText: String
    ) {
        val provider = _uiState.value.provider ?: return
        viewModelScope.launch {
            io.androllm.core.common.runCatching {
                providerManager.addCustomModel(
                    providerId = provider.id,
                    modelName = modelName,
                    modelId = modelId,
                    apiBaseUrl = apiBaseUrl.ifBlank { null },
                    apiKey = apiKey,
                    apiKeyHeader = apiKeyHeader,
                    extraHeaders = parseHeaders(headersText),
                    description = description,
                    tags = parseTags(tagsText)
                )
            }.onSuccess {
                _message.value = "Custom model added"
            }.onError { e ->
                _message.value = "Could not add custom model: ${e.message}"
            }
        }
    }

    fun updateCustomModel(
        customModelId: String,
        modelName: String,
        modelId: String,
        apiBaseUrl: String,
        apiKey: String?,
        apiKeyHeader: String,
        headersText: String,
        description: String,
        tagsText: String
    ) {
        val provider = _uiState.value.provider ?: return
        viewModelScope.launch {
            io.androllm.core.common.runCatching {
                providerManager.updateCustomModel(
                    providerId = provider.id,
                    customModelId = customModelId,
                    modelName = modelName,
                    modelId = modelId,
                    apiBaseUrl = apiBaseUrl.ifBlank { null },
                    apiKey = apiKey,
                    apiKeyHeader = apiKeyHeader,
                    extraHeaders = parseHeaders(headersText),
                    description = description,
                    tags = parseTags(tagsText)
                )
            }.onSuccess {
                _message.value = "Custom model updated"
            }.onError { e ->
                _message.value = "Could not update custom model: ${e.message}"
            }
        }
    }

    fun deleteCustomModel(customModelId: String) {
        val provider = _uiState.value.provider ?: return
        viewModelScope.launch {
            providerManager.deleteCustomModel(provider.id, customModelId)
            _message.value = "Custom model deleted"
        }
    }

    fun clearMessage() {
        _message.value = null
    }
}
