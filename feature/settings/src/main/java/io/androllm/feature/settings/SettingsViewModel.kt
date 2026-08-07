package io.androllm.feature.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.androllm.core.common.BaseViewModel
import io.androllm.core.common.UiState
import io.androllm.core.common.onError
import io.androllm.core.common.onSuccess
import io.androllm.core.database.repository.SettingsRepository
import io.androllm.core.memory.MemoryManager
import io.androllm.core.memory.model.MemoryInspectorStats
import io.androllm.core.memory.model.MemorySettings
import io.androllm.core.models.ThemeMode
import io.androllm.core.utils.LogUtils
import io.androllm.core.utils.ShareUtils
import io.androllm.core.utils.StorageUtils
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel for the settings screen.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val memoryManager: MemoryManager
) : BaseViewModel() {

    private val _uiState = MutableStateFlow<UiState<SettingsData>>(UiState.Loading())
    val uiState: StateFlow<UiState<SettingsData>> = _uiState

    private val _logPreview = MutableStateFlow("")
    val logPreview: StateFlow<String> = _logPreview

    private val _memorySettings = MutableStateFlow(MemorySettings())
    val memorySettings: StateFlow<MemorySettings> = _memorySettings.asStateFlow()

    private val _memoryStats = MutableStateFlow<MemoryInspectorStats?>(null)
    val memoryStats: StateFlow<MemoryInspectorStats?> = _memoryStats.asStateFlow()

    private val _memoryMessage = MutableStateFlow<String?>(null)
    val memoryMessage: StateFlow<String?> = _memoryMessage.asStateFlow()

    private val _storageStats = MutableStateFlow<io.androllm.core.utils.StorageStats?>(null)
    val storageStats: StateFlow<io.androllm.core.utils.StorageStats?> = _storageStats.asStateFlow()

    /** Firebase is optional â€” the settings header must still work offline as a guest. */
    private val auth: FirebaseAuth? = runCatching { FirebaseAuth.getInstance() }.getOrNull()

    private val _user = MutableStateFlow(auth?.currentUser?.toSettingsUser())
    val user: StateFlow<SettingsIdentity?> = _user.asStateFlow()

    private val authListener = FirebaseAuth.AuthStateListener { firebaseAuth ->
        _user.value = firebaseAuth.currentUser?.toSettingsUser()
    }

    init {
        observeSettings()
        refreshLogPreview()
        auth?.addAuthStateListener(authListener)
        observeMemorySettings()
        refreshMemoryStats()
        refreshStorageStats()
    }

    override fun onCleared() {
        super.onCleared()
        auth?.removeAuthStateListener(authListener)
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
            refreshStorageStats()
        }
    }

    /**
     * Re-reads the real free space on the models filesystem (cheap: a single
     * filesystem stat, no directory walk). Never throws — a storage read must
     * not take down the settings screen (or leak an uncaught coroutine
     * exception in tests when the mock context returns unusable File mocks).
     * Runs entirely on IO: the coroutine must not need the Main dispatcher to
     * resume after a test teardown (StateFlow writes are thread-safe).
     */
    fun refreshStorageStats() {
        viewModelScope.launch(Dispatchers.IO) {
            val stats = runCatching { StorageUtils.getStorageStats(context) }.getOrNull()
            if (stats != null) {
                _storageStats.value = stats
            }
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

    // â”€â”€ On-device memory system â”€â”€

    private fun observeMemorySettings() {
        viewModelScope.launch {
            memoryManager.settings.collect { _memorySettings.value = it }
        }
    }

    fun refreshMemoryStats() {
        viewModelScope.launch {
            _memoryStats.value = memoryManager.getInspectorStats()
        }
    }

    fun toggleMemoryEnabled() {
        viewModelScope.launch {
            val enabled = !_memorySettings.value.enabled
            memoryManager.updateSettings { it.copy(enabled = enabled) }
            if (enabled) {
                memoryManager.preloadEmbeddingModel()
                _memoryMessage.value = "Memory enabled"
            } else {
                _memoryMessage.value = "Memory disabled"
            }
            refreshMemoryStats()
        }
    }

    fun updateSimilarityThreshold(value: Float) {
        viewModelScope.launch {
            memoryManager.updateSettings { it.copy(similarityThreshold = value) }
        }
    }

    fun updateRetrievalCount(value: Int) {
        viewModelScope.launch {
            memoryManager.updateSettings { it.copy(retrievalCount = value) }
        }
    }

    fun updateSummarizationInterval(value: Int) {
        viewModelScope.launch {
            memoryManager.updateSettings { it.copy(summarizationInterval = value) }
        }
    }

    fun setEmbeddingModelPath(path: String) {
        viewModelScope.launch {
            memoryManager.setEmbeddingModelPath(path.trim())
            _memoryMessage.value = "Embedding model path saved"
            refreshMemoryStats()
        }
    }

    /**
     * Points embeddings at the active cloud provider's embedding model
     * (OpenAI-compatible id, e.g. "openai/text-embedding-3-small"). Empty
     * clears the cloud route and reverts to the local GGUF model.
     */
    fun setCloudEmbeddingModel(modelId: String) {
        viewModelScope.launch {
            memoryManager.setCloudEmbeddingModel(modelId.trim())
            _memoryMessage.value = if (modelId.isBlank()) {
                "Cloud embedding disabled — using local model"
            } else {
                "Cloud embedding model saved: $modelId"
            }
            refreshMemoryStats()
        }
    }

    fun testEmbeddingModel() {
        viewModelScope.launch {
            memoryManager.preloadEmbeddingModel()
                .onSuccess { _memoryMessage.value = "Embedding model loaded (dim ${memoryManager.getInspectorStats().embeddingDimension})" }
                .onError { _memoryMessage.value = "Model load failed: ${it.message}" }
            refreshMemoryStats()
        }
    }

    fun deleteAllMemories() {
        viewModelScope.launch {
            memoryManager.deleteAll()
            _memoryMessage.value = "All memories deleted"
            refreshMemoryStats()
        }
    }

    fun exportMemories() {
        viewModelScope.launch {
            memoryManager.exportMemories()
                .onSuccess { file ->
                    ShareUtils.shareFile(context, file, "AndroLLM Memory Export")
                    _memoryMessage.value = "Export shared"
                }
                .onError { _memoryMessage.value = "Export failed: ${it.message}" }
        }
    }

    /**
     * Imports memories from a user-picked JSON file (SAF Uri).
     */
    fun importMemories(uri: Uri?) {
        if (uri == null) return
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                val temp = File(context.cacheDir, "memory_import_${System.currentTimeMillis()}.json")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    temp.outputStream().use { input.copyTo(it) }
                }
                memoryManager.importMemories(temp)
            }
            result.onSuccess {
                _memoryMessage.value = "Imported +${it.inserted} ~${it.updated} -${it.skipped}"
            }.onError {
                _memoryMessage.value = "Import failed: ${it.message}"
            }
            refreshMemoryStats()
        }
    }

    fun clearMemoryMessage() {
        _memoryMessage.value = null
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

/**
 * UI snapshot of the Firebase identity for the settings header (null-safe).
 */
data class SettingsIdentity(
    val displayName: String?,
    val email: String?
) {
    val isGuest: Boolean get() = email.isNullOrBlank()
}

private fun FirebaseUser.toSettingsUser(): SettingsIdentity = SettingsIdentity(
    displayName = displayName,
    email = email
)
