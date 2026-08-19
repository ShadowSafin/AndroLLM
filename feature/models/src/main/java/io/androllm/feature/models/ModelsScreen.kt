package io.androllm.feature.models

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Clear
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
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
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
import io.androllm.core.models.catalog.CatalogSections
import io.androllm.core.models.catalog.CatalogSortOption
import io.androllm.core.models.catalog.CatalogState
import io.androllm.core.utils.DeviceHardwareInfo
import io.androllm.core.utils.DeviceInfoCollector
import io.androllm.feature.models.benchmark.BenchmarkReport
import io.androllm.feature.models.benchmark.ModelBenchmarker
import io.androllm.engine.api.EngineState
import io.androllm.engine.backend.BackendCapabilities
import io.androllm.engine.models.BackendType
import io.androllm.engine.models.MemoryStats
import io.androllm.core.ui.components.CloudAdaptiveNavigation
import io.androllm.core.ui.components.CloudGlassCard
import io.androllm.core.ui.components.ModelWalletCard
import io.androllm.core.ui.theme.DeskInk
import io.androllm.core.ui.theme.DeskWalnut
import io.androllm.core.ui.theme.DeskWalnutDeep
import io.androllm.core.ui.theme.LampAmber
import io.androllm.core.ui.theme.LampDeep
import io.androllm.core.ui.theme.ledger

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

    // One scroll state per tab, hoisted here so switching tabs (or changing
    // catalog filters / sort) keeps each list exactly where the user left it.
    val installedListState = rememberLazyListState()
    val downloadsListState = rememberLazyListState()
    val catalogListState = rememberLazyListState()
    val huggingFaceListState = rememberLazyListState()

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
                    title = { Text("Model Manager", fontWeight = FontWeight.Bold, color = MaterialTheme.ledger.deskPaper) },
                    actions = {
                        IconButton(onClick = { sortMenuExpanded = true }) {
                            Icon(Icons.Default.Sort, contentDescription = "Sort models", tint = MaterialTheme.ledger.deskInk)
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
                            Icon(Icons.Default.Folder, contentDescription = "Import LiteRT model", tint = MaterialTheme.ledger.deskInk)
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
                shape = RoundedCornerShape(16.dp)
            )

            // Parchment scrollable tabs — terracotta on the active tab, ink on the rest
            androidx.compose.material3.ScrollableTabRow(
                selectedTabIndex = data.selectedTab.ordinal,
                edgePadding = 20.dp,
                containerColor = Color.Transparent,
                contentColor = MaterialTheme.ledger.deskInk,
                divider = {},
                modifier = Modifier.fillMaxWidth()
            ) {
                Tab(
                    selected = data.selectedTab == ModelsTab.INSTALLED,
                    onClick = { viewModel.selectTab(ModelsTab.INSTALLED) },
                    text = { Text("Installed (${data.installedModels.count { it.isDownloaded }})") },
                    selectedContentColor = MaterialTheme.ledger.lampDeep,
                    unselectedContentColor = MaterialTheme.ledger.deskInk
                )
                Tab(
                    selected = data.selectedTab == ModelsTab.DOWNLOADS,
                    onClick = { viewModel.selectTab(ModelsTab.DOWNLOADS) },
                    text = { Text("Downloads (${activeDownloads.size})") },
                    selectedContentColor = MaterialTheme.ledger.lampDeep,
                    unselectedContentColor = MaterialTheme.ledger.deskInk
                )
                Tab(
                    selected = data.selectedTab == ModelsTab.CATALOG,
                    onClick = { viewModel.selectTab(ModelsTab.CATALOG) },
                    text = { Text("Catalog (${data.catalogCount})") },
                    selectedContentColor = MaterialTheme.ledger.lampDeep,
                    unselectedContentColor = MaterialTheme.ledger.deskInk
                )
                Tab(
                    selected = data.selectedTab == ModelsTab.HUGGINGFACE,
                    onClick = { viewModel.selectTab(ModelsTab.HUGGINGFACE) },
                    text = { Text("HuggingFace 🤗") },
                    selectedContentColor = MaterialTheme.ledger.lampDeep,
                    unselectedContentColor = MaterialTheme.ledger.deskInk
                )
                Tab(
                    selected = data.selectedTab == ModelsTab.DIAGNOSTICS,
                    onClick = { viewModel.selectTab(ModelsTab.DIAGNOSTICS) },
                    text = { Text("Hardware") },
                    selectedContentColor = MaterialTheme.ledger.lampDeep,
                    unselectedContentColor = MaterialTheme.ledger.deskInk
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
                    onImportClick = { importLauncher.launch(arrayOf("*/*")) },
                    listState = installedListState
                )
                ModelsTab.DOWNLOADS -> DownloadsTab(
                    activeDownloads = activeDownloads,
                    viewModel = viewModel,
                    listState = downloadsListState
                )
                ModelsTab.CATALOG -> CatalogTab(
                    data = data,
                    viewModel = viewModel,
                    installedModels = data.installedModels,
                    listState = catalogListState
                )
                ModelsTab.HUGGINGFACE -> HuggingFaceTab(
                    remoteModels = data.remoteModels,
                    isSearching = data.isSearchingRemote,
                    onSelectModel = { summary -> viewModel.fetchRemoteDetails(summary.id) },
                    listState = huggingFaceListState
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
    onImportClick: () -> Unit,
    listState: LazyListState
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
                    text = "No Installed LiteRT Models",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Import a local .litertlm / .tflite file from storage or download from the Catalog.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = onImportClick) {
                    Icon(Icons.Default.Folder, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Import LiteRT Model")
                }
            }
        }
    } else {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Execution Backend — adaptive selector. The NPU option appears
            // ONLY when the startup probe found a usable NPU delegate on this
            // device (silent NPU support: no NPU ⇒ no NPU option, exactly like
            // today). AUTO lets the engine pick NPU → GPU → CPU with silent
            // fallback. Persisted across launches.
            item(key = "backend_selector") {
                val preference by viewModel.backendPreference.collectAsStateWithLifecycle()
                BackendSelectorCard(
                    preference = preference,
                    capabilities = data.backendCapabilities,
                    onSelect = { viewModel.setBackendPreference(it) }
                )
            }

            // Model Status Dashboard - shown when a model is active
            val engineState = data.engineState
            if (engineState !is EngineState.Unloaded) {
                item(key = "status_dashboard") {
ModelStatusDashboard(
                        engineState = engineState,
                        memoryStats = data.memoryStats,
                        performanceStats = data.performanceStats,
                        onUnload = {
                            val loadedModel = installedOnly.find { it.id == data.loadedModelId }
                            loadedModel?.let { viewModel.unloadModel(it) }
                        },
                        onRetry = { viewModel.retryFailedLoad() }
                    )
                }
            }

            items(installedOnly, key = { it.id }) { model ->
                ModelWalletCard(
                    model = model,
                    isActive = model.id == data.loadedModelId,
                    isDownloaded = model.isDownloaded,
                    activeContextLength = if (model.id == data.loadedModelId) {
                        (engineState as? EngineState.Ready)?.model?.contextLength
                            ?: (engineState as? EngineState.Generating)?.model?.contextLength
                    } else {
                        null
                    },
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
    viewModel: ModelsViewModel,
    listState: LazyListState
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
                    text = "Browse the Catalog to download LiteRT models.",
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
                state = listState,
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
    val total = progress?.totalBytes?.takeIf { it > 0 } ?: model.fileSize
    val speed = progress?.speedBytesPerSec ?: 0f
    val eta = progress?.etaSeconds ?: 0L
    val status = progress?.status ?: model.downloadStatus

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.ledger.deskWalnut)
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

            // Unknown total size (chunked transfer, no Content-Length): show
            // an indeterminate bar instead of a misleading 0% determinate one.
            if (status == DownloadStatus.DOWNLOADING && total <= 0L) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                )
            } else {
                LinearProgressIndicator(
                    progress = (percent / 100f).coerceIn(0f, 1f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                )
            }

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
                        text = "${speed.formatSpeed()} • ${if (eta > 0) "${eta}s left" else "Calculating..."}",
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
    installedModels: List<Model>,
    listState: LazyListState
) {
    when (val state = data.catalogState) {
        is CatalogState.Loading -> CatalogLoadingPlaceholder()
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
            installedModels = installedModels,
            listState = listState
        )
    }
}

