package io.androllm.feature.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.MotionPhotosOn
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import io.androllm.core.common.UiState
import io.androllm.core.models.ThemeMode
import io.androllm.core.ui.components.CloudAdaptiveNavigation
import io.androllm.core.ui.components.CloudAtmosphericBackground
import io.androllm.core.ui.components.CloudBugdroidLogo
import io.androllm.core.ui.components.CloudCapsuleButton
import io.androllm.core.ui.components.CloudChip
import io.androllm.core.ui.components.CloudGlassCard
import io.androllm.core.ui.components.SectionHeader
import io.androllm.core.ui.theme.DeskInk
import io.androllm.core.ui.theme.DeskInkFaint
import io.androllm.core.ui.theme.DeskPaper
import io.androllm.core.ui.theme.LampAmber
import io.androllm.core.ui.theme.LampDeep
import io.androllm.core.ui.theme.LampGlow
import io.androllm.core.utils.StorageUtils
import io.androllm.feature.settings.R

/**
 * Writer's Night Desk — Settings. Identity, storage, motion and privacy,
 * all kept in walnut under the lamp.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    navController: NavController,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val logPreview by viewModel.logPreview.collectAsStateWithLifecycle()
    val user by viewModel.user.collectAsStateWithLifecycle()
    val memorySettings by viewModel.memorySettings.collectAsStateWithLifecycle()
    val memoryStats by viewModel.memoryStats.collectAsStateWithLifecycle()
    val memoryMessage by viewModel.memoryMessage.collectAsStateWithLifecycle()
    val storageStats by viewModel.storageStats.collectAsStateWithLifecycle()
    val voiceSettings by viewModel.voiceSettings.collectAsStateWithLifecycle()
    val voiceState by viewModel.voiceState.collectAsStateWithLifecycle()
    val automationSettings by viewModel.automationSettings.collectAsStateWithLifecycle()
    val accessibilitySettings by viewModel.accessibilitySettings.collectAsStateWithLifecycle()
    val mcpServers by viewModel.mcpServers.collectAsStateWithLifecycle()
    val mcpStates by viewModel.mcpStates.collectAsStateWithLifecycle()
    val whisperModels by viewModel.whisperModels.collectAsStateWithLifecycle()
    val whisperInstalled by viewModel.whisperInstalled.collectAsStateWithLifecycle()
    val whisperDownload by viewModel.whisperDownload.collectAsStateWithLifecycle()
    val whisperMessage by viewModel.whisperMessage.collectAsStateWithLifecycle()
    val whisperStorageBytes = viewModel.whisperStorageBytes
    val overlayGranted = viewModel.overlayGranted
    val settings = (uiState as? UiState.Success)?.data ?: SettingsData()

    var reduceMotion by remember { mutableStateOf(false) }
    var showModelPathDialog by remember { mutableStateOf(false) }
    var showCloudEmbeddingDialog by remember { mutableStateOf(false) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> viewModel.importMemories(uri) }

    // Tool-calling runtime permissions (SMS, contacts, calls, calendar). The
    // tools fail fast without them, so Settings → Automation offers a grant
    // button; rows recompose away once the permission is granted.
    val automationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* nothing to do — the section recomposes from the grant state */ }

    LaunchedEffect(memoryMessage) {
        if (memoryMessage != null) {
            kotlinx.coroutines.delay(4000)
            viewModel.clearMemoryMessage()
        }
    }

    CloudAtmosphericBackground(reduceMotion = reduceMotion) {
        CloudAdaptiveNavigation(
            currentRoute = io.androllm.core.navigation.Routes.SETTINGS,
            onTabSelected = { tab ->
                if (tab.route != io.androllm.core.navigation.Routes.SETTINGS) {
                    navController.navigate(tab.route)
                }
            },
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            stringResource(R.string.settings_title),
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Bold,
                                color = DeskPaper
                            )
                        )
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
                )
            }
        ) { padding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                // 1. User Profile Header Card
                item {
                    UserProfileCard(user = user)
                }

                // 2. User Statistics Cards
                item {
                    UserStatsRow()
                }

                // 3. Firebase Authentication Section
                item {
                    FirebaseAuthCard(
                        user = user,
                        onSignIn = { navController.navigate(io.androllm.core.navigation.Routes.AUTH) }
                    )
                }

                // 4. Appearance & Motion
                item {
                    SectionHeader(title = stringResource(R.string.settings_appearance))
                    CloudGlassCard(modifier = Modifier.fillMaxWidth()) {
                        Column {
                            SettingRow(
                                icon = Icons.Filled.Palette,
                                title = stringResource(R.string.settings_theme),
                                value = settings.theme.displayName(),
                                onClick = { viewModel.cycleTheme() }
                            )
                            SettingRow(
                                icon = Icons.Filled.MotionPhotosOn,
                                title = "Reduce Background Motion",
                                value = if (reduceMotion) "Enabled" else "Disabled",
                                onClick = { reduceMotion = !reduceMotion }
                            )
                        }
                    }
                }

                // 5. Storage
                item {
                    SectionHeader(title = stringResource(R.string.settings_storage))
                    CloudGlassCard(modifier = Modifier.fillMaxWidth()) {
                        Column {
                            SettingRow(
                                icon = Icons.Filled.Storage,
                                title = stringResource(R.string.settings_storage_path),
                                value = settings.storagePath.ifEmpty { "Internal Storage" },
                                onClick = {}
                            )
                            SettingRow(
                                icon = Icons.Filled.Storage,
                                title = "Free Space",
                                value = storageStats?.let {
                                    "${StorageUtils.formatBytes(it.availableBytes)} free"
                                } ?: "…",
                                onClick = { viewModel.refreshStorageStats() }
                            )
                            SettingRow(
                                icon = Icons.Filled.Storage,
                                title = "Models on Device",
                                value = storageStats?.let {
                                    StorageUtils.formatBytes(it.usedBytes)
                                } ?: "…",
                                onClick = { viewModel.refreshStorageStats() }
                            )
                            SettingRow(
                                icon = Icons.Filled.Storage,
                                title = stringResource(R.string.settings_clear_cache),
                                onClick = { viewModel.clearCache() }
                            )
                        }
                    }
                }

                // 6. On-device Memory
                item {
                    SectionHeader(title = "Memory")
                    MemorySettingsCard(
                        settings = memorySettings,
                        stats = memoryStats,
                        feedback = memoryMessage,
                        onToggleEnabled = { viewModel.toggleMemoryEnabled() },
                        onThresholdChange = { viewModel.updateSimilarityThreshold(it) },
                        onRetrievalCountChange = { viewModel.updateRetrievalCount(it) },
                        onSummarizationIntervalChange = { viewModel.updateSummarizationInterval(it) },
                        onModelPathClick = { showModelPathDialog = true },
                        onTestModel = { viewModel.testEmbeddingModel() },
                        onCloudEmbeddingClick = { showCloudEmbeddingDialog = true },
                        onExport = { viewModel.exportMemories() },
                        onImport = { importLauncher.launch(arrayOf("application/json", "application/octet-stream", "text/plain")) },
                        onDeleteAll = { viewModel.deleteAllMemories() },
                        onRefreshStats = { viewModel.refreshMemoryStats() },
                        onInspectorClick = {
                            navController.navigate(io.androllm.core.navigation.Routes.DEVELOPER)
                        }
                    )
                }

                // 7. Voice Assistant (always-on wake word)
                item {
                    SectionHeader(title = "Voice Assistant")
                    VoiceAssistantSection(
                        settings = voiceSettings,
                        liveState = voiceState,
                        overlayGranted = overlayGranted,
                        onUpdate = { viewModel.updateVoiceSettings(it) },
                        onStart = { viewModel.startVoiceAssistant() },
                        onStop = { viewModel.stopVoiceAssistant() },
                        onOpenOverlayPermission = { viewModel.openOverlayPermissionSettings() }
                    )
                }

// 7b. Speech Recognition (whisper.cpp)
                item {
                    SectionHeader(title = "Speech Recognition")
                    SpeechRecognitionSection(
                        settings = voiceSettings,
                        models = whisperModels,
                        installedIds = whisperInstalled,
                        download = whisperDownload,
                        message = whisperMessage,
                        storageBytes = whisperStorageBytes,
                        onSelectModel = { viewModel.selectWhisperModel(it) },
                        onDownloadModel = { viewModel.downloadWhisperModel(it) },
                        onDeleteModel = { viewModel.deleteWhisperModel(it) },
                        onUpdate = { viewModel.updateVoiceSettings(it) }
                    )
                }

                // 7c. Text Normalization (LLM output → TTS-ready speech)
                item {
                    SectionHeader(title = "Text Normalization")
                    TextNormalizationSection(
                        settings = voiceSettings,
                        onUpdate = { viewModel.updateVoiceSettings(it) }
                    )
                }

                // 7d. Automation (Tool Calling — per-tool permission management)
                item {
                    SectionHeader(title = "Automation")
                    AutomationSection(
                        settings = automationSettings,
                        tools = viewModel.tools,
                        onUpdate = { viewModel.updateAutomationSettings(it) },
                        onRequestPermissions = { perms -> automationPermissionLauncher.launch(perms.toTypedArray()) }
                    )
                }

                // 7e. UI Automation (accessibility engine — last-resort UI control)
                item {
                    SectionHeader(title = "UI Automation")
                    AccessibilitySection(
                        settings = accessibilitySettings,
                        serviceEnabled = viewModel.accessibilityServiceEnabled,
                        connected = viewModel.accessibilityConnected,
                        onUpdate = { viewModel.updateAccessibilitySettings(it) },
                        onOpenSettings = { viewModel.openAccessibilitySettings() }
                    )
                }

                // 7f. Permissions & Access (central gate manager)
                item {
                    SectionHeader(title = "Permissions & Access")
                    CloudGlassCard(modifier = Modifier.fillMaxWidth()) {
                        Column {
                            SettingRow(
                                icon = Icons.Filled.Lock,
                                title = "Permissions & Access",
                                value = "Microphone, notifications, accessibility…",
                                onClick = { navController.navigate(io.androllm.core.navigation.Routes.PERMISSIONS) }
                            )
                        }
                    }
                }

                // 7g. MCP Servers (remote tool imports)
                item {
                    SectionHeader(title = "MCP Servers")
                    McpSection(
                        servers = mcpServers,
                        states = mcpStates,
                        onAdd = { name, url, token -> viewModel.addMcpServer(name, url, token) },
                        onRemove = { viewModel.removeMcpServer(it) },
                        onToggle = { server, enabled -> viewModel.setMcpServerEnabled(server, enabled) }
                    )
                }

                // 8. Cloud Providers (LiteLLM gateway)
                item {
                    SectionHeader(title = "Cloud Providers")
                    CloudGlassCard(modifier = Modifier.fillMaxWidth()) {
                        Column {
                            SettingRow(
                                icon = Icons.Filled.CloudDone,
                                title = "LiteLLM Gateway",
                                value = "Manage providers & models",
                                onClick = { navController.navigate(io.androllm.core.navigation.Routes.CLOUD_PROVIDERS) }
                            )
                        }
                    }
                }

                // 8. Developer Options
                item {
                    SectionHeader(title = stringResource(R.string.settings_developer))
                    CloudGlassCard(modifier = Modifier.fillMaxWidth()) {
                        Column {
                            SettingRow(
                                icon = Icons.Filled.Code,
                                title = stringResource(R.string.settings_developer_mode),
                                value = settings.developerMode.displayYesNo(),
                                onClick = { viewModel.toggleDeveloperMode() }
                            )
                        }
                    }
                }

                // 9. Logs & Diagnostics
                item {
                    SectionHeader(title = stringResource(R.string.settings_logs))
                    CloudGlassCard(modifier = Modifier.fillMaxWidth()) {
                        Column {
                            SettingRow(
                                icon = Icons.Filled.Settings,
                                title = stringResource(R.string.settings_export_logs),
                                onClick = { viewModel.exportLogs() }
                            )
                            SettingRow(
                                icon = Icons.Filled.Refresh,
                                title = stringResource(R.string.settings_refresh_logs),
                                onClick = { viewModel.refreshLogPreview() }
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = logPreview.ifBlank { stringResource(R.string.settings_logs_empty) },
                                style = MaterialTheme.typography.bodySmall,
                                color = DeskInk,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }
                    }
                }

                // 10. About & Privacy
                item {
                    SectionHeader(title = stringResource(R.string.settings_about))
                    CloudGlassCard(modifier = Modifier.fillMaxWidth()) {
                        Column {
                            SettingRow(
                                icon = Icons.Filled.Info,
                                title = stringResource(R.string.settings_version),
                                value = "3.0.0 (Cloud Intelligence)",
                                onClick = {}
                            )
                            SettingRow(
                                icon = Icons.Filled.Security,
                                title = "Privacy Guarantee",
                                value = "100% Offline AI",
                                onClick = {}
                            )
                        }
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
    }

    if (showModelPathDialog) {
        ModelPathDialog(
            currentPath = memorySettings.embeddingModelPath,
            onDismiss = { showModelPathDialog = false },
            onSave = { path ->
                showModelPathDialog = false
                viewModel.setEmbeddingModelPath(path)
            }
        )
    }

    if (showCloudEmbeddingDialog) {
        CloudEmbeddingModelDialog(
            currentModel = memorySettings.cloudEmbeddingModel,
            onDismiss = { showCloudEmbeddingDialog = false },
            onSave = { modelId ->
                showCloudEmbeddingDialog = false
                viewModel.setCloudEmbeddingModel(modelId)
            }
        )
    }
}

/**
 * Large Floating User Profile Header Card.
 */
@Composable
private fun UserProfileCard(user: SettingsIdentity?) {
    CloudGlassCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            CloudBugdroidLogo(size = 72.dp)
            Spacer(modifier = Modifier.width(18.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = user?.displayName?.takeIf { it.isNotBlank() } ?: "AndroLLM User",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = DeskPaper
                    ),
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = user?.displayName?.takeIf { it.isNotBlank() } ?: "",
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = DeskInkFaint
                    ),
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
                Text(
                    text = if (user?.isGuest == false) {
                        user?.email ?: ""
                    } else {
                        "Guest • 100% on-device"
                    },
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = if (user?.isGuest == false) LampDeep else DeskInkFaint
                    ),
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * User Statistics Row.
 */
@Composable
private fun UserStatsRow() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        CloudGlassCard(modifier = Modifier.weight(1f)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Downloaded", style = MaterialTheme.typography.labelSmall.copy(color = DeskInk))
                Text("3 Models", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = DeskPaper))
            }
        }
        CloudGlassCard(modifier = Modifier.weight(1f)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Storage", style = MaterialTheme.typography.labelSmall.copy(color = DeskInk))
                Text("4.2 GB", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = DeskPaper))
            }
        }
        CloudGlassCard(modifier = Modifier.weight(1f)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Execution", style = MaterialTheme.typography.labelSmall.copy(color = DeskInk))
                Text("Vulkan", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = LampDeep))
            }
        }
    }
}

