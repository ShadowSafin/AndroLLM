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
import io.androllm.core.datastore.PreferencesDataStore
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
import io.androllm.engine.backend.BackendCapabilities
import io.androllm.engine.models.BackendType
import io.androllm.engine.models.EngineStats
import io.androllm.engine.models.MemoryStats
import io.androllm.engine.models.ModelLoadConfig
import io.androllm.engine.utils.LiteRtValidator
import io.androllm.feature.models.benchmark.BenchmarkReport
import io.androllm.feature.models.benchmark.ModelBenchmarker
import io.androllm.core.models.catalog.CatalogFilters
import io.androllm.core.models.catalog.CatalogModel
import io.androllm.core.models.catalog.CatalogRepository
import io.androllm.core.models.catalog.CatalogSortOption
import io.androllm.core.models.catalog.CatalogState
import io.androllm.core.models.catalog.ModelSearchEngine
import io.androllm.core.models.catalog.RecommendationEngine
import io.androllm.feature.models.catalog.toDownloadModel
import io.androllm.feature.models.downloader.ModelDownloadWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
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
    val downloadManager: io.androllm.feature.models.downloader.DownloadManager,
    private val catalogRepository: CatalogRepository,
    private val preferencesDataStore: PreferencesDataStore
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

    // Metadata-driven Catalog State
    private val _catalogFilters = MutableStateFlow(CatalogFilters())
    private val _catalogSort = MutableStateFlow(CatalogSortOption.TRENDING)
    private val _catalogRefreshError = MutableStateFlow<String?>(null)

    // Storage stats — computed OFF the main thread (walking the models
    // directories can take hundreds of ms when many GGUF files are present).
    private val _storageStats = MutableStateFlow<StorageStats?>(null)
    val storageStats: StateFlow<StorageStats?> = _storageStats.asStateFlow()

    /**
     * User-selected execution backend (AUTO / NPU / GPU / CPU), persisted.
     * AUTO (default) lets the engine pick the best available backend silently.
     */
    val backendPreference: StateFlow<BackendType> = preferencesDataStore.backendPreference
        .map { value -> runCatching { BackendType.valueOf(value) }.getOrDefault(BackendType.AUTO) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, BackendType.AUTO)

    fun setBackendPreference(type: BackendType) {
        viewModelScope.launch { preferencesDataStore.setBackendPreference(type.name) }
    }

    /** Startup hardware probe (SoC/GPU/NPU) — drives the adaptive backend selector. */
    val backendCapabilities: StateFlow<BackendCapabilities> = engineRepository.backendCapabilities

    val hardwareInfo: DeviceHardwareInfo = DeviceInfoCollector.collectDeviceInfo(context)

    val uiState: StateFlow<UiState<ModelsData>> = combine(
        combine(
            modelRepository.observeAllModels(),
            engineRepository.engineState,
            engineRepository.performanceStats,
            _selectedTab,
            _searchQuery
        ) { installedModels, engineState, performanceStats, tab, query ->
            listOf(installedModels, engineState, performanceStats, tab, query)
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
            _isSearchingRemote,
            engineRepository.backendCapabilities
        ) { benchReport, isBenchmarking, remoteModels, isSearchingRemote, caps ->
            listOf(benchReport, isBenchmarking, remoteModels, isSearchingRemote, caps)
        },
        combine(
            _selectedRemoteDetails,
            _readmeText,
            _isLoadingDetails,
            _storageStats
        ) { remoteDetails, readme, isLoadingDetails, storageStats ->
            listOf(remoteDetails, readme, isLoadingDetails, storageStats)
        },
        combine(
            catalogRepository.state,
            catalogRepository.refreshing,
            _catalogFilters,
            _catalogSort,
            _catalogRefreshError
        ) { catalogState, isRefreshing, filters, sort, refreshError ->
            listOf(catalogState, isRefreshing, filters, sort, refreshError)
        }
    ) { group1, group2, group3, group4, group5 ->
        @Suppress("UNCHECKED_CAST")
        val installedModels = group1[0] as List<Model>
        val engineState = group1[1] as EngineState
        @Suppress("UNCHECKED_CAST")
        val performanceStats = group1[2] as EngineStats?
        val tab = group1[3] as ModelsTab
        val query = group1[4] as String

        val sort = group2[0] as ModelSortOption
        val loadingId = group2[1] as String?
        val importing = group2[2] as Boolean
        val error = group2[3] as String?

        val benchReport = group3[0] as BenchmarkReport?
        val isBenchmarking = group3[1] as Boolean
        @Suppress("UNCHECKED_CAST")
        val remoteModels = group3[2] as List<RemoteModelSummary>
        val isSearchingRemote = group3[3] as Boolean
        val caps = group3[4] as BackendCapabilities

        val remoteDetails = group4[0] as RemoteModelDetails?
        val readme = group4[1] as String?
        val isLoadingDetails = group4[2] as Boolean
        val storageStats = group4[3] as StorageStats?

        @Suppress("UNCHECKED_CAST")
        val catalogState = group5[0] as CatalogState
        val isCatalogRefreshing = group5[1] as Boolean
        val catalogFilters = group5[2] as CatalogFilters
        val catalogSort = group5[3] as CatalogSortOption
        val catalogRefreshError = group5[4] as String?

        val loadedId = when (engineState) {
            is EngineState.Ready -> engineState.model.id
            is EngineState.Generating -> engineState.model.id
            else -> null
        }
        val memStats = (engineState as? EngineState.Ready)?.memoryStats
        val filteredInstalled = sortAndFilter(installedModels, query, sort)

        val allCatalogModels = (catalogState as? CatalogState.Ready)?.catalog?.models.orEmpty()
        val filteredCatalog = ModelSearchEngine.search(
            models = allCatalogModels,
            query = query,
            filters = catalogFilters,
            sort = catalogSort
        )
        val recommended = RecommendationEngine.recommend(allCatalogModels, hardwareInfo.totalRamGb, topN = 6)

        UiState.Success(
            ModelsData(
                installedModels = filteredInstalled,
                catalogState = catalogState,
                catalogModels = filteredCatalog,
                catalogCount = allCatalogModels.size,
                recommendedCatalogModels = recommended.map { it.model },
                catalogFilters = catalogFilters,
                catalogSort = catalogSort,
                isCatalogRefreshing = isCatalogRefreshing,
                catalogRefreshError = catalogRefreshError,
                selectedTab = tab,
                searchQuery = query,
                sortOption = sort,
                loadedModelId = loadedId,
                loadingModelId = loadingId,
                importing = importing,
                errorMessage = error,
                storageStats = storageStats,
                hardwareInfo = hardwareInfo,
                benchmarkReport = benchReport,
                isBenchmarking = isBenchmarking,
                remoteModels = remoteModels,
                isSearchingRemote = isSearchingRemote,
                selectedRemoteDetails = remoteDetails,
                readmeText = readme,
                isLoadingDetails = isLoadingDetails,
                recommendedModel = recommended.firstOrNull()?.model?.toDownloadModel(),
                showFirstLaunchDialog = installedModels.none { it.isDownloaded },
                engineState = engineState,
                memoryStats = memStats,
                inferenceTokensPerSecond = performanceStats?.tokensPerSecond ?: 0f,
                backendCapabilities = caps
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
        refreshStorageStats()
    }

    /**
     * Re-computes model storage usage on a background dispatcher (walking the
     * models directories can be slow with many GGUF files — never on main).
     * Never throws — a storage read must not take down the screen (or leak an
     * uncaught coroutine exception in tests with unusable mock File objects).
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

            // CRITICAL: loading runs on Default, never Main. engineRepository
            // .loadModel performs the model load AND the post-load coherence
            // self-test (a real inference probe) — on a Main-scope call that
            // would freeze the UI for the whole load. The header validation
            // read is included for the same reason.
            val result = withContext(Dispatchers.Default) {
                // LiteRT artifact gate: only a .litertlm container or a .tflite
                // flatbuffer can be handed to the LiteRT runtime. A stale GGUF
                // download or a truncated/renamed file is rejected here, before
                // the runtime wastes minutes and RAM on it. The LiteRT runtime
                // itself validates tokenizer/weights on load and the post-load
                // coherence probe verifies real output.
                val validation = LiteRtValidator.validateHeader(filePath)
                if (!validation.isValid) {
                    _errorMessage.value = "Validation Error: ${validation.errorMessage}"
                    _loadingModelId.value = null
                    modelRepository.updateLoadState(model.id, false, ModelStatus.ERROR)
                    return@withContext null
                }

                // Backend preference → explicit load request. AUTO leaves the
                // decision to the engine's startup probe (NPU → GPU → CPU with
                // silent fallback); explicit selections are honored verbatim
                // and fall back the same way when they cannot initialize.
                val loadConfig = when (backendPreference.value) {
                    BackendType.CPU -> ModelLoadConfig(backend = BackendType.CPU)
                    BackendType.GPU -> ModelLoadConfig(backend = BackendType.GPU)
                    BackendType.NPU -> ModelLoadConfig(backend = BackendType.NPU)
                    else -> ModelLoadConfig()
                }
                engineRepository.loadModel(model, loadConfig)
            }
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
                null -> Unit // early-return paths above already surfaced their error
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

    fun downloadModel(model: Model) {
        viewModelScope.launch {
            downloadManager.startDownload(model)
            selectTab(ModelsTab.DOWNLOADS)
        }
    }

    fun downloadModel(catalogModel: CatalogModel) {
        viewModelScope.launch {
            downloadManager.startDownload(catalogModel.toDownloadModel())
            selectTab(ModelsTab.DOWNLOADS)
        }
    }

    fun updateCatalogFilters(filters: CatalogFilters) {
        _catalogFilters.value = filters
    }

    fun updateCatalogSort(sort: CatalogSortOption) {
        _catalogSort.value = sort
    }

    fun refreshCatalog() {
        viewModelScope.launch {
            when (val result = catalogRepository.refresh()) {
                is Result.Success -> _catalogRefreshError.value = null
                is Result.Error -> _catalogRefreshError.value = result.exception.message
            }
        }
    }

    fun dismissCatalogRefreshError() {
        _catalogRefreshError.value = null
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

                    val validation = LiteRtValidator.validateHeader(target.absolutePath)
                    if (!validation.isValid) {
                        target.delete()
                        error("Header Validation Failed: ${validation.errorMessage}")
                    }

                    val format = when (validation.format) {
                        "litertlm" -> ModelFormat.LITERTLM
                        "tflite" -> ModelFormat.TFLITE
                        else -> ModelFormat.UNKNOWN
                    }

                    Model(
                        id = UUID.randomUUID().toString(),
                        name = safeName.removeSuffix(".litertlm").removeSuffix(".tflite"),
                        description = "Imported LiteRT model (${validation.format})",
                        filePath = target.absolutePath,
                        fileSize = target.length(),
                        format = format,
                        isDownloaded = true,
                        downloadStatus = DownloadStatus.DOWNLOADED,
                        status = ModelStatus.NOT_LOADED,
                        createdAt = System.currentTimeMillis(),
                        updatedAt = System.currentTimeMillis(),
                        addedDate = System.currentTimeMillis()
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
        return name ?: "imported_model_${System.currentTimeMillis()}.litertlm"
    }
}

/**
 * Presentation data class for Model Manager screen.
 */
data class ModelsData(
    val installedModels: List<Model> = emptyList(),
    val catalogState: CatalogState = CatalogState.Loading,
    val catalogModels: List<CatalogModel> = emptyList(),
    val catalogCount: Int = 0,
    val recommendedCatalogModels: List<CatalogModel> = emptyList(),
    val catalogFilters: CatalogFilters = CatalogFilters(),
    val catalogSort: CatalogSortOption = CatalogSortOption.TRENDING,
    val isCatalogRefreshing: Boolean = false,
    val catalogRefreshError: String? = null,
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
    val memoryStats: MemoryStats? = null,
    val inferenceTokensPerSecond: Float = 0f,
    val backendCapabilities: BackendCapabilities = BackendCapabilities.UNKNOWN
)