@Composable
private fun CatalogList(
    data: ModelsData,
    viewModel: ModelsViewModel,
    installedModels: List<Model>,
    listState: LazyListState
) {
    // The recommended row is a device-based shortcut, meaningful only when
    // browsing the full catalog. While searching or filtering it would show
    // stale, off-query models — hide it then. The models it shows are drawn
    // from the SAME filtered source and excluded from the main list below,
    // so every catalog model appears exactly once.
    val showRecommended = data.recommendedCatalogModels.isNotEmpty() &&
        data.searchQuery.isBlank() &&
        data.catalogFilters.sections.isEmpty() &&
        !data.catalogInstalledOnly &&
        !data.catalogDownloadedOnly
    val recommendedShown = if (showRecommended) data.recommendedCatalogModels.take(4) else emptyList()
    val recommendedIds = remember(data.recommendedCatalogModels) {
        data.recommendedCatalogModels.map { it.id }.toSet()
    }
    val visibleModels = if (showRecommended) {
        data.catalogModels.filter { it.id !in recommendedIds }
    } else {
        data.catalogModels
    }

    LazyColumn(
        state = listState,
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

        if (recommendedShown.isNotEmpty()) {
            item(key = "rec_header") {
                Text(
                    text = "⭐ Recommended for your device",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            items(recommendedShown, key = { "rec_${it.id}" }) { model ->
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

        item(key = "filter_chips") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = data.catalogFilters.sections.isEmpty() &&
                        !data.catalogInstalledOnly && !data.catalogDownloadedOnly,
                    onClick = {
                        viewModel.updateCatalogFilters(data.catalogFilters.copy(sections = emptySet()))
                        viewModel.updateCatalogInstalledOnly(false)
                        viewModel.updateCatalogDownloadedOnly(false)
                    },
                    label = { Text("All") }
                )
                CatalogSections.ALL.forEach { section ->
                    FilterChip(
                        selected = section in data.catalogFilters.sections,
                        onClick = {
                            val current = data.catalogFilters.sections.toMutableSet()
                            if (!current.add(section)) current.remove(section)
                            viewModel.updateCatalogFilters(data.catalogFilters.copy(sections = current))
                        },
                        label = { Text(section) }
                    )
                }
                FilterChip(
                    selected = data.catalogInstalledOnly,
                    onClick = { viewModel.updateCatalogInstalledOnly(!data.catalogInstalledOnly) },
                    label = { Text("Installed") }
                )
                FilterChip(
                    selected = data.catalogDownloadedOnly,
                    onClick = { viewModel.updateCatalogDownloadedOnly(!data.catalogDownloadedOnly) },
                    label = { Text("Downloaded") }
                )
            }
        }

        item(key = "results_header") {
            Text(
                text = "${visibleModels.size + recommendedShown.size} models",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline
            )
        }

        if (visibleModels.isEmpty()) {
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

        items(visibleModels, key = { it.id }) { model ->
            CatalogModelCard(
                model = model,
                installedModels = installedModels,
                recommended = false,
                onDownload = { viewModel.downloadModel(it) }
            )
        }
    }
}

/**
 * Catalog model card — Material 3, warm light: white surface, soft shadow,
 * orange accent, compact emoji pills and a prominent download action.
 * Hierarchy: name / family • author / description / badges / meta / action.
 */
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
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.ledger.deskWalnut
        ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val wide = maxWidth > 600.dp
                if (wide) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Bottom
                    ) {
                        CatalogCardBody(
                            model = model,
                            recommended = recommended,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(20.dp))
                        CatalogModelAction(
                            model = model,
                            isGated = isGated,
                            isDownloaded = isDownloaded,
                            isDownloading = isDownloading,
                            onDownload = { onDownload(model) }
                        )
                    }
                } else {
                    CatalogCardBody(model = model, recommended = recommended)
                }
            }

            if (!wideSize()) {
                Spacer(modifier = Modifier.height(14.dp))
                CatalogModelAction(
                    model = model,
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
 * Card body: name (large, bold), family • author, description (3 lines),
 * compact badge pills and the Memory / Context / Quantization meta row.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CatalogCardBody(
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
        val subtitle = buildList {
            if (model.family.isNotBlank()) add(model.family)
            if (model.author.isNotBlank()) add(model.author)
            if (model.family.isBlank() && model.architecture.isNotBlank()) add(model.architecture)
        }.joinToString(" • ")
        if (subtitle.isNotBlank()) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.ledger.deskInk,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = model.description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )

        val badgeList = buildList {
            if (recommended) add("⭐ Recommended")
            addAll(model.badges)
        }
        if (badgeList.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                badgeList.forEach { badge -> CatalogBadgePill(badge) }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ModelMetaPill("RAM ${"%.0f".format(model.minRamGb)}+ GB", leadingIcon = Icons.Default.Speed)
            ModelMetaPill("${model.contextLength.coerceAtLeast(1) / 1000}K Context", leadingIcon = Icons.Default.History)
            if (model.quantization.isNotBlank()) {
                ModelMetaPill(model.quantization, leadingIcon = Icons.Default.Memory)
            }
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
                        tint = MaterialTheme.ledger.lampAmber
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = model.likes.toString(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
            if (model.license.isNotBlank()) {
                Text(
                    text = model.license,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.End
                )
            }
        }
    }
}

/**
 * Compact metadata pill (Memory / Context / Quantization) sized by content.
 */
@Composable
private fun ModelMetaPill(
    text: String,
    leadingIcon: androidx.compose.ui.graphics.vector.ImageVector? = null
) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.ledger.deskWalnutDeep
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (leadingIcon != null) {
                Icon(
                    imageVector = leadingIcon,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.ledger.lampDeep
                )
                Spacer(modifier = Modifier.width(5.dp))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.ledger.deskInk,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** Tint pair for a compact badge pill: soft container + readable content color. */
private data class BadgeTint(val container: Color, val content: Color)

private val RecommendationGreen = Color(0xFF4CAF50)
private val RecommendationGreenDeep = Color(0xFF2E7D32)

/** Per-badge soft tints — orange accent, green recommendation/NPU, subtle hues. */
@Composable
private fun badgeTint(badge: String): BadgeTint = when {
    badge.contains("Recommended") || badge.contains("NPU") ->
        BadgeTint(RecommendationGreen.copy(alpha = 0.14f), RecommendationGreenDeep)
    badge.contains("Trending") || badge.contains("Vulkan") ->
        BadgeTint(MaterialTheme.ledger.lampAmber.copy(alpha = 0.16f), MaterialTheme.ledger.lampDeep)
    badge.contains("Fast") || badge.contains("Beginner") || badge.contains("Low RAM") ->
        BadgeTint(Color(0xFFDCEBFF), Color(0xFF2F6FDB))
    badge.contains("Reasoning") || badge.contains("Agentic") || badge.contains("Memory") ->
        BadgeTint(Color(0xFFEFE6FF), Color(0xFF7A4FD0))
    badge.contains("Speech") || badge.contains("Tool Calling") ->
        BadgeTint(Color(0xFFDCF5F0), Color(0xFF0E8A72))
    badge.contains("Vision") || badge.contains("Multimodal") || badge.contains("Code") ->
        BadgeTint(Color(0xFFE5E9FF), Color(0xFF4056D6))
    badge.contains("Medical") ->
        BadgeTint(Color(0xFFFFE7E7), Color(0xFFC0392B))
    badge.contains("Mobile Optimized") ->
        BadgeTint(Color(0xFFDFF6FA), Color(0xFF007E93))
    badge.contains("Embedding") ->
        BadgeTint(Color(0xFFF1E8FC), Color(0xFF7B1FA2))
    badge.contains("Multilingual") || badge.contains("Translation") ->
        BadgeTint(Color(0xFFDCEBFF), Color(0xFF2F6FDB))
    else -> BadgeTint(MaterialTheme.ledger.deskWalnutDeep, MaterialTheme.ledger.deskInk)
}

/** Compact pill badge with emoji + label and a per-type soft tint. */
@Composable
private fun CatalogBadgePill(badge: String) {
    val tint = badgeTint(badge)
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = tint.container,
        contentColor = tint.content
    ) {
        Text(
            text = badge,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

/**
 * The single action slot for a catalog card: Gated / Installed / Downloading /
 * Download. The download button carries the quantization and file size.
 */
@Composable
private fun CatalogModelAction(
    model: CatalogModel,
    isGated: Boolean,
    isDownloaded: Boolean,
    isDownloading: Boolean,
    onDownload: () -> Unit,
    modifier: Modifier = Modifier
) {
    val subtitle = buildList {
        if (model.quantization.isNotBlank()) add(model.quantization)
        add(model.sizeBytes.formatSize())
    }.joinToString(" • ")

    val target = when {
        isGated -> CatalogActionState.GATED
        isDownloaded -> CatalogActionState.INSTALLED
        isDownloading -> CatalogActionState.DOWNLOADING
        else -> CatalogActionState.DOWNLOAD
    }
    AnimatedContent(
        targetState = target,
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        label = "catalogAction"
    ) { state ->
        when (state) {
            CatalogActionState.GATED -> AssistChip(
                onClick = {},
                label = { Text("Gated") },
                modifier = modifier
            )
            CatalogActionState.INSTALLED -> OutlinedButton(
                onClick = {},
                enabled = false,
                modifier = modifier
            ) {
                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Installed")
            }
            CatalogActionState.DOWNLOADING -> Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = modifier
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Downloading…", style = MaterialTheme.typography.labelLarge)
                }
            }
            CatalogActionState.DOWNLOAD -> Button(
                onClick = onDownload,
                modifier = modifier
            ) {
                Icon(
                    Icons.Default.Download,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(horizontalAlignment = Alignment.Start) {
                    Text("Download", style = MaterialTheme.typography.labelLarge)
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f),
                        maxLines = 1
                    )
                }
            }
        }
    }
}

private enum class CatalogActionState { GATED, INSTALLED, DOWNLOADING, DOWNLOAD }

/** Reads the current window width to swap the catalog card layout at ~600dp. */
@Composable
private fun wideSize(): Boolean {
    val configuration = LocalConfiguration.current
    return configuration.screenWidthDp >= 600
}

/**
 * Skeleton cards shown while the catalog loads — soft pulsing placeholders.
 */
@Composable
private fun CatalogLoadingPlaceholder() {
    val pulse = rememberInfiniteTransition(label = "catalogSkeleton").animateFloat(
        initialValue = 0.45f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 700),
            repeatMode = RepeatMode.Reverse
        ),
        label = "skeletonAlpha"
    )
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(4) {
            ElevatedCard(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.ledger.deskWalnut),
                elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    SkeletonBar(fraction = 0.6f, barHeight = 22.dp, alpha = pulse.value)
                    Spacer(modifier = Modifier.height(10.dp))
                    SkeletonBar(fraction = 0.35f, barHeight = 13.dp, alpha = pulse.value)
                    Spacer(modifier = Modifier.height(16.dp))
                    SkeletonBar(fraction = 1f, barHeight = 12.dp, alpha = pulse.value)
                    Spacer(modifier = Modifier.height(6.dp))
                    SkeletonBar(fraction = 0.8f, barHeight = 12.dp, alpha = pulse.value)
                    Spacer(modifier = Modifier.height(16.dp))
                    SkeletonBar(fraction = 0.72f, barHeight = 24.dp, alpha = pulse.value)
                    Spacer(modifier = Modifier.height(12.dp))
                    SkeletonBar(fraction = 0.5f, barHeight = 26.dp, alpha = pulse.value)
                    Spacer(modifier = Modifier.height(16.dp))
                    SkeletonBar(fraction = 0.42f, barHeight = 44.dp, alpha = pulse.value)
                }
            }
        }
    }
}