/**
 * Account & Sync Section.
 */
@Composable
private fun FirebaseAuthCard(
    user: SettingsIdentity?,
    onSignIn: () -> Unit
) {
    CloudGlassCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column {
                    Text(
                        text = "Account & Sync",
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = FontWeight.Bold,
                            color = DeskPaper
                        )
                    )
                    Text(
                        text = if (user?.isGuest == false) {
                            "Synced as ${user?.email ?: "your account"}"
                        } else {
                            "Syncing is optional — offline AI never requires a login"
                        },
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = DeskInk
                        ),
                        maxLines = 2,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }
                CloudChip(
                    text = if (user?.isGuest == false) "Signed In" else "Optional",
                    accentColor = if (user?.isGuest == false) LampDeep else DeskInk
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            CloudCapsuleButton(
                text = if (user?.isGuest == false) "Manage Account" else "Sign in with Google",
                onClick = onSignIn,
                icon = Icons.Filled.AccountCircle,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
internal fun SettingRow(
    icon: ImageVector,
    title: String,
    value: String? = null,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = LampDeep,
            modifier = Modifier.size(20.dp)
        )
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = FontWeight.Medium,
                color = DeskPaper
            ),
            modifier = Modifier.weight(1f)
        )
        if (value != null) {
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall.copy(
                    color = DeskInkFaint
                )
            )
        }
    }
}

