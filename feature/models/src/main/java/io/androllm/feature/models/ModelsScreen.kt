package io.androllm.feature.models

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import io.androllm.core.common.UiState
import io.androllm.core.models.DownloadProgress
import io.androllm.core.models.DownloadStatus
import io.androllm.core.models.Model
import io.androllm.core.models.RemoteGgufFile
import io.androllm.core.models.RemoteModelDetails
import io.androllm.core.models.RemoteModelSummary
import io.androllm.core.models.catalog.CatalogCategory
import io.androllm.core.models.catalog.CatalogModel
import io.androllm.core.models.catalog.CatalogSortOption
import io.androllm.core.models.catalog.CatalogState
import io.androllm.core.utils.DeviceHardwareInfo
import io.androllm.core.utils.DeviceInfoCollector
import io.androllm.feature.models.benchmark.BenchmarkReport
import io.androllm.feature.models.benchmark.ModelBenchmarker
import io.androllm.engine.api.EngineState
import io.androllm.engine.models.MemoryStats
import io.androllm.core.ui.components.CloudAdaptiveNavigation
import io.androllm.core.ui.components.ModelWalletCard

/**
 * Model Manager Screen featuring Installed Models, Download Manager & Queue,
 * Official Catalog, Hugging Face Hub, Hardware Diagnostics, Benchmarking, and Compatibility Analysis.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelsScreen(
    navController: NavController,
    viewModel: ModelsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val data = (uiState as? UiState.Success)?.data ?: ModelsData()

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { viewModel.importModel(it) }
    }

    var sortMenuExpanded by remember { mutableStateOf(false) }

    val activeDownloads = remember(data.installedModels) {
        data.installedModels.filter { !it.isDownloaded }
    }

    io.androllm.core.ui.components.CloudAtmosphericBackground {
        CloudAdaptiveNavigation(
            currentRoute = io.androllm.core.navigation.Routes.MODELS,
            onTabSelected = { tab ->
                if (tab.route != io.androllm.core.navigation.Routes.MODELS) {
                    navController.navigate(tab.route)
                }
            },
            topBar = {
                TopAppBar(
                    title = { Text("Model Manager", fontWeight = FontWeight.Bold, color = io.androllm.core.ui.theme.DeskPaper) },
                    actions = {
                        IconButton(onClick = { sortMenuExpanded = true }) {
                            Icon(Icons.Default.Sort, contentDescription = "Sort models", tint = io.androllm.core.ui.theme.DeskInk)
                        }

                        DropdownMenu(
                            expanded = sortMenuExpanded,
                            onDismissRequest = { sortMenuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Sort by Name") },
                                onClick = { viewModel.updateSortOption(ModelSortOption.NAME); sortMenuExpanded = false }
                            )
                            DropdownMenuItem(
                                text = { Text("Sort by Size") },
                                onClick = { viewModel.updateSortOption(ModelSortOption.SIZE); sortMenuExpanded = false }
                            )
                            DropdownMenuItem(
                                text = { Text("Sort by RAM") },
                                onClick = { viewModel.updateSortOption(ModelSortOption.RAM); sortMenuExpanded = false }
                            )
                        }

                        IconButton(onClick = { importLauncher.launch(arrayOf("*/*")) }) {
                            Icon(Icons.Default.Folder, contentDescription = "Import GGUF", tint = io.androllm.core.ui.theme.DeskInk)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
                )
            },
        ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Search Bar
            OutlinedTextField(
                value = data.searchQuery,
                onValueChange = { viewModel.updateSearchQuery(it) },
                placeholder = { Text("Search models...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (data.searchQuery.isNotEmpty()) {
                        IconButton(onClick = { viewModel.updateSearchQuery("") }) {
                            Icon(Icons.Default.Clear, contentDescription = null)
                        }
                    }
                },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                shape = RoundedCornerShape(12.dp)
            )

            // Material 3 Scrollable Tabs
            androidx.compose.material3.ScrollableTabRow(
                selectedTabIndex = data.selectedTab.ordinal,
                edgePadding = 16.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Tab(
                    selected = data.selectedTab == ModelsTab.INSTALLED,
                    onClick = { viewModel.selectTab(ModelsTab.INSTALLED) },
                    text = { Text("Installed (${data.installedModels.count { it.isDownloaded }})") }
                )
                Tab(
                    selected = data.selectedTab == ModelsTab.DOWNLOADS,
                    onClick = { viewModel.selectTab(ModelsTab.DOWNLOADS) },
                    text = { Text("Downloads (${activeDownloads.size})") }
                )
                Tab(
                    selected = data.selectedTab == ModelsTab.CATALOG,
                    onClick = { viewModel.selectTab(ModelsTab.CATALOG) },
                    text = { Text("Catalog (${data.catalogCount})") }
                )
                Tab(
                    selected = data.selectedTab == ModelsTab.HUGGINGFACE,
                    onClick = { viewModel.selectTab(ModelsTab.HUGGINGFACE) },
                    text = { Text("HuggingFace 🤗") }
                )
                Tab(
                    selected = data.selectedTab == ModelsTab.DIAGNOSTICS,
                    onClick = { viewModel.selectTab(ModelsTab.DIAGNOSTICS) },
                    text = { Text("Hardware") }
                )
            }

            // Error Banner
            data.errorMessage?.let { error ->
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            // Tab Content
            when (data.selectedTab) {
                ModelsTab.INSTALLED -> InstalledModelsTab(
                    data = data,
                    viewModel = viewModel,
                    onImportClick = { importLauncher.launch(arrayOf("*/*")) }
                )
                ModelsTab.DOWNLOADS -> DownloadsTab(
                    activeDownloads = activeDownloads,
                    viewModel = viewModel
                )
                ModelsTab.CATALOG -> CatalogTab(
                    data = data,
                    viewModel = viewModel,
                    installedModels = data.installedModels
                )
                ModelsTab.HUGGINGFACE -> HuggingFaceTab(
                    remoteModels = data.remoteModels,
                    isSearching = data.isSearchingRemote,
                    onSelectModel = { summary -> viewModel.fetchRemoteDetails(summary.id) }
                )
                ModelsTab.DIAGNOSTICS -> HardwareDiagnosticsTab(
                    hardwareInfo = data.hardwareInfo ?: DeviceInfoCollector.collectDeviceInfo(context)
                )
            }
        }
    }

    // Remote Details Sheet
    data.selectedRemoteDetails?.let { details ->
        RemoteModelDetailsSheet(
            details = details,
            readmeText = data.readmeText,
            onDownloadGguf = { summary, file -> viewModel.downloadRemoteGguf(summary, file) },
            onDismiss = { viewModel.dismissRemoteDetails() }
        )
    }

    // Benchmark Report Dialog
    data.benchmarkReport?.let { report ->
        BenchmarkReportDialog(
            report = report,
            onDismiss = { viewModel.dismissBenchmarkReport() }
        )
    }
    }
}