/** A single rounded placeholder block inside a skeleton card. */
@Composable
private fun SkeletonBar(fraction: Float, barHeight: Dp, alpha: Float) {
    Box(
        modifier = Modifier
            .fillMaxWidth(fraction)
            .height(barHeight)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.ledger.deskWalnutDeep.copy(alpha = alpha))
    )
}

private val SORT_OPTIONS = listOf(
    CatalogSortOption.DOWNLOADS,   // Popular
    CatalogSortOption.SIZE_ASC,    // Smallest
    CatalogSortOption.SIZE_DESC,   // Largest
    CatalogSortOption.FASTEST,     // Fastest
    CatalogSortOption.NEWEST,      // Newest
    CatalogSortOption.RECOMMENDED  // Recommended
)

@Composable
private fun HuggingFaceTab(
    remoteModels: List<RemoteModelSummary>,
    isSearching: Boolean,
    onSelectModel: (RemoteModelSummary) -> Unit,
    listState: LazyListState
) {
    if (isSearching) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    } else if (remoteModels.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No LiteRT models found on Hugging Face Hub.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
        }
    } else {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(remoteModels, key = { it.id }) { remote ->
                Card(
                    onClick = { onSelectModel(remote) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.ledger.deskWalnut)
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
                                Icon(Icons.Default.Favorite, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color(0xFFE0A489))
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

            SecondaryTabRow(
                selectedTabIndex = selectedSubTab,
                containerColor = Color.Transparent,
                contentColor = MaterialTheme.ledger.deskInk
            ) {
                Tab(
                    selected = selectedSubTab == 0,
                    onClick = { selectedSubTab = 0 },
                    text = { Text("Model Files (${details.ggufFiles.size})") },
                    selectedContentColor = MaterialTheme.ledger.lampDeep,
                    unselectedContentColor = MaterialTheme.ledger.deskInk
                )
                Tab(
                    selected = selectedSubTab == 1,
                    onClick = { selectedSubTab = 1 },
                    text = { Text("README.md") },
                    selectedContentColor = MaterialTheme.ledger.lampDeep,
                    unselectedContentColor = MaterialTheme.ledger.deskInk
                )
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
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.ledger.deskWalnutDeep)
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
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.ledger.deskWalnut)
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
                    DiagnosticRow("GPU Backend", "LiteRT GPU delegate")
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
        DownloadStatus.DOWNLOADED -> "Installed" to MaterialTheme.ledger.lampDeep
        DownloadStatus.DOWNLOADING -> "Downloading" to MaterialTheme.ledger.lampAmber
        DownloadStatus.QUEUED -> "Queued" to MaterialTheme.ledger.deskPaperDim
        DownloadStatus.PAUSED -> "Paused" to MaterialTheme.ledger.lampDeep
        DownloadStatus.ERROR -> "Failed" to MaterialTheme.colorScheme.error
        DownloadStatus.NOT_DOWNLOADED -> "Not Installed" to MaterialTheme.ledger.deskInk
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

/**
 * Formats bytes/sec into a human-readable speed string using binary units.
 *
 * Conversion rules:
 *   < 1024 B     -> "512 B/s"
 *   < 1024 KB    -> "845 KB/s"
 *   < 1024 MB    -> "9.82 MB/s"
 *   >= 1024 MB   -> "1.50 GB/s"
 */
private fun Float.formatSpeed(): String {
    return when {
        this < 1024f -> String.format(java.util.Locale.getDefault(), "%.0f B/s", this)
        this < 1024f * 1024f -> String.format(java.util.Locale.getDefault(), "%.0f KB/s", this / 1024f)
        this < 1024f * 1024f * 1024f -> String.format(java.util.Locale.getDefault(), "%.2f MB/s", this / (1024f * 1024f))
        else -> String.format(java.util.Locale.getDefault(), "%.2f GB/s", this / (1024f * 1024f * 1024f))
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
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.ledger.deskWalnut)
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
 * Adaptive execution-backend selector. The NPU option is offered ONLY when
 * the startup probe found a usable NPU delegate on this device
 * ([BackendCapabilities.npuOptionVisible]) — on any other device the card
 * shows Auto / GPU / CPU, i.e. exactly today's options (silent NPU support:
 * no NPU detected ⇒ no NPU option, no disabled buttons, no banners).
 *
 * AUTO lets the engine pick NPU → GPU → CPU at load with silent fallback;
 * explicit selections are honored and fall back the same way when the
 * backend cannot initialize on this device.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BackendSelectorCard(
    preference: BackendType,
    capabilities: BackendCapabilities,
    onSelect: (BackendType) -> Unit
) {
    CloudGlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Execution Backend",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "AUTO picks the fastest accelerator on this device (NPU → GPU → CPU) with silent fallback.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
            if (capabilities.npuOptionVisible) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = buildString {
                        append("NPU detected: ")
                        append(capabilities.npuVendor ?: "NPU")
                        if (!capabilities.npuAccelerator.isNullOrBlank()) {
                            append(" · ")
                            append(capabilities.npuAccelerator)
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val options = buildList {
                    add(BackendType.AUTO to "Auto")
                    if (capabilities.npuOptionVisible) add(BackendType.NPU to "NPU")
                    add(BackendType.GPU to "GPU")
                    add(BackendType.CPU to "CPU")
                }
                options.forEach { (type, label) ->
                    FilterChip(
                        selected = preference == type,
                        onClick = { onSelect(type) },
                        label = { Text(label) },
                        shape = RoundedCornerShape(16.dp)
                    )
                }
            }
        }
    }
}

/**
 * Persistent model status dashboard showing engine state, memory telemetry,
 * backend info, and unload controls.
 */
@Composable
private fun ModelStatusDashboard(
    engineState: EngineState,
    memoryStats: MemoryStats?,
    performanceStats: io.androllm.engine.models.EngineStats?,
    onUnload: () -> Unit,
    onRetry: () -> Unit = {}
) {
    val (statusLabel, statusColor, showProgress) = when (engineState) {
        is EngineState.Loading -> Triple(
            "Loading: ${engineState.stage}",
            Color(0xFFE69D81),
            true
        )
        is EngineState.WarmingUp -> Triple(
            "Warming Up: ${engineState.step}",
            Color(0xFFE0A33D),
            true
        )
        is EngineState.Ready -> Triple(
            "🟢 Engine Ready",
            Color(0xFF52C41A),
            false
        )
        is EngineState.Generating -> Triple(
            "● Generating (Prompt #${engineState.promptNumber})",
            Color(0xFFD97757),
            false
        )
        EngineState.Unloading -> Triple(
            "Unloading...",
            Color(0xFFE0A489),
            true
        )
        is EngineState.Failed -> Triple(
            "● Error: ${engineState.message}",
            Color(0xFFC7442F),
            false
        )
        EngineState.Unloaded -> Triple(
            "No Model",
            Color(0xFF8F8D87),
            false
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.ledger.deskWalnut
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

            // Failed-load diagnostics: an actionable suggestion under the error
            // and a Retry button when the failure can succeed on a second
            // attempt (corrupted download, transient backend init failure).
            if (engineState is EngineState.Failed) {
                engineState.suggestion?.let { suggestion ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = suggestion,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (engineState.retryable) {
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedButton(onClick = onRetry) {
                        Text("Retry Load", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }

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
                        MemoryStatRow("Model artifact", formatBytesOrUnavailable(stats.modelSizeBytes))
                        MemoryStatRow("Process RAM (PSS)", formatBytesOrUnavailable(stats.processPssBytes))
                        MemoryStatRow("Native heap", formatBytesOrUnavailable(stats.nativeHeapAllocatedBytes))
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        MemoryStatRow("Java heap", formatBytesOrUnavailable(stats.javaHeapUsedBytes))
                        MemoryStatRow("Peak process RAM", formatBytesOrUnavailable(stats.peakMemoryBytes))
                        MemoryStatRow("Total model memory", formatBytesOrUnavailable(stats.totalRuntimeBytes))
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // ── Execution Backend — derived from RUNTIME state only (the
                // Vulkan correctness self-test never determines the active backend) ──
                Text(
                    text = "Execution Backend",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(4.dp))
                val isNpu = stats.backend == "npu"
                val npuModel = (engineState as? EngineState.Ready)?.model
                    ?: (engineState as? EngineState.Generating)?.model
                val successGreen = Color(0xFF52C41A)
                LedgerStatRow(
                    "NPU",
                    if (isNpu) "✓ ${npuModel?.accelerator?.ifBlank { "NPU" } ?: "NPU"}" else "Not active",
                    valueColor = if (isNpu) successGreen else MaterialTheme.colorScheme.outline
                )
                LedgerStatRow(
                    "GPU",
                    if (stats.isGpuAccelerated) "${stats.gpuBackendLabel} ✓" else "Not active",
                    valueColor = if (stats.isGpuAccelerated) successGreen else MaterialTheme.colorScheme.outline
                )
                LedgerStatRow("CPU", "Host ✓", valueColor = successGreen)
                LedgerStatRow(
                    "Mode",
                    if (isNpu) "NPU only" else stats.executionMode,
                    valueColor = if (isNpu || stats.isGpuAccelerated) successGreen else MaterialTheme.ledger.lampAmber
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Backend status pill
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val isVulkan = stats.isGpuAccelerated
                    val activeLabel = when {
                        isNpu -> "🟢 NPU" + (npuModel?.vendor?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "")
                        isVulkan -> "🟢 ${stats.gpuBackendLabel}"
                        else -> "🟡 CPU"
                    }
                    AssistChip(
                        onClick = {},
                        label = {
                            Text(
                                text = activeLabel,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold
                            )
                        },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = if (isNpu || isVulkan)
                                Color(0xFF52C41A).copy(alpha = 0.15f)
                            else
                                Color(0xFFE0A33D).copy(alpha = 0.18f)
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

                if (isNpu) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = buildString {
                            append("NPU: ")
                            append(npuModel?.accelerator?.ifBlank { "NPU" } ?: "NPU")
                            npuModel?.vendor?.takeIf { it.isNotBlank() }?.let { append(" • $it") }
                            npuModel?.delegate?.takeIf { it.isNotBlank() }?.let { append(" • $it") }
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = successGreen
                    )
                }
                if (stats.isGpuAccelerated) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = buildString {
                            append("GPU: ")
                            append(stats.gpuName.ifBlank { stats.gpuBackendLabel.ifBlank { "GPU" } })
                            if (stats.gpuDriverVersion.isNotBlank()) append(" • Driver ${stats.gpuDriverVersion}")
                            if (stats.gpuApiVersion.isNotBlank()) append(" • API ${stats.gpuApiVersion}")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = successGreen
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // ── Runtime statistics ──
                Text(
                    text = "Runtime",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(4.dp))
                if (isNpu) {
                    LedgerStatRow("NPU", npuModel?.delegate?.ifBlank { "LiteRT Delegate" } ?: "LiteRT Delegate")
                    LedgerStatRow("Accelerator", npuModel?.accelerator?.ifBlank { UNAVAILABLE_ON_DEVICE } ?: UNAVAILABLE_ON_DEVICE)
                    LedgerStatRow("Vendor", npuModel?.vendor?.ifBlank { UNAVAILABLE_ON_DEVICE } ?: UNAVAILABLE_ON_DEVICE)
                }
                LedgerStatRow(
                    "LiteRT delegate",
                    when {
                        isNpu -> npuModel?.delegate?.ifBlank { "LiteRT Delegate" } ?: "LiteRT Delegate"
                        stats.isGpuAccelerated -> "LiteRT GPU active"
                        stats.backend == "cpu" -> "XNNPACK CPU active"
                        else -> UNAVAILABLE_ON_DEVICE
                    }
                )
                // LiteRT-LM 0.16's public API has no Vulkan delegate or
                // Vulkan allocator surface. Do not relabel its GPU delegate
                // as Vulkan or fabricate a layer/buffer split.
LedgerStatRow("Vulkan delegate", UNAVAILABLE_ON_DEVICE)
                LedgerStatRow("KV cache", formatKvCacheTokensOrUnavailable(stats.kvCacheTokens))
                LedgerStatRow("Tokenizer memory", UNAVAILABLE_ON_DEVICE)
                val contextLen = ((engineState as? EngineState.Ready)?.model
                    ?: (engineState as? EngineState.Generating)?.model)
                    ?.contextLength
                LedgerStatRow(
                    "Context",
                    contextLen?.takeIf { it > 0 }?.let(::formatContextLength) ?: UNAVAILABLE_ON_DEVICE
                )
                LedgerStatRow("Live process RAM", formatBytesOrUnavailable(stats.totalRuntimeBytes))
                LedgerStatRow(
                    "Last decode speed",
                    formatSpeedOrUnavailable(performanceStats?.decodeTokensPerSecond ?: performanceStats?.tokensPerSecond ?: 0f)
                )

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Inference",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(4.dp))
                LedgerStatRow("Average tokens/sec", formatSpeedOrUnavailable(performanceStats?.averageTokensPerSecond ?: 0f))
                LedgerStatRow("Peak tokens/sec", formatSpeedOrUnavailable(performanceStats?.peakTokensPerSecond ?: 0f))
                LedgerStatRow("Last generation", formatSpeedOrUnavailable(performanceStats?.tokensPerSecond ?: 0f))
                LedgerStatRow("Prompt evaluation", formatSpeedOrUnavailable(performanceStats?.promptTokensPerSecond ?: 0f))
                LedgerStatRow("Decode speed", formatSpeedOrUnavailable(performanceStats?.decodeTokensPerSecond ?: 0f))
                LedgerStatRow("Prompt tokens", formatTokenCountOrUnavailable(performanceStats?.promptTokens ?: 0L))
                LedgerStatRow("Generated tokens", formatTokenCountOrUnavailable(performanceStats?.generatedTokens ?: 0L))
                LedgerStatRow(
                    "Total tokens",
                    formatTokenCountOrUnavailable(
                        (performanceStats?.promptTokens ?: 0L) + (performanceStats?.generatedTokens ?: 0L)
                    )
                )
                LedgerStatRow("Inference duration", formatDurationOrUnavailable(performanceStats?.totalTimeMs ?: 0L))

                // Genuine runtime CPU fallback — the ONLY case a warning is shown
                if (stats.isCpuFallback) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Surface(
                        color = Color(0xFFE0A33D).copy(alpha = 0.15f),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "⚠ Running on CPU — ${stats.backendReason}",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF8A5A00),
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }

                // Inference status (runtime, from real decodes)
                if (stats.isGpuAccelerated) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = when {
                            stats.gpuInferenceVerified -> "✓ ${stats.gpuBackendLabel} inference active"
                            stats.vulkanValidationFailed -> "Inference active (self-test mismatch — see Diagnostics)"
                            else -> "Verifying ${stats.gpuBackendLabel} inference…"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = when {
                            stats.gpuInferenceVerified -> successGreen
                            stats.vulkanValidationFailed -> MaterialTheme.ledger.lampDeep
                            else -> MaterialTheme.colorScheme.outline
                        }
                    )
                }

                // GPU allocator metrics. LiteRT-LM has no public API for
                // these counters on Android, so every unavailable counter is
                // explicit rather than rendered as a deceptive 0 MB.
                if (stats.isGpuAccelerated) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "GPU / Vulkan memory",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    MemoryStatRow("GPU allocated", if (stats.hasGpuAllocatedMetric) formatBytesOrUnavailable(stats.gpuMemoryAllocatedBytes) else UNAVAILABLE_ON_DEVICE)
                    MemoryStatRow("GPU used", if (stats.hasGpuUsedMetric) formatBytesOrUnavailable(stats.gpuMemoryUsedBytes) else UNAVAILABLE_ON_DEVICE)
                    MemoryStatRow(
                        "GPU free / total",
                        if (stats.hasGpuFreeTotalMetric) {
                            "${formatBytesOrUnavailable(stats.gpuMemoryFreeBytes)} / ${formatBytesOrUnavailable(stats.gpuMemoryTotalBytes)}"
                        } else {
                            UNAVAILABLE_ON_DEVICE
                        }
                    )
MemoryStatRow("GPU peak", if (stats.hasGpuPeakMetric) formatBytesOrUnavailable(stats.gpuMemoryPeakBytes) else UNAVAILABLE_ON_DEVICE)
                    MemoryStatRow("KV cache", formatKvCacheTokensOrUnavailable(stats.kvCacheTokens))
                    MemoryStatRow("Vulkan buffers", UNAVAILABLE_ON_DEVICE)
                    MemoryStatRow("Allocated buffers", if (stats.hasGpuBufferMetric) stats.gpuBufferCount.toString() else UNAVAILABLE_ON_DEVICE)
                }

                // ── Runtime recovery / CPU session fallback (collapsible) ──
                if (stats.cpuSessionFallback || stats.recoveryCount > 0) {
                    Spacer(modifier = Modifier.height(8.dp))
                    RuntimeRecoveryDiagnostics(
                        cpuSessionFallback = stats.cpuSessionFallback,
                        recoveryCount = stats.recoveryCount,
                        lastRecoveryReason = stats.lastRecoveryReason
                    )
                }

                // ── Diagnostics: Vulkan correctness self-test (collapsible) ──
                if (stats.vulkanValidationFailed) {
                    Spacer(modifier = Modifier.height(8.dp))
                    VulkanValidationDiagnostics(detail = stats.vulkanValidationDetail)
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

private const val UNAVAILABLE_ON_DEVICE = "Unavailable on this device"

private fun formatContextLength(tokens: Int): String =
    "${(tokens / 1024).coerceAtLeast(1)}K"

private fun formatBytesOrUnavailable(bytes: Long): String =
    if (bytes > 0L) "%.0f MB".format(bytes / (1024f * 1024f)) else UNAVAILABLE_ON_DEVICE

/** Live KV-cache occupancy in tokens (LiteRT-LM exposes the cache as a token count, not bytes). */
private fun formatKvCacheTokensOrUnavailable(tokens: Long): String =
    if (tokens >= 0L) "$tokens tokens" else UNAVAILABLE_ON_DEVICE

private fun formatSpeedOrUnavailable(tokensPerSecond: Float): String =
    if (tokensPerSecond > 0f) "%.1f tok/s".format(tokensPerSecond) else UNAVAILABLE_ON_DEVICE

private fun formatTokenCountOrUnavailable(tokens: Long): String =
    if (tokens > 0L) tokens.toString() else UNAVAILABLE_ON_DEVICE

private fun formatDurationOrUnavailable(durationMs: Long): String =
    if (durationMs > 0L) {
        if (durationMs < 1_000L) "$durationMs ms" else "%.2f s".format(durationMs / 1_000f)
    } else {
        UNAVAILABLE_ON_DEVICE
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

/**
 * Ledger-style stat row: muted label on the left, semi-bold value on the right.
 */
@Composable
private fun LedgerStatRow(
    label: String,
    value: String,
    valueColor: Color = MaterialTheme.ledger.deskInk
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = valueColor,
            textAlign = TextAlign.End,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Collapsible diagnostics panel for the Vulkan correctness self-test.
 * The self-test result is diagnostic-only and never affects which backend is
 * actually executing inference.
 */
@Composable
private fun RuntimeRecoveryDiagnostics(
    cpuSessionFallback: Boolean,
    recoveryCount: Int,
    lastRecoveryReason: String
) {
    var expanded by remember { mutableStateOf(false) }

    Surface(
        color = if (cpuSessionFallback) Color(0xFFC7442F).copy(alpha = 0.10f)
                else Color(0xFFE0A33D).copy(alpha = 0.12f),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(
            1.dp,
            if (cpuSessionFallback) Color(0xFFC7442F).copy(alpha = 0.4f)
            else Color(0xFFE0A33D).copy(alpha = 0.4f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (cpuSessionFallback) "⚠ Running on CPU for this session"
                               else "⚠ Runtime recovery occurred",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (cpuSessionFallback) Color(0xFFB3261E) else Color(0xFFB3573E)
                    )
                    Text(
                        text = if (cpuSessionFallback)
                            "GPU recovery failed — inference continues on CPU. Vulkan buffers were recreated and the prompt retried before falling back."
                        else
                            "A corrupted inference run was detected and automatically recovered (context recreated, backend reloaded).",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF8A5A00),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = if (expanded) "Hide Details ▲" else "Show Details ▾",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFB3573E)
                )
            }
            if (expanded) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Recoveries: $recoveryCount\nLast reason: ${lastRecoveryReason.ifBlank { "—" }}",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.ledger.deskInk
                )
            }
        }
    }
}

@Composable
private fun VulkanValidationDiagnostics(detail: String) {
    var expanded by remember { mutableStateOf(false) }

    Surface(
        color = Color(0xFFE0A33D).copy(alpha = 0.12f),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, Color(0xFFE0A33D).copy(alpha = 0.4f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "⚠ Vulkan validation mismatch",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFB3573E)
                    )
                    Text(
                        text = "This only affects the validation self-test. Inference continues using Vulkan.",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF8A5A00),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = if (expanded) "Hide Details ▲" else "Show Details ▾",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFB3573E)
                )
            }
            if (expanded) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = detail.ifBlank { "No mismatch details captured." },
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.ledger.deskInk
                )
            }
        }
    }
}
