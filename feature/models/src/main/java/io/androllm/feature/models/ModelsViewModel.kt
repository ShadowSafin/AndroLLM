package io.androllm.feature.models

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.viewModelScope
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.androllm.core.common.BaseViewModel
import io.androllm.core.common.Result
import io.androllm.core.common.UiState
import io.androllm.core.common.getOrNull
import io.androllm.core.database.repository.ModelRepository
import io.androllm.core.models.DownloadStatus
import io.androllm.core.models.Model
import io.androllm.core.models.ModelFormat
import io.androllm.core.models.ModelStatus
import io.androllm.core.models.RemoteGgufFile
import io.androllm.core.models.RemoteModelDetails
import io.androllm.core.models.RemoteModelSummary
import io.androllm.core.models.RepositoryFilter
import io.androllm.core.network.repository.RepositoryRegistry
import io.androllm.core.utils.DeviceHardwareInfo
import io.androllm.core.utils.DeviceInfoCollector
import io.androllm.core.utils.StorageStats
import io.androllm.core.utils.StorageUtils
import io.androllm.engine.api.EngineRepository
import io.androllm.engine.api.EngineState
import io.androllm.engine.models.MemoryStats
import io.androllm.engine.utils.GgufValidator
import io.androllm.feature.models.benchmark.BenchmarkReport
import io.androllm.feature.models.benchmark.ModelBenchmarker
import io.androllm.feature.models.catalog.OfficialModelCatalog
import io.androllm.feature.models.downloader.ModelDownloadWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject

enum class ModelsTab {
    INSTALLED,
    DOWNLOADS,
    CATALOG,
    HUGGINGFACE,
    DIAGNOSTICS
}

enum class ModelSortOption {
    NAME,
    SIZE,
    DATE,
    RAM
}

/**
 * ViewModel for the Model Manager screen handling catalog browsing, Hugging Face Hub integration,
 * background downloads, SAF GGUF import validation, benchmark execution, and sorting/filtering.
 */