@Composable
private fun InstalledModelsTab(
    data: ModelsData,
    viewModel: ModelsViewModel,
    onImportClick: () -> Unit
) {
    val installedOnly = remember(data.installedModels) {
        data.installedModels.filter { it.isDownloaded }
    }

    if (installedOnly.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Memory,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(48.dp)
                )
                Text(
                    text = "No Installed GGUF Models",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Import a local .gguf file from storage or download from Catalog or Hugging Face.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = onImportClick) {
                    Icon(Icons.Default.Folder, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Import GGUF Model")
                }
            }
        }
    } else {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Model Status Dashboard - shown when a model is active
            val engineState = data.engineState
            if (engineState !is EngineState.Unloaded) {
                item(key = "status_dashboard") {
                    ModelStatusDashboard(
                        engineState = engineState,
                        memoryStats = data.memoryStats,
                        onUnload = {
                            val loadedModel = installedOnly.find { it.id == data.loadedModelId }
                            loadedModel?.let { viewModel.unloadModel(it) }
                        }
                    )
                }
            }

            items(installedOnly, key = { it.id }) { model ->
                ModelWalletCard(
                    model = model,
                    isActive = model.id == data.loadedModelId,
                    isDownloaded = model.isDownloaded,
                    onLoadClick = {
                        if (model.id == data.loadedModelId) {
                            viewModel.unloadModel(model)
                        } else {
                            viewModel.loadModel(model)
                        }
                    },
                    onDownloadClick = { viewModel.downloadModel(model) },
                    menuItems = {
                        DropdownMenuItem(
                            text = { Text(if (model.isDefault) "Default Model" else "Set as Default") },
                            onClick = { viewModel.setDefaultModel(model) },
                            enabled = !model.isDefault
                        )
                        DropdownMenuItem(
                            text = { Text(if (model.isFavorite) "Remove from Favorites" else "Add to Favorites") },
                            onClick = { viewModel.toggleFavorite(model) },
                            leadingIcon = {
                                Icon(
                                    imageVector = if (model.isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                                    contentDescription = null
                                )
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Run Benchmark") },
                            onClick = { viewModel.runBenchmark(model) },
                            leadingIcon = { Icon(Icons.Default.Speed, contentDescription = null) }
                        )
                        DropdownMenuItem(
                            text = { Text("Delete Model", color = MaterialTheme.colorScheme.error) },
                            onClick = { viewModel.deleteModel(model) },
                            leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) }
                        )
                    }
                )
            }
        }
    }
}