/**
 * On-device Memory — master switch, similarity threshold, retrieval count,
 * embedding model wiring, export/import and full wipe.
 */
@Composable
private fun MemorySettingsCard(
    settings: io.androllm.core.memory.model.MemorySettings,
    stats: io.androllm.core.memory.model.MemoryInspectorStats?,
    feedback: String?,
    onToggleEnabled: () -> Unit,
    onThresholdChange: (Float) -> Unit,
    onRetrievalCountChange: (Int) -> Unit,
    onSummarizationIntervalChange: (Int) -> Unit,
    onModelPathClick: () -> Unit,
    onTestModel: () -> Unit,
    onCloudEmbeddingClick: () -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onDeleteAll: () -> Unit,
    onRefreshStats: () -> Unit,
    onInspectorClick: () -> Unit
) {
    CloudGlassCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            // Master switch
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggleEnabled)
                    .padding(horizontal = 12.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Memory,
                    contentDescription = null,
                    tint = if (settings.enabled) LampAmber else LampGlow,
                    modifier = Modifier.size(20.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "On-device Memory",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.Medium,
                            color = DeskPaper
                        )
                    )
                    Text(
                        text = "Personalized replies from your own conversations — never leaves this device",
                        style = MaterialTheme.typography.bodySmall.copy(color = DeskInk),
                        maxLines = 2,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }
                Switch(
                    checked = settings.enabled,
                    onCheckedChange = { onToggleEnabled() },
                    colors = SwitchDefaults.colors(checkedThumbColor = LampAmber)
                )
            }

            if (settings.enabled) {
                HorizontalDivider(color = DeskInkFaint.copy(alpha = 0.25f))

                // Stats line
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = stats?.let { "${it.memoryCount} memories • ${it.embeddingCount} embeddings" } ?: "…",
                        style = MaterialTheme.typography.bodySmall.copy(color = DeskInkFaint),
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onRefreshStats) {
                        Text("Refresh", color = LampGlow)
                    }
                }

                if (feedback != null) {
                    Text(
                        text = feedback,
                        style = MaterialTheme.typography.bodySmall.copy(color = LampAmber),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }

                // Similarity threshold
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(
                        text = "Similarity threshold  ${(settings.similarityThreshold * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall.copy(color = DeskInk)
                    )
                    Slider(
                        value = settings.similarityThreshold,
                        onValueChange = onThresholdChange,
                        valueRange = io.androllm.core.memory.model.MemorySettings.THRESHOLD_MIN..io.androllm.core.memory.model.MemorySettings.THRESHOLD_MAX,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // Retrieval count
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(
                        text = "Memories retrieved per prompt: ${settings.retrievalCount}",
                        style = MaterialTheme.typography.labelSmall.copy(color = DeskInk)
                    )
                    Slider(
                        value = settings.retrievalCount.toFloat(),
                        onValueChange = { onRetrievalCountChange(it.toInt()) },
                        valueRange = io.androllm.core.memory.model.MemorySettings.RETRIEVAL_MIN.toFloat()..
                            io.androllm.core.memory.model.MemorySettings.RETRIEVAL_MAX.toFloat(),
                        steps = io.androllm.core.memory.model.MemorySettings.RETRIEVAL_MAX -
                            io.androllm.core.memory.model.MemorySettings.RETRIEVAL_MIN - 1,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // Summarization interval
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(
                        text = "Summarize every ${settings.summarizationInterval} messages",
                        style = MaterialTheme.typography.labelSmall.copy(color = DeskInk)
                    )
                    Slider(
                        value = settings.summarizationInterval.toFloat(),
                        onValueChange = { onSummarizationIntervalChange(it.toInt()) },
                        valueRange = io.androllm.core.memory.model.MemorySettings.SUMMARIZATION_MIN.toFloat()..
                            io.androllm.core.memory.model.MemorySettings.SUMMARIZATION_MAX.toFloat(),
                        steps = 10,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // Embedding model
                SettingRow(
                    icon = Icons.Filled.Tune,
                    title = "Embedding model",
                    value = settings.embeddingModelPath.substringAfterLast('/')
                        .takeIf { it.isNotBlank() }
                        ?: "Not configured",
                    onClick = onModelPathClick
                )
                SettingRow(
                    icon = Icons.Filled.PlayArrow,
                    title = "Test embedding model",
                    value = if (stats?.embeddingModelLoaded == true) "Loaded (dim ${stats.embeddingDimension})" else null,
                    onClick = onTestModel
                )
                SettingRow(
                    icon = Icons.Filled.CloudDone,
                    title = "Cloud embedding model",
                    value = settings.cloudEmbeddingModel.substringAfterLast('/')
                        .takeIf { it.isNotBlank() }
                        ?: "Not configured",
                    onClick = onCloudEmbeddingClick
                )

                HorizontalDivider(color = DeskInkFaint.copy(alpha = 0.25f))

                SettingRow(
                    icon = Icons.Filled.IosShare,
                    title = "Export memories",
                    onClick = onExport
                )
                SettingRow(
                    icon = Icons.Filled.FileUpload,
                    title = "Import memories",
                    onClick = onImport
                )
                SettingRow(
                    icon = Icons.Filled.Psychology,
                    title = "Memory Inspector",
                    value = "Open developer dashboard",
                    onClick = onInspectorClick
                )
                SettingRow(
                    icon = Icons.Filled.Delete,
                    title = "Delete all memories",
                    onClick = onDeleteAll
                )
            }
        }
    }
}

