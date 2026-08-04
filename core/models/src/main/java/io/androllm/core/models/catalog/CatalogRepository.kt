package io.androllm.core.models.catalog

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.androllm.core.common.Result
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Loads and owns the model catalog. On startup prefers a previously fetched catalog
 * persisted in app files, falling back to the catalog bundled as an asset. [refresh]
 * fetches the newest catalog from [CatalogRemoteSource] and persists it only after it
 * parses and validates cleanly, so a broken remote update never breaks the app.
 */
@Singleton
class CatalogRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val remoteSource: CatalogRemoteSource
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<CatalogState>(CatalogState.Loading)
    val state: StateFlow<CatalogState> = _state.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private val catalogFile: File
        get() = File(context.filesDir, "catalog/catalog_v1.json")

    init {
        scope.launch {
            loadInitial()
        }
    }

    private suspend fun loadInitial() {
        val bundled = runCatching { context.assets.open(BUNDLED_ASSET).bufferedReader().use { it.readText() } }.getOrNull()
        if (bundled == null) {
            _state.update { CatalogState.Failed("Catalog asset $BUNDLED_ASSET not found") }
            return
        }
        val saved = runCatching { catalogFile.takeIf { it.exists() }?.readText() }.getOrNull()
        _state.update { CatalogLoader.load(saved, bundled) }
    }

    /**
     * Fetches the latest catalog from the remote source and applies it when valid.
     * Returns the new state on success or a Result.Error explaining why nothing changed.
     */
    suspend fun refresh(): Result<CatalogState.Ready> {
        if (_refreshing.value) return Result.error("Refresh already in progress")
        _refreshing.value = true
        return try {
            when (val fetch = remoteSource.fetchCatalogJson()) {
                is Result.Error -> fetch
                is Result.Success -> {
                    when (val state = CatalogLoader.apply(fetch.data, CatalogSource.REMOTE)) {
                        is CatalogState.Ready -> {
                            CatalogLoader.persistText(catalogFile, fetch.data)
                            _state.update { state }
                            Result.success(state)
                        }
                        is CatalogState.Failed -> Result.error(state.message)
                        CatalogState.Loading -> Result.error("Unexpected catalog state")
                    }
                }
            }
        } finally {
            _refreshing.value = false
        }
    }

    /** Current models, or an empty list while loading / after failure. */
    fun currentModels(): List<CatalogModel> =
        (state.value as? CatalogState.Ready)?.catalog?.models.orEmpty()

    companion object {
        const val BUNDLED_ASSET = "catalog_v1.json"
    }
}