@Composable
private fun DownloadsTab(
    activeDownloads: List<Model>,
    viewModel: ModelsViewModel
) {
    if (activeDownloads.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Download,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(48.dp)
                )
                Text(
                    text = "No Active Downloads",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Browse Catalog or Hugging Face Hub to download GGUF models.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
    } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            // Bulk Controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Download Queue (${activeDownloads.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { viewModel.pauseAllDownloads(activeDownloads) }) {
                        Text("Pause All")
                    }
                    TextButton(onClick = { viewModel.resumeAllDownloads(activeDownloads) }) {
                        Text("Resume All")
                    }
                    TextButton(onClick = { viewModel.cancelAllDownloads(activeDownloads) }) {
                        Text("Cancel All", color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(activeDownloads, key = { it.id }) { model ->
                    val progressState by viewModel.downloadManager.observeProgress(model.id)
                        .collectAsStateWithLifecycle(initialValue = null)

                    DownloadCard(
                        model = model,
                        progress = progressState,
                        onPause = { viewModel.pauseDownload(model.id) },
                        onResume = { viewModel.resumeDownload(model) },
                        onCancel = { viewModel.cancelDownload(model.id) },
                        onRetry = { viewModel.retryDownload(model) }
                    )
                }
            }
        }
    }
}

