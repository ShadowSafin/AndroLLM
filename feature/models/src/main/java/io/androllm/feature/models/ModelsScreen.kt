package io.androllm.feature.models

import io.androllm.core.models.ModelCategory

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.MoreVert
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
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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
import io.androllm.core.utils.DeviceHardwareInfo
import io.androllm.core.utils.DeviceInfoCollector
import io.androllm.feature.models.benchmark.BenchmarkReport
import io.androllm.feature.models.benchmark.ModelBenchmarker
import io.androllm.feature.models.compatibility.CompatibilityAnalyzer
import io.androllm.feature.models.compatibility.CompatibilityRating
import io.androllm.engine.api.EngineState
import io.androllm.engine.models.MemoryStats

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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Model Manager", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = { sortMenuExpanded = true }) {
                        Icon(Icons.Default.Sort, contentDescription = "Sort models")
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
                        Icon(Icons.Default.Folder, contentDescription = "Import GGUF")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
            )
        }
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
                    text = { Text("Catalog (${data.catalogModels.size})") }
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
                ModelsTab.CATALOG -> OfficialCatalogTab(
                    catalogModels = data.catalogModels,
                    installedModels = data.installedModels,
                    onDownload = { viewModel.downloadModel(it) }
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
                InstalledModelCard(
                    model = model,
                    isLoaded = model.id == data.loadedModelId,
                    isLoading = model.id == data.loadingModelId,
                    hardwareInfo = data.hardwareInfo,
                    onLoad = { viewModel.loadModel(model) },
                    onUnload = { viewModel.unloadModel(model) },
                    onFavoriteToggle = { viewModel.toggleFavorite(model) },
                    onSetDefault = { viewModel.setDefaultModel(model) },
                    onBenchmark = { viewModel.runBenchmark(model) },
                    onDelete = { viewModel.deleteModel(model) },
                    onRetryDownload = { viewModel.downloadModel(model) }
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
                    fontWeight = FontWeight.Bold
                )

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
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "${bytes.formatSize()} / ${total.formatSize()} ($percent%)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (status == DownloadStatus.DOWNLOADING) {
                    Text(
                        text = "${"%.1f".format(speed)} MB/s • ${eta}s left",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                } else if (status == DownloadStatus.ERROR) {
                    Text(
                        text = progress?.errorMessage ?: "Download Failed (Check network / url)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (status == DownloadStatus.DOWNLOADING) {
                    OutlinedButton(onClick = onPause) {
                        Icon(Icons.Default.Pause, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Pause")
                    }
                } else if (status == DownloadStatus.PAUSED) {
                    Button(onClick = onResume) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Resume")
                    }
                } else if (status == DownloadStatus.ERROR) {
                    Button(onClick = onRetry) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Retry")
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                TextButton(onClick = onCancel) {
                    Icon(Icons.Default.Cancel, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Cancel", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun InstalledModelCard(
    model: Model,
    isLoaded: Boolean,
    isLoading: Boolean,
    hardwareInfo: DeviceHardwareInfo?,
    onLoad: () -> Unit,
    onUnload: () -> Unit,
    onFavoriteToggle: () -> Unit,
    onSetDefault: () -> Unit,
    onBenchmark: () -> Unit,
    onDelete: () -> Unit,
    onRetryDownload: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }

    val compatibility = remember(model, hardwareInfo) {
        hardwareInfo?.let { CompatibilityAnalyzer.analyze(model, it) }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isLoaded) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
            else MaterialTheme.colorScheme.surfaceContainer
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = model.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    if (model.isDefault) {
                        Spacer(modifier = Modifier.width(6.dp))
                        AssistChip(
                            onClick = {},
                            label = { Text("Default", style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier.height(24.dp)
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onFavoriteToggle, modifier = Modifier.size(28.dp)) {
                        Icon(
                            imageVector = if (model.isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                            contentDescription = "Favorite",
                            tint = if (model.isFavorite) Color(0xFFFFD54F) else MaterialTheme.colorScheme.outline
                        )
                    }

                    Box {
                        IconButton(onClick = { menuExpanded = true }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Options")
                        }

                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Set as Default") },
                                onClick = { menuExpanded = false; onSetDefault() }
                            )
                            DropdownMenuItem(
                                text = { Text("Run Benchmark") },
                                onClick = { menuExpanded = false; onBenchmark() },
                                leadingIcon = { Icon(Icons.Default.Speed, contentDescription = null) }
                            )
                            DropdownMenuItem(
                                text = { Text("Delete Model", color = MaterialTheme.colorScheme.error) },
                                onClick = { menuExpanded = false; onDelete() },
                                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AssistChip(
                    onClick = {},
                    label = { Text(model.quantization.ifBlank { "Q4_K_M" }) }
                )
                AssistChip(
                    onClick = {},
                    label = { Text("${model.contextLength} ctx") }
                )
                Text(
                    text = model.fileSize.formatSize(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }

            compatibility?.let { comp ->
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = comp.title,
                    style = MaterialTheme.typography.labelSmall,
                    color = when (comp.rating) {
                        CompatibilityRating.EXCELLENT -> Color(0xFFA6E3A1)
                        CompatibilityRating.MODERATE -> Color(0xFFFFE082)
                        else -> MaterialTheme.colorScheme.error
                    },
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (model.downloadStatus == DownloadStatus.DOWNLOADING || (!model.isDownloaded && model.downloadStatus != DownloadStatus.ERROR)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(
                            text = "Downloading...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                } else if (model.downloadStatus == DownloadStatus.ERROR) {
                    OutlinedButton(onClick = onRetryDownload) {
                        Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Retry Download")
                    }
                } else if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                } else if (isLoaded) {
                    OutlinedButton(onClick = onUnload) {
                        Text("Unload")
                    }
                } else {
                    Button(onClick = onLoad) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Load Model")
                    }
                }
            }
        }
    }
}

@Composable
private fun OfficialCatalogTab(
    catalogModels: List<Model>,
    installedModels: List<Model>,
    onDownload: (Model) -> Unit
) {
    val categories: List<Pair<ModelCategory, String>> = listOf(
        ModelCategory.RECOMMENDED to "⭐ Recommended",
        ModelCategory.CHAT to "💬 General Chat",
        ModelCategory.REASONING to "🧠 Reasoning",
        ModelCategory.MOBILE_OPTIMIZED to "⚡ Mobile Optimized"
    )

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        categories.forEach { (cat, title) ->
            val sectionModels = catalogModels.filter { it.category == cat }
            if (sectionModels.isNotEmpty()) {
                item(key = "header_${cat.name}") {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }

                items(sectionModels, key = { it.id }) { model ->
                    val installed = installedModels.find { it.id == model.id }
                    val isDownloaded = installed?.isDownloaded == true
                    val isDownloading = installed != null && !installed.isDownloaded && installed.downloadStatus != DownloadStatus.ERROR

                    Card(
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
                                    text = model.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )

                                if (isDownloaded) {
                                    StatusBadge(status = DownloadStatus.DOWNLOADED)
                                } else if (isDownloading) {
                                    StatusBadge(status = DownloadStatus.DOWNLOADING)
                                }
                            }

                            if (model.badges.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    model.badges.take(3).forEach { badge ->
                                        AssistChip(
                                            onClick = {},
                                            label = { Text(badge, style = MaterialTheme.typography.labelSmall) },
                                            modifier = Modifier.height(24.dp)
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = model.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(8.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Size: ${model.fileSize.formatSize()} | RAM: ${"%.0f".format(model.minRamGb)} GB+",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline
                                )

                                if (isDownloaded) {
                                    OutlinedButton(onClick = {}, enabled = false) {
                                        Text("Installed")
                                    }
                                } else if (isDownloading) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Downloading...", style = MaterialTheme.typography.labelMedium)
                                    }
                                } else {
                                    Button(onClick = { onDownload(model) }) {
                                        Icon(Icons.Default.CloudDownload, contentDescription = null)
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Download")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

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
                                fontWeight = FontWeight.Bold
                            )
                            AssistChip(onClick = {}, label = { Text(remote.family) })
                        }
                        Text(
                            text = "by ${remote.author}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.Download, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.outline)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("${remote.downloads}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Favorite, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color(0xFFF38BA8))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("${remote.likes}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
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
            Text(text = details.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(text = "by ${details.author} • License: ${details.license}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
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
                                    Text(file.filename, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                                    Text("Quant: ${file.quantization} | RAM: ${"%.0f".format(file.minRamGb)} GB+", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                                }
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
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
        Text(text = value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun StatusBadge(status: DownloadStatus) {
    val (label, color) = when (status) {
        DownloadStatus.DOWNLOADED -> "Installed" to Color(0xFFA6E3A1)
        DownloadStatus.DOWNLOADING -> "Downloading" to Color(0xFF89B4FA)
        DownloadStatus.QUEUED -> "Queued" to Color(0xFFF9E2AF)
        DownloadStatus.PAUSED -> "Paused" to Color(0xFFFAB387)
        DownloadStatus.ERROR -> "Failed" to MaterialTheme.colorScheme.error
        DownloadStatus.NOT_DOWNLOADED -> "Not Installed" to MaterialTheme.colorScheme.outline
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
