package io.androllm.feature.settings

import android.content.Context
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.androllm.core.common.BaseViewModel
import io.androllm.core.common.UiState
import io.androllm.core.database.repository.SettingsRepository
import io.androllm.core.models.ThemeMode
import io.androllm.core.utils.LogUtils
import io.androllm.core.utils.ShareUtils
import io.androllm.core.utils.StorageUtils
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * ViewModel for the settings screen.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository
) : BaseViewModel() {

    private val _uiState = MutableStateFlow<UiState<SettingsData>>(UiState.Loading())
    val uiState: StateFlow<UiState<SettingsData>> = _uiState

    private val _logPreview = MutableStateFlow("")
    val logPreview: StateFlow<String> = _logPreview

    init {
        observeSettings()
        refreshLogPreview()
    }

    private fun observeSettings() {
        settingsRepository.observeSettings()
            .onEach { settings ->
                _uiState.value = UiState.Success(
                    SettingsData(
                        theme = settings.theme,
                        language = settings.language,
                        storagePath = settings.storagePath,
                        developerMode = settings.developerMode,
                        versionName = context.getVersionNameSafe(),
                        markdownEnabled = settings.markdownEnabled,
                        codeWrapping = settings.codeWrapping,
                        autoScroll = settings.autoScroll,
                        typingIndicator = settings.typingIndicator
                    )
                )
            }
            .launchIn(viewModelScope)
    }

    /**
     * Cycles the theme between system, light and dark.
     */
    fun cycleTheme() {
        viewModelScope.launch {
            val current = (_uiState.value as? UiState.Success)?.data?.theme ?: ThemeMode.SYSTEM
            val next = when (current) {
                ThemeMode.SYSTEM -> ThemeMode.LIGHT
                ThemeMode.LIGHT -> ThemeMode.DARK
                ThemeMode.DARK -> ThemeMode.SYSTEM
            }
            settingsRepository.updateTheme(next)
        }
    }

    /**
     * Toggles developer mode.
     */
    fun toggleDeveloperMode() {
        viewModelScope.launch {
            val current = (_uiState.value as? UiState.Success)?.data?.developerMode ?: false
            settingsRepository.updateDeveloperMode(!current)
        }
    }

    /**
     * Clears the app cache.
     */
    fun clearCache() {
        viewModelScope.launch {
            StorageUtils.clearCache(context)
        }
    }

    fun exportLogs() {
        viewModelScope.launch {
            val logFile = LogUtils.getLogFile(context)
            if (logFile.exists()) {
                ShareUtils.shareFile(context, logFile, "AndroLLM Logs")
            }
        }
    }

    fun refreshLogPreview() {
        _logPreview.value = LogUtils.readRecentLogs(context)
    }

    fun toggleMarkdownEnabled() {
        viewModelScope.launch {
            val current = (_uiState.value as? UiState.Success)?.data?.markdownEnabled ?: true
            settingsRepository.updateSettings { it.copy(markdownEnabled = !current) }
        }
    }

    fun toggleCodeWrapping() {
        viewModelScope.launch {
            val current = (_uiState.value as? UiState.Success)?.data?.codeWrapping ?: false
            settingsRepository.updateSettings { it.copy(codeWrapping = !current) }
        }
    }

    fun toggleAutoScroll() {
        viewModelScope.launch {
            val current = (_uiState.value as? UiState.Success)?.data?.autoScroll ?: true
            settingsRepository.updateSettings { it.copy(autoScroll = !current) }
        }
    }

    fun toggleTypingIndicator() {
        viewModelScope.launch {
            val current = (_uiState.value as? UiState.Success)?.data?.typingIndicator ?: true
            settingsRepository.updateSettings { it.copy(typingIndicator = !current) }
        }
    }

    /**
     * Reads the version name without throwing.
     */
    private fun Context.getVersionNameSafe(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0"
    }.getOrDefault("1.0")
}

/**
 * Data displayed on the settings screen.
 */
data class SettingsData(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val language: String = "en",
    val storagePath: String = "",
    val developerMode: Boolean = false,
    val versionName: String = "1.0",
    val markdownEnabled: Boolean = true,
    val codeWrapping: Boolean = false,
    val autoScroll: Boolean = true,
    val typingIndicator: Boolean = true
)