@Composable
private fun DownloadCard(
    model: Model,
    progress: DownloadProgress?,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit
) {
    val percent = progress?.progressPercent ?: 0
    val bytes = progress?.bytesDownloaded ?: 0L
    val total = progress?.totalBytes ?: model.fileSize
    val speed = progress?.speedMbps ?: 0f
    val eta = progress?.etaSeconds ?: 0L
    val status = progress?.status ?: model.downloadStatus

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = model.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                Spacer(modifier = Modifier.width(10.dp))

                StatusBadge(status = status)
            }

            Spacer(modifier = Modifier.height(8.dp))

            LinearProgressIndicator(
                progress = (percent / 100f).coerceIn(0f, 1f),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${bytes.formatSize()} / ${total.formatSize()} ($percent%)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )

                when (status) {
                    DownloadStatus.DOWNLOADING -> Text(
                        text = "${"%.1f".format(speed)} MB/s • ${eta}s left",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    DownloadStatus.ERROR -> Text(
                        text = progress?.errorMessage ?: "Download Failed (Check network / url)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    else -> {}
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                when (status) {
                    DownloadStatus.DOWNLOADING -> OutlinedButton(onClick = onPause, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Pause, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Pause")
                    }
                    DownloadStatus.PAUSED -> Button(onClick = onResume, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Resume")
                    }
                    DownloadStatus.ERROR -> Button(onClick = onRetry, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Retry")
                    }
                    else -> {}
                }

                if (status != DownloadStatus.DOWNLOADED) {
                    TextButton(onClick = onCancel, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Cancel, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Cancel", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

@Composable
private fun CatalogTab(
    data: ModelsData,
    viewModel: ModelsViewModel,
    installedModels: List<Model>
) {
    when (val state = data.catalogState) {
        is CatalogState.Loading -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        is CatalogState.Failed -> {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "Catalog unavailable",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = state.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = { viewModel.refreshCatalog() }) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Retry")
                }
            }
        }
        is CatalogState.Ready -> CatalogList(
            data = data,
            viewModel = viewModel,
            installedModels = installedModels
        )
    }
}

@Composable
private fun CatalogList(
    data: ModelsData,
    viewModel: ModelsViewModel,
    installedModels: List<Model>
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "catalog_header") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Model Catalog (${data.catalogCount})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                TextButton(
                    onClick = { viewModel.refreshCatalog() },
                    enabled = !data.isCatalogRefreshing
                ) {
                    if (data.isCatalogRefreshing) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Updating...")
                    } else {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Update")
                    }
                }
            }
        }

        data.catalogRefreshError?.let { error ->
            item(key = "catalog_error") {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { viewModel.dismissCatalogRefreshError() }) {
                            Icon(
                                Icons.Default.Cancel,
                                contentDescription = "Dismiss",
                                tint = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            }
        }

        if (data.recommendedCatalogModels.isNotEmpty()) {
            item(key = "rec_header") {
                Text(
                    text = "⭐ Recommended for your device",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            items(data.recommendedCatalogModels.take(4), key = { "rec_${it.id}" }) { model ->
                CatalogModelCard(
                    model = model,
                    installedModels = installedModels,
                    recommended = true,
                    onDownload = { viewModel.downloadModel(it) }
                )
            }
            item(key = "divider") {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }

        item(key = "sort_chips") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SORT_OPTIONS.forEach { option ->
                    FilterChip(
                        selected = data.catalogSort == option,
                        onClick = { viewModel.updateCatalogSort(option) },
                        label = { Text(option.label) }
                    )
                }
            }
        }

        item(key = "category_chips") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = data.catalogFilters.categories.isEmpty(),
                    onClick = { viewModel.updateCatalogFilters(data.catalogFilters.copy(categories = emptySet())) },
                    label = { Text("All") }
                )
                CATEGORY_FILTERS.forEach { category ->
                    FilterChip(
                        selected = category in data.catalogFilters.categories,
                        onClick = {
                            val current = data.catalogFilters.categories.toMutableSet()
                            if (!current.add(category)) current.remove(category)
                            viewModel.updateCatalogFilters(data.catalogFilters.copy(categories = current))
                        },
                        label = { Text(category.label) }
                    )
                }
            }
        }

        item(key = "results_header") {
            Text(
                text = "${data.catalogModels.size} models",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline
            )
        }

        if (data.catalogModels.isEmpty()) {
            item(key = "empty") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No models match your search.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        }

        items(data.catalogModels, key = { it.id }) { model ->
            CatalogModelCard(
                model = model,
                installedModels = installedModels,
                recommended = false,
                onDownload = { viewModel.downloadModel(it) }
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CatalogModelCard(
    model: CatalogModel,
    installedModels: List<Model>,
    recommended: Boolean,
    onDownload: (CatalogModel) -> Unit
) {
    val installed = installedModels.find { it.id == model.id }
    val isDownloaded = installed?.isDownloaded == true
    val isDownloading = installed != null && !installed.isDownloaded &&
        installed.downloadStatus != DownloadStatus.ERROR
    val isGated = model.isGated

    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentHeight(),
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (recommended) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
            } else {
                MaterialTheme.colorScheme.surfaceContainer
            }
        )
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val wide = maxWidth > 600.dp
                if (wide) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CatalogTitleBlock(
                            model = model,
                            recommended = recommended,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(20.dp))
                        CatalogModelAction(
                            isGated = isGated,
                            isDownloaded = isDownloaded,
                            isDownloading = isDownloading,
                            onDownload = { onDownload(model) }
                        )
                    }
                } else {
                    CatalogTitleBlock(model = model, recommended = recommended)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = model.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            if (model.tags.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    model.tags.forEach { tag ->
                        SuggestionChip(
                            onClick = {},
                            label = { Text(tag) },
                            shape = RoundedCornerShape(16.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (model.parameters.isNotBlank()) {
                    ModelMetaPill("${model.parameters} Params", leadingIcon = Icons.Default.Memory)
                }
                ModelMetaPill(model.sizeBytes.formatSize(), leadingIcon = Icons.Default.Storage)
                ModelMetaPill("${model.contextLength.coerceAtLeast(1) / 1000}K Context", leadingIcon = Icons.Default.History)
                ModelMetaPill("RAM ${"%.0f".format(model.minRamGb)}+ GB", leadingIcon = Icons.Default.Speed)
            }

            Spacer(modifier = Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f, fill = false),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Outlined.Download,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.outline
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = model.downloads.toString(),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Favorite,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = Color(0xFFF38BA8)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = model.likes.toString(),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }

                if (wideSize()) {
                    CatalogModelAction(
                        isGated = isGated,
                        isDownloaded = isDownloaded,
                        isDownloading = isDownloading,
                        onDownload = { onDownload(model) }
                    )
                }
            }

            if (!wideSize()) {
                Spacer(modifier = Modifier.height(12.dp))
                CatalogModelAction(
                    isGated = isGated,
                    isDownloaded = isDownloaded,
                    isDownloading = isDownloading,
                    onDownload = { onDownload(model) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

/**
 * Model identity block: name (max 2 lines, ellipsized) with the quantization
 * and recommendation chips laid out beside/below it — never overlapping.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CatalogTitleBlock(
    model: CatalogModel,
    recommended: Boolean,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(
            text = model.name,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(6.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (model.quantization.isNotBlank()) {
                AssistChip(
                    onClick = {},
                    label = {
                        Text(model.quantization, style = MaterialTheme.typography.labelMedium)
                    },
                    leadingIcon = {
                        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(14.dp))
                    }
                )
            }
            if (recommended) {
                AssistChip(
                    onClick = {},
                    label = { Text("★ Recommended") },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                )
            }
        }
        if (model.family.isNotBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = buildString {
                    append(model.family)
                    if (model.architecture.isNotBlank()) append(" • ${model.architecture}")
                    if (model.license.isNotBlank()) append(" • ${model.license}")
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Compact metadata pill (Params / Size / Context / RAM) sized by content.
 */
@Composable
private fun ModelMetaPill(
    text: String,
    leadingIcon: androidx.compose.ui.graphics.vector.ImageVector? = null
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (leadingIcon != null) {
                Icon(
                    imageVector = leadingIcon,
                    contentDescription = null,
                    modifier = Modifier.size(13.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(4.dp))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * The single action slot for a catalog card: Gated / Installed / Downloading /
 * Download. Always bottom-anchored on phones, side-anchored on wide screens.
 */
@Composable
private fun CatalogModelAction(
    isGated: Boolean,
    isDownloaded: Boolean,
    isDownloading: Boolean,
    onDownload: () -> Unit,
    modifier: Modifier = Modifier
) {
    when {
        isGated -> AssistChip(
            onClick = {},
            label = { Text("Gated") },
            modifier = modifier
        )
        isDownloaded -> OutlinedButton(
            onClick = {},
            enabled = false,
            modifier = modifier
        ) {
            Text("Installed")
        }
        isDownloading -> Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = modifier
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Downloading…", style = MaterialTheme.typography.labelLarge)
            }
        }
        else -> Button(
            onClick = onDownload,
            modifier = modifier
        ) {
            Icon(Icons.Default.CloudDownload, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Download")
        }
    }
}

/** Reads the current window width to swap the catalog card layout at ~600dp. */
@Composable
private fun wideSize(): Boolean {
    val configuration = LocalConfiguration.current
    return configuration.screenWidthDp >= 600
}

private val SORT_OPTIONS = listOf(
    CatalogSortOption.TRENDING,
    CatalogSortOption.DOWNLOADS,
    CatalogSortOption.LIKES,
    CatalogSortOption.NEWEST,
    CatalogSortOption.SIZE_DESC,
    CatalogSortOption.LEAST_RAM
)

private val CATEGORY_FILTERS = listOf(
    CatalogCategory.CHAT,
    CatalogCategory.REASONING,
    CatalogCategory.CODE,
    CatalogCategory.MATH,
    CatalogCategory.VISION,
    CatalogCategory.EMBEDDING,
    CatalogCategory.MULTILINGUAL,
    CatalogCategory.FUNCTION_CALLING,
    CatalogCategory.LIGHTWEIGHT
)

@Composable
private fun HuggingFaceTab(
    remoteModels: List<RemoteModelSummary>,
    isSearching: Boolean,
    onSelectModel: (RemoteModelSummary) -> Unit
) {
    if (isSearching) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    } else if (remoteModels.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No GGUF models found on Hugging Face Hub.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
        }
    } else {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(remoteModels, key = { it.id }) { remote ->
                Card(
                    onClick = { onSelectModel(remote) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = remote.name,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            AssistChip(onClick = {}, label = { Text(remote.family) })
                        }
                        Text(
                            text = "by ${remote.author}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.Download, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.outline)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("${remote.downloads}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Favorite, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color(0xFFF38BA8))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("${remote.likes}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RemoteModelDetailsSheet(
    details: RemoteModelDetails,
    readmeText: String?,
    onDownloadGguf: (RemoteModelSummary, RemoteGgufFile) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedSubTab by remember { mutableIntStateOf(0) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Text(text = details.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(text = "by ${details.author} • License: ${details.license}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(modifier = Modifier.height(12.dp))

            SecondaryTabRow(selectedTabIndex = selectedSubTab) {
                Tab(selected = selectedSubTab == 0, onClick = { selectedSubTab = 0 }, text = { Text("GGUF Files (${details.ggufFiles.size})") })
                Tab(selected = selectedSubTab == 1, onClick = { selectedSubTab = 1 }, text = { Text("README.md") })
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (selectedSubTab == 0) {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    details.ggufFiles.forEach { file ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(file.filename, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("Quant: ${file.quantization} • RAM: ${"%.0f".format(file.minRamGb)} GB+", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Button(
                                    onClick = {
                                        val summary = RemoteModelSummary(id = details.id, name = details.name, author = details.author)
                                        onDownloadGguf(summary, file)
                                    }
                                ) {
                                    Text("Download")
                                }
                            }
                        }
                    }
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(350.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = readmeText ?: "Loading README from Hugging Face...",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

@Composable
private fun HardwareDiagnosticsTab(hardwareInfo: DeviceHardwareInfo) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Device Hardware & Capability",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    DiagnosticRow("Device", "${hardwareInfo.manufacturer} ${hardwareInfo.deviceName}")
                    DiagnosticRow("Android Version", "Android ${hardwareInfo.androidVersion} (API ${hardwareInfo.apiLevel})")
                    DiagnosticRow("CPU Architecture", "${hardwareInfo.abi} (${hardwareInfo.cpuCores} Cores)")
                    DiagnosticRow("Total RAM", "${"%.2f".format(hardwareInfo.totalRamGb)} GB")
                    DiagnosticRow("Free Storage", (hardwareInfo.freeStorageBytes).formatSize())
                    DiagnosticRow("Vulkan Acceleration", if (hardwareInfo.isVulkanSupported) "🟢 Supported" else "🔴 Not Available")
                    DiagnosticRow("GPU Backend", "llama.cpp Vulkan Compute Shaders")
                    DiagnosticRow("GPU Offloading", "Automatic (Max Safe Layers)")
                }
            }
        }
    }
}

@Composable
private fun DiagnosticRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.End,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun StatusBadge(status: DownloadStatus) {
    val (label, color) = when (status) {
        DownloadStatus.DOWNLOADED -> "Installed" to io.androllm.core.ui.theme.LampGlow
        DownloadStatus.DOWNLOADING -> "Downloading" to io.androllm.core.ui.theme.LampAmber
        DownloadStatus.QUEUED -> "Queued" to io.androllm.core.ui.theme.DeskPaperDim
        DownloadStatus.PAUSED -> "Paused" to io.androllm.core.ui.theme.LampDeep
        DownloadStatus.ERROR -> "Failed" to MaterialTheme.colorScheme.error
        DownloadStatus.NOT_DOWNLOADED -> "Not Installed" to io.androllm.core.ui.theme.DeskInk
    }

    AssistChip(
        onClick = {},
        label = { Text(label, style = MaterialTheme.typography.labelSmall, color = color, fontWeight = FontWeight.Bold) },
        colors = AssistChipDefaults.assistChipColors(containerColor = color.copy(alpha = 0.15f))
    )
}

@Composable
private fun BenchmarkReportDialog(
    report: BenchmarkReport,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Benchmark Results: ${report.modelName}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Throughput: ${"%.1f".format(report.tokensPerSecond)} tok/s", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = MaterialTheme.colorScheme.primary)
                Text("Cold Load Time: ${report.loadTimeMs} ms")
                Text("Prompt Speed: ${report.promptTokens} tokens in ${report.promptTimeMs} ms")
                Text("Generation: ${report.generatedTokens} tokens in ${report.generationTimeMs} ms")
                Text("Peak RAM: ${"%.1f".format(report.peakRamMb)} MB")
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val json = ModelBenchmarker.exportToJson(report)
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Benchmark Report", json))
                    Toast.makeText(context, "Exported JSON report copied", Toast.LENGTH_SHORT).show()
                    onDismiss()
                }
            ) { Text("Copy JSON") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

private fun Long.formatSize(): String {
    return when {
        this < 1024 -> "$this B"
        this < 1024 * 1024 -> String.format(java.util.Locale.getDefault(), "%.1f KB", this / 1024.0)
        this < 1024 * 1024 * 1024 -> String.format(java.util.Locale.getDefault(), "%.1f MB", this / (1024.0 * 1024.0))
        else -> String.format(java.util.Locale.getDefault(), "%.1f GB", this / (1024.0 * 1024.0 * 1024.0))
    }
}

@Composable
private fun FirstLaunchRecommendationDialog(
    model: Model,
    ramGb: Float,
    isVulkan: Boolean,
    onDownload: () -> Unit,
    onChooseAnother: () -> Unit,
    onSkip: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onSkip,
        title = { Text("⭐ Recommended Initial Model") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "We detected your device has ${"%.1f".format(ramGb)} GB RAM${if (isVulkan) " & Vulkan GPU support" else ""}.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(4.dp))
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(text = model.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Text(text = model.description, style = MaterialTheme.typography.bodySmall)
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(text = "Download Size: ${model.fileSize.formatSize()}", style = MaterialTheme.typography.labelSmall)
                        Text(text = "RAM Usage: ~${"%.1f".format(model.minRamGb)} GB", style = MaterialTheme.typography.labelSmall)
                        Text(text = "Expected Speed: ${model.expectedTokSec}", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDownload) {
                Text("Download Recommended")
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onChooseAnother) {
                    Text("Catalog")
                }
                TextButton(onClick = onSkip) {
                    Text("Skip")
                }
            }
        }
    )
}

/**
 * Persistent model status dashboard showing engine state, memory telemetry,
 * backend info, and unload controls.
 */
@Composable
private fun ModelStatusDashboard(
    engineState: EngineState,
    memoryStats: MemoryStats?,
    onUnload: () -> Unit
) {
    val (statusLabel, statusColor, showProgress) = when (engineState) {
        is EngineState.Loading -> Triple(
            "Loading: ${engineState.stage}",
            Color(0xFF89B4FA),
            true
        )
        is EngineState.WarmingUp -> Triple(
            "Warming Up: ${engineState.step}",
            Color(0xFFF9E2AF),
            true
        )
        is EngineState.Ready -> Triple(
            "● Ready",
            Color(0xFFA6E3A1),
            false
        )
        is EngineState.Generating -> Triple(
            "● Generating (Prompt #${engineState.promptNumber})",
            Color(0xFF89B4FA),
            false
        )
        EngineState.Unloading -> Triple(
            "Unloading...",
            Color(0xFFFAB387),
            true
        )
        is EngineState.Failed -> Triple(
            "● Error: ${engineState.message}",
            Color(0xFFF38BA8),
            false
        )
        EngineState.Unloaded -> Triple(
            "No Model",
            Color(0xFF6C7086),
            false
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header with status and unload
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Memory,
                        contentDescription = null,
                        tint = statusColor,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "Engine Status",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                }

                if (engineState is EngineState.Ready || engineState is EngineState.Generating) {
                    OutlinedButton(
                        onClick = onUnload,
                        enabled = engineState !is EngineState.Generating
                    ) {
                        Text("Unload", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Status label
            Text(
                text = statusLabel,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = statusColor
            )

            // Progress bar during loading/warm-up
            if (showProgress) {
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                )
            }

            // Memory telemetry - shown when stats are available
            memoryStats?.let { stats ->
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "Memory",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        MemoryStatRow("Model RAM", "%.0f MB".format(stats.modelSizeMb()))
                        MemoryStatRow("Context", "%.0f MB".format(stats.contextSizeMb()))
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        MemoryStatRow("Peak", "%.0f MB".format(stats.peakMb()))
                        MemoryStatRow("Total", "%.0f MB".format(stats.totalNativeMb()))
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Backend & GPU Status
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val isVulkan = stats.backend == "vulkan"
                    AssistChip(
                        onClick = {},
                        label = {
                            Text(
                                text = if (isVulkan) "🟢 Vulkan" else "🔴 ${stats.backend.uppercase()}",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold
                            )
                        },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = if (isVulkan)
                                Color(0xFFA6E3A1).copy(alpha = 0.15f)
                            else
                                Color(0xFFF38BA8).copy(alpha = 0.15f)
                        )
                    )
                    if (stats.gpuLayersOffloaded > 0) {
                        AssistChip(
                            onClick = {},
                            label = {
                                Text(
                                    "GPU Layers: ${stats.gpuLayersDisplay}",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        )
                    }
                }

                if (stats.isGpuAccelerated) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = buildString {
                            append("GPU: ")
                            append(stats.gpuName.ifBlank { "Vulkan device" })
                            if (stats.gpuDriverVersion.isNotBlank()) append(" • Driver ${stats.gpuDriverVersion}")
                            if (stats.gpuApiVersion.isNotBlank()) append(" • Vulkan ${stats.gpuApiVersion}")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFA6E3A1)
                    )
                } else if (stats.backendReason.isNotBlank()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "CPU fallback: ${stats.backendReason}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFF38BA8)
                    )
                }

                // GPU Memory (if Vulkan)
                if (stats.isGpuAccelerated) {
                    Spacer(modifier = Modifier.height(4.dp))
                    MemoryStatRow("GPU allocated (est.)", "%.0f MB".format(stats.gpuMemoryAllocatedMb()))
                    MemoryStatRow("GPU free / total", "%.0f / %.0f MB".format(stats.gpuMemoryFreeMb(), stats.gpuMemoryTotalMb()))
                    MemoryStatRow("GPU peak", "%.0f MB • Buffers: %d".format(stats.gpuMemoryPeakMb(), stats.gpuBufferCount))
                    MemoryStatRow("KV cache", "%.0f MB (included in allocation)".format(stats.contextSizeMb()))
                    Text(
                        text = if (stats.gpuInferenceVerified) {
                            "Vulkan inference verified"
                        } else {
                            "Verifying Vulkan inference…"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (stats.gpuInferenceVerified) Color(0xFFA6E3A1) else MaterialTheme.colorScheme.outline
                    )
                }
            }

            // Ready state details
            if (engineState is EngineState.Ready) {
                if (engineState.promptCount > 0) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Prompts served: ${engineState.promptCount}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                if (engineState.loadedSinceMs > 0) {
                    val elapsedMin = (System.currentTimeMillis() - engineState.loadedSinceMs) / 60000
                    Text(
                        text = if (elapsedMin < 1) "Loaded just now" else "Loaded ${elapsedMin}m ago",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        }
    }
}

@Composable
private fun MemoryStatRow(label: String, value: String) {
    Row(
        modifier = Modifier.padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold
        )
    }
}