/**
 * Small dialog for entering the absolute path of the local LiteRT embedding
 * model (.tflite). The LiteRT engine expects the Gemma 3 `tokenizer.model`
 * next to the file (downloaded automatically with the catalog model).
 */
@Composable
private fun ModelPathDialog(
    currentPath: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var path by remember { mutableStateOf(currentPath) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Embedding model path", fontWeight = FontWeight.Bold, color = DeskPaper) },
        text = {
            Column {
                Text(
                    text = "Absolute path to the EmbeddingGemma 300M .tflite model (downloaded from the Models screen Catalog). A tokenizer.model must sit next to it — the app downloads both automatically.",
                    style = MaterialTheme.typography.bodySmall.copy(color = DeskInk)
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = path,
                    onValueChange = { path = it },
                    label = { Text("Model path") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(path.trim()) },
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = LampAmber)
            ) {
                Text("Save", color = DeskPaper)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = DeskInkFaint) }
        }
    )
}

/**
 * Small dialog for configuring the cloud embedding model id, routed through
 * the active LiteLLM provider. Empty clears the cloud route.
 */
@Composable
private fun CloudEmbeddingModelDialog(
    currentModel: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var model by remember { mutableStateOf(currentModel) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Cloud embedding model", fontWeight = FontWeight.Bold, color = DeskPaper) },
        text = {
            Column {
                Text(
                    text = "Model id for /v1/embeddings through your active cloud provider (e.g. openai/text-embedding-3-small, cohere/embed-english-v3.0, togethertext-embedding...). Leave empty to use the local LiteRT embedding model.",
                    style = MaterialTheme.typography.bodySmall.copy(color = DeskInk)
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text("Embedding model id") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(model.trim()) },
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = LampAmber)
            ) {
                Text("Save", color = DeskPaper)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = DeskInkFaint) }
        }
    )
}

private fun ThemeMode.displayName(): String = when (this) {
    ThemeMode.SYSTEM -> "System"
    ThemeMode.LIGHT -> "Light"
    ThemeMode.DARK -> "Dark"
}

private fun Boolean.displayYesNo(): String = if (this) "Yes" else "No"