@HiltViewModel
class ModelsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val modelRepository: ModelRepository,
    private val engineRepository: EngineRepository,
    private val repositoryRegistry: RepositoryRegistry,
    val downloadManager: io.androllm.feature.models.downloader.DownloadManager
) : BaseViewModel() {

    private val _selectedTab = MutableStateFlow(ModelsTab.INSTALLED)
    private val _searchQuery = MutableStateFlow("")
    private val _sortOption = MutableStateFlow(ModelSortOption.NAME)
    private val _loadingModelId = MutableStateFlow<String?>(null)
    private val _importing = MutableStateFlow(false)
    private val _errorMessage = MutableStateFlow<String?>(null)
    private val _benchmarkReport = MutableStateFlow<BenchmarkReport?>(null)
    private val _isBenchmarking = MutableStateFlow(false)

    // Hugging Face Remote State
    private val _remoteModels = MutableStateFlow<List<RemoteModelSummary>>(emptyList())
    private val _isSearchingRemote = MutableStateFlow(false)
    private val _selectedRemoteDetails = MutableStateFlow<RemoteModelDetails?>(null)
    private val _readmeText = MutableStateFlow<String?>(null)
    private val _isLoadingDetails = MutableStateFlow(false)

    val hardwareInfo: DeviceHardwareInfo = DeviceInfoCollector.collectDeviceInfo(context)

    val uiState: StateFlow<UiState<ModelsData>> = combine(
        combine(
            modelRepository.observeAllModels(),
            engineRepository.engineState,
            _selectedTab,
            _searchQuery
        ) { installedModels, engineState, tab, query ->
            listOf(installedModels, engineState, tab, query)
        },
        combine(
            _sortOption,
            _loadingModelId,
            _importing,
            _errorMessage
        ) { sort, loadingId, importing, error ->
            listOf(sort, loadingId, importing, error)
        },
        combine(
            _benchmarkReport,
            _isBenchmarking,
            _remoteModels,
            _isSearchingRemote
        ) { benchReport, isBenchmarking, remoteModels, isSearchingRemote ->
            listOf(benchReport, isBenchmarking, remoteModels, isSearchingRemote)
        },
        combine(
            _selectedRemoteDetails,
            _readmeText,
            _isLoadingDetails
        ) { remoteDetails, readme, isLoadingDetails ->
            Triple(remoteDetails, readme, isLoadingDetails)
        }
    ) { group1, group2, group3, group4 ->
        @Suppress("UNCHECKED_CAST")
        val installedModels = group1[0] as List<Model>
        val engineState = group1[1] as EngineState
        val tab = group1[2] as ModelsTab
        val query = group1[3] as String

        val sort = group2[0] as ModelSortOption
        val loadingId = group2[1] as String?
        val importing = group2[2] as Boolean
        val error = group2[3] as String?

        val benchReport = group3[0] as BenchmarkReport?
        val isBenchmarking = group3[1] as Boolean
        @Suppress("UNCHECKED_CAST")
        val remoteModels = group3[2] as List<RemoteModelSummary>
        val isSearchingRemote = group3[3] as Boolean

        val (remoteDetails, readme, isLoadingDetails) = group4

        val loadedId = when (engineState) {
            is EngineState.Ready -> engineState.model.id
            is EngineState.Generating -> engineState.model.id
            else -> null
        }
        val memStats = (engineState as? EngineState.Ready)?.memoryStats
        val filteredInstalled = sortAndFilter(installedModels, query, sort)
        val filteredCatalog = sortAndFilter(OfficialModelCatalog.catalogModels, query, sort)

        UiState.Success(
            ModelsData(
                installedModels = filteredInstalled,
                catalogModels = filteredCatalog,
                selectedTab = tab,
                searchQuery = query,
                sortOption = sort,
                loadedModelId = loadedId,
                loadingModelId = loadingId,
                importing = importing,
                errorMessage = error,
                storageStats = StorageUtils.getStorageStats(context),
                hardwareInfo = hardwareInfo,
                benchmarkReport = benchReport,
                isBenchmarking = isBenchmarking,
                remoteModels = remoteModels,
                isSearchingRemote = isSearchingRemote,
                selectedRemoteDetails = remoteDetails,
                readmeText = readme,
                isLoadingDetails = isLoadingDetails,
                recommendedModel = computeRecommendedModel(hardwareInfo.totalRamGb),
                showFirstLaunchDialog = installedModels.none { it.isDownloaded },
                engineState = engineState,
                memoryStats = memStats
            )
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = UiState.Loading()
    )

    init {
        viewModelScope.launch {
            engineRepository.initialize()
        }
        searchHuggingFace("")
    }

    fun selectTab(tab: ModelsTab) {
        _selectedTab.value = tab
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
        if (_selectedTab.value == ModelsTab.HUGGINGFACE) {
            searchHuggingFace(query)
        }
    }

    fun searchHuggingFace(query: String) {
        _isSearchingRemote.value = true
        viewModelScope.launch {
            repositoryRegistry.getActiveProvider()
                .searchModels(RepositoryFilter(searchQuery = query))
                .collect { result ->
                    _isSearchingRemote.value = false
                    val models = (result as? Result.Success)?.data
                    if (models != null) {
                        _remoteModels.value = models
                    }
                }
        }
    }

    fun fetchRemoteDetails(modelId: String) {
        _isLoadingDetails.value = true
        _selectedRemoteDetails.value = null
        _readmeText.value = null

        viewModelScope.launch {
            val provider = repositoryRegistry.getActiveProvider()
            provider.getModelDetails(modelId).collect { result ->
                _isLoadingDetails.value = false
                val details = (result as? Result.Success)?.data
                if (details != null) {
                    _selectedRemoteDetails.value = details
                }
            }

            provider.getReadme(modelId).collect { readmeResult ->
                _readmeText.value = (readmeResult as? Result.Success)?.data
            }
        }
    }

    fun dismissRemoteDetails() {
        _selectedRemoteDetails.value = null
        _readmeText.value = null
    }

    fun downloadRemoteGguf(summary: RemoteModelSummary, file: RemoteGgufFile) {
        viewModelScope.launch {
            val mediaDir = File(context.getExternalFilesDir(null) ?: context.filesDir, "models").apply { mkdirs() }
            val safeFilename = file.filename.replace(Regex("[^a-zA-Z0-9._\\-]"), "_")
            val targetFile = File(mediaDir, safeFilename)

            val newModel = Model(
                id = UUID.randomUUID().toString(),
                name = "${summary.name} (${file.quantization})",
                description = "Downloaded from Hugging Face (${summary.author}/${summary.name})",
                filePath = targetFile.absolutePath,
                fileSize = file.sizeBytes,
                format = ModelFormat.GGUF,
                quantization = file.quantization,
                downloadUrl = file.downloadUrl,
                isDownloaded = false,
                downloadStatus = DownloadStatus.DOWNLOADING,
                status = ModelStatus.NOT_LOADED,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
                addedDate = System.currentTimeMillis(),
                minRamGb = file.minRamGb,
                recommendedRamGb = file.recommendedRamGb
            )
            downloadManager.startDownload(newModel)
            _selectedRemoteDetails.value = null
            selectTab(ModelsTab.DOWNLOADS)
        }
    }

    fun updateSortOption(option: ModelSortOption) {
        _sortOption.value = option
    }

    fun loadModel(model: Model) {
        if (_loadingModelId.value != null) return
        _loadingModelId.value = model.id
        _errorMessage.value = null

        viewModelScope.launch {
            val filePath = model.filePath
            if (filePath.isNullOrBlank() || !File(filePath).exists()) {
                _errorMessage.value = if (!model.isDownloaded) {
                    "Model is still downloading. Please wait for download to finish."
                } else {
                    "Model file does not exist on disk."
                }
                _loadingModelId.value = null
                return@launch
            }

            val validation = GgufValidator.validateHeader(filePath)
            if (!validation.isValid) {
                _errorMessage.value = "Validation Error: ${validation.errorMessage}"
                _loadingModelId.value = null
                modelRepository.updateLoadState(model.id, false, ModelStatus.ERROR)
                return@launch
            }

            val result = engineRepository.loadModel(model)
            _loadingModelId.value = null

            when (result) {
                is Result.Success -> {
                    modelRepository.updateLoadState(model.id, true, ModelStatus.LOADED)
                    modelRepository.updateLastUsed(model.id)
                }
                is Result.Error -> {
                    _errorMessage.value = result.exception.message
                    modelRepository.updateLoadState(model.id, false, ModelStatus.ERROR)
                }
            }
        }
    }

    fun unloadModel(model: Model) {
        viewModelScope.launch {
            engineRepository.unloadModel()
            modelRepository.updateLoadState(model.id, false, ModelStatus.NOT_LOADED)
        }
    }

    fun toggleFavorite(model: Model) {
        viewModelScope.launch {
            modelRepository.setFavorite(model.id, !model.isFavorite)
        }
    }

    fun setDefaultModel(model: Model) {
        viewModelScope.launch {
            modelRepository.setDefaultModel(model.id)
        }
    }

    fun renameModel(modelId: String, newName: String) {
        viewModelScope.launch {
            modelRepository.renameModel(modelId, newName)
        }
    }

    fun deleteModel(model: Model) {
        viewModelScope.launch {
            model.filePath?.let { path ->
                val file = File(path)
                if (file.exists()) file.delete()
            }
            modelRepository.deleteById(model.id)
        }
    }

    fun downloadModel(catalogModel: Model) {
        viewModelScope.launch {
            downloadManager.startDownload(catalogModel)
            selectTab(ModelsTab.DOWNLOADS)
        }
    }

    fun pauseDownload(modelId: String) {
        downloadManager.pauseDownload(modelId)
    }

    fun resumeDownload(model: Model) {
        downloadManager.resumeDownload(model)
    }

    fun cancelDownload(modelId: String) {
        downloadManager.cancelDownload(modelId)
    }

    fun retryDownload(model: Model) {
        downloadManager.retryDownload(model)
    }

    fun pauseAllDownloads(models: List<Model>) {
        downloadManager.pauseAll(models)
    }

    fun resumeAllDownloads(models: List<Model>) {
        downloadManager.resumeAll(models)
    }

    fun cancelAllDownloads(models: List<Model>) {
        downloadManager.cancelAll(models)
    }

    fun importModel(uri: Uri) {
        _importing.value = true
        _errorMessage.value = null

        viewModelScope.launch {
            try {
                val model = withContext(Dispatchers.IO) {
                    val displayName = queryDisplayName(uri)
                    val safeName = displayName.replace(Regex("[^a-zA-Z0-9._\\-]"), "_")
                    val dir = File(context.filesDir, "models").apply { mkdirs() }
                    val target = File(dir, safeName)

                    context.contentResolver.openInputStream(uri)?.use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    } ?: error("Cannot open selected URI")

                    val validation = GgufValidator.validateHeader(target.absolutePath)
                    if (!validation.isValid) {
                        target.delete()
                        error("Header Validation Failed: ${validation.errorMessage}")
                    }

                    Model(
                        id = UUID.randomUUID().toString(),
                        name = safeName.removeSuffix(".gguf"),
                        description = "Imported GGUF model (v${validation.version})",
                        filePath = target.absolutePath,
                        fileSize = target.length(),
                        format = ModelFormat.GGUF,
                        isDownloaded = true,
                        downloadStatus = DownloadStatus.DOWNLOADED,
                        status = ModelStatus.NOT_LOADED,
                        createdAt = System.currentTimeMillis(),
                        updatedAt = System.currentTimeMillis(),
                        addedDate = System.currentTimeMillis(),
                        architecture = validation.architecture
                    )
                }

                val result = modelRepository.upsert(model)
                if (result is Result.Error) {
                    _errorMessage.value = result.exception.message
                }
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "Import failed"
            } finally {
                _importing.value = false
            }
        }
    }

    fun runBenchmark(model: Model) {
        _isBenchmarking.value = true
        viewModelScope.launch {
            val result = ModelBenchmarker.runBenchmark(model, engineRepository)
            _isBenchmarking.value = false
            _benchmarkReport.value = result.getOrNull()
        }
    }

    fun dismissBenchmarkReport() {
        _benchmarkReport.value = null
    }

    private fun computeRecommendedModel(ramGb: Float): Model {
        val catalog = OfficialModelCatalog.catalogModels
        return when {
            ramGb >= 15f -> catalog.firstOrNull { it.id == "gemma-3n-e4b-it" } ?: catalog.first()
            ramGb >= 11f -> catalog.firstOrNull { it.id == "gemma-4-e2b-it" } ?: catalog.first()
            ramGb >= 7f -> catalog.firstOrNull { it.id == "qwen2.5-1.5b-instruct" } ?: catalog.first()
            else -> catalog.firstOrNull { it.id == "gemma3-1b-it" } ?: catalog.first()
        }
    }

    private fun sortAndFilter(models: List<Model>, query: String, sort: ModelSortOption): List<Model> {
        val filtered = if (query.isBlank()) models else {
            models.filter { it.name.contains(query, ignoreCase = true) || it.description.contains(query, ignoreCase = true) }
        }

        return when (sort) {
            ModelSortOption.NAME -> filtered.sortedBy { it.name }
            ModelSortOption.SIZE -> filtered.sortedByDescending { it.fileSize }
            ModelSortOption.DATE -> filtered.sortedByDescending { it.createdAt }
            ModelSortOption.RAM -> filtered.sortedBy { it.recommendedRamGb }
        }
    }

    private fun queryDisplayName(uri: Uri): String {
        var name: String? = null
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) {
                name = cursor.getString(index)
            }
        }
        return name ?: "imported_model_${System.currentTimeMillis()}.gguf"
    }
}

/**
 * Presentation data class for Model Manager screen.
 */
data class ModelsData(
    val installedModels: List<Model> = emptyList(),
    val catalogModels: List<Model> = emptyList(),
    val selectedTab: ModelsTab = ModelsTab.INSTALLED,
    val searchQuery: String = "",
    val sortOption: ModelSortOption = ModelSortOption.NAME,
    val loadedModelId: String? = null,
    val loadingModelId: String? = null,
    val importing: Boolean = false,
    val errorMessage: String? = null,
    val storageStats: StorageStats? = null,
    val hardwareInfo: DeviceHardwareInfo? = null,
    val benchmarkReport: BenchmarkReport? = null,
    val isBenchmarking: Boolean = false,
    val remoteModels: List<RemoteModelSummary> = emptyList(),
    val isSearchingRemote: Boolean = false,
    val selectedRemoteDetails: RemoteModelDetails? = null,
    val readmeText: String? = null,
    val isLoadingDetails: Boolean = false,
    val recommendedModel: Model? = null,
    val showFirstLaunchDialog: Boolean = false,
    val engineState: EngineState = EngineState.Unloaded,
    val memoryStats: MemoryStats? = null
)
